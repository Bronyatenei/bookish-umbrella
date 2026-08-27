import { spawn } from "node:child_process";
import fs from "node:fs/promises";
import path from "node:path";

function clampInteger(value, fallback, minimum, maximum) {
  const parsed = Number.parseInt(String(value ?? ""), 10);
  if (!Number.isFinite(parsed)) return fallback;
  return Math.min(maximum, Math.max(minimum, parsed));
}

function normaliseLogins(value, fieldName) {
  if (value === undefined) return [];
  if (!Array.isArray(value)) throw new Error(`${fieldName} must be an array of Twitch logins`);
  return [...new Set(value.map((login) => String(login).trim().toLowerCase()).filter(Boolean))];
}

function formatFileTimestamp(date = new Date()) {
  const pad = (value) => String(value).padStart(2, "0");
  return [
    date.getFullYear(),
    pad(date.getMonth() + 1),
    pad(date.getDate()),
    "_",
    pad(date.getHours()),
    pad(date.getMinutes()),
    pad(date.getSeconds())
  ].join("");
}

function shortError(error) {
  const text = `${error?.name || "Error"}: ${error?.message || String(error)}`;
  return text.replace(/\s+/g, " ").slice(0, 700);
}

/**
 * Parses optional Streamlink settings. Recording is disabled when recordChannels is omitted or empty.
 * Every requested recording channel must also be in the normal Home Agent channels list.
 */
export function createRecordingOptions(config, configDirectory, monitoredLogins) {
  const recordLogins = normaliseLogins(config.recordChannels, "config.recordChannels");
  if (recordLogins.length === 0) return null;

  const monitored = new Set(monitoredLogins);
  const missing = recordLogins.filter((login) => !monitored.has(login));
  if (missing.length > 0) {
    throw new Error(`Every config.recordChannels login must also exist in config.channels: ${missing.join(", ")}`);
  }

  const raw = config.recording ?? {};
  if (raw === null || Array.isArray(raw) || typeof raw !== "object") {
    throw new Error("config.recording must be an object when config.recordChannels is configured");
  }

  const outputDirectoryValue = String(raw.outputDirectory ?? "./recordings").trim();
  const quality = String(raw.quality ?? "best").trim();
  const streamlinkPath = String(raw.streamlinkPath ?? "streamlink").trim();
  if (!outputDirectoryValue) throw new Error("config.recording.outputDirectory must not be empty");
  if (!quality) throw new Error("config.recording.quality must not be empty");
  if (!streamlinkPath) throw new Error("config.recording.streamlinkPath must not be empty");

  return {
    recordLogins: new Set(recordLogins),
    outputDirectory: path.resolve(configDirectory, outputDirectoryValue),
    quality,
    streamlinkPath,
    maxRestarts: clampInteger(raw.maxRestarts, 5, 0, 12),
    restartDelaySeconds: clampInteger(raw.restartDelaySeconds, 20, 5, 300),
    streamSegmentAttempts: clampInteger(raw.streamSegmentAttempts, 5, 1, 10),
    playlistReloadAttempts: clampInteger(raw.playlistReloadAttempts, 5, 1, 10),
    streamTimeoutSeconds: clampInteger(raw.streamTimeoutSeconds, 60, 10, 300)
  };
}

/**
 * Runs one Streamlink child process for each selected currently-live channel. A disconnected
 * recorder is restarted a bounded number of times, with every continuation written to a new .ts
 * part so partially downloaded content is never overwritten.
 */
export class StreamRecorder {
  constructor(options) {
    this.options = options;
    this.active = new Map();
    this.observed = new Map();
    this.exhaustedStreamIds = new Map();
    this.restartTimers = new Map();
    this.stopped = false;
    this.streamlinkUnavailable = false;
  }

  get enabled() {
    return this.options !== null;
  }

  describeStartup() {
    if (!this.enabled) return null;
    return `Stream recording enabled for ${this.options.recordLogins.size} channel(s); output: ${this.options.outputDirectory}; reconnects: ${this.options.maxRestarts}.`;
  }

  observe(stream) {
    if (!this.enabled || !this.options.recordLogins.has(stream.login)) return;

    this.observed.set(stream.login, {
      isLive: Boolean(stream.isLive),
      streamId: stream.streamId || ""
    });

    if (!stream.isLive) {
      this.exhaustedStreamIds.delete(stream.login);
      return;
    }

    const exhaustedId = this.exhaustedStreamIds.get(stream.login);
    if (exhaustedId === stream.streamId) return;
    if (exhaustedId && exhaustedId !== stream.streamId) this.exhaustedStreamIds.delete(stream.login);

    const existing = this.active.get(stream.login);
    if (existing?.streamId === stream.streamId) return;
    if (existing) this.stopSession(existing, "a newer Twitch stream was detected");

    const timer = this.restartTimers.get(stream.login);
    if (timer) return;

    void this.start(stream, 0).catch((error) => {
      console.error(`[${new Date().toISOString()}] recording setup failed for ${stream.login}: ${shortError(error)}`);
    });
  }

  async start(stream, restartNumber) {
    if (this.stopped || this.streamlinkUnavailable || !stream.isLive) return;
    const alreadyActive = this.active.get(stream.login);
    if (alreadyActive?.streamId === stream.streamId) return;

    const session = {
      login: stream.login,
      streamId: stream.streamId || "unknown",
      restartNumber,
      starting: true,
      child: null,
      completed: false,
      stderr: "",
      outputFile: ""
    };
    this.active.set(stream.login, session);

    const channelDirectory = path.join(this.options.outputDirectory, stream.login);
    try {
      await fs.mkdir(channelDirectory, { recursive: true });
    } catch (error) {
      if (this.active.get(stream.login) === session) this.active.delete(stream.login);
      throw error;
    }
    if (this.stopped || this.active.get(stream.login) !== session) return;

    const part = restartNumber + 1;
    session.outputFile = path.join(
      channelDirectory,
      `${stream.login}_${stream.streamId || "unknown"}_${formatFileTimestamp()}_part-${String(part).padStart(2, "0")}.ts`
    );

    const args = [
      "--output", session.outputFile,
      "--progress", "no",
      "--retry-streams", "5",
      "--retry-max", "3",
      "--stream-segment-attempts", String(this.options.streamSegmentAttempts),
      "--hls-playlist-reload-attempts", String(this.options.playlistReloadAttempts),
      "--stream-timeout", String(this.options.streamTimeoutSeconds)
    ];
    // On a reconnect, continue from the earliest part of Twitch's still available live playlist.
    // It can create a small overlap, which is safer than losing the minutes around an outage.
    if (restartNumber > 0) args.push("--hls-live-restart");
    args.push(`https://www.twitch.tv/${encodeURIComponent(stream.login)}`, this.options.quality);

    let child;
    try {
      child = spawn(this.options.streamlinkPath, args, {
        stdio: ["ignore", "ignore", "pipe"],
        windowsHide: true
      });
    } catch (error) {
      this.finishSession(session, null, null, error);
      return;
    }

    session.starting = false;
    session.child = child;
    child.stderr?.on("data", (chunk) => {
      session.stderr = `${session.stderr}${chunk.toString()}`.slice(-6000);
    });
    child.once("error", (error) => this.finishSession(session, null, null, error));
    child.once("exit", (code, signal) => this.finishSession(session, code, signal, null));

    console.log(
      `[${new Date().toISOString()}] recording started: ${stream.login}; stream=${session.streamId}; part=${part}; file=${session.outputFile}`
    );
  }

  finishSession(session, code, signal, launchError) {
    if (session.completed) return;
    session.completed = true;
    if (this.active.get(session.login) === session) this.active.delete(session.login);

    if (launchError?.code === "ENOENT") {
      this.streamlinkUnavailable = true;
      console.error(
        `[${new Date().toISOString()}] recording disabled: Streamlink was not found at ${this.options.streamlinkPath}. Set recording.streamlinkPath to streamlink.exe or its full path.`
      );
      return;
    }

    const detail = launchError
      ? shortError(launchError)
      : `exit=${code ?? "unknown"}${signal ? `, signal=${signal}` : ""}${session.stderr ? `; ${session.stderr.replace(/\s+/g, " ").slice(-700)}` : ""}`;
    console.log(
      `[${new Date().toISOString()}] recording ended: ${session.login}; stream=${session.streamId}; part=${session.restartNumber + 1}; ${detail}`
    );

    if (this.stopped) return;
    const latest = this.observed.get(session.login);
    if (!latest?.isLive || latest.streamId !== session.streamId) return;
    this.scheduleRestart(session, detail);
  }

  scheduleRestart(session, detail) {
    const nextRestart = session.restartNumber + 1;
    if (nextRestart > this.options.maxRestarts) {
      this.exhaustedStreamIds.set(session.login, session.streamId);
      console.error(
        `[${new Date().toISOString()}] recording gave up for ${session.login}; stream=${session.streamId}; ${this.options.maxRestarts} reconnect attempt(s) exhausted. A new Twitch stream ID will be recorded normally. Last result: ${detail}`
      );
      return;
    }

    console.log(
      `[${new Date().toISOString()}] recording reconnect scheduled: ${session.login}; stream=${session.streamId}; attempt=${nextRestart}/${this.options.maxRestarts}; in ${this.options.restartDelaySeconds}s.`
    );
    const timer = setTimeout(() => {
      this.restartTimers.delete(session.login);
      const latest = this.observed.get(session.login);
      if (!this.stopped && latest?.isLive && latest.streamId === session.streamId) {
        void this.start({ login: session.login, streamId: session.streamId, isLive: true }, nextRestart).catch((error) => {
          console.error(`[${new Date().toISOString()}] recording restart failed for ${session.login}: ${shortError(error)}`);
        });
      }
    }, this.options.restartDelaySeconds * 1000);
    this.restartTimers.set(session.login, timer);
  }

  stopSession(session, reason) {
    if (session.completed) return;
    session.completed = true;
    if (this.active.get(session.login) === session) this.active.delete(session.login);
    if (session.child && session.child.exitCode === null) {
      try {
        session.child.kill();
      } catch (error) {
        console.error(`[${new Date().toISOString()}] could not stop recording for ${session.login}: ${shortError(error)}`);
      }
    }
    console.log(`[${new Date().toISOString()}] recording stopped: ${session.login}; ${reason}`);
  }

  stopAll() {
    this.stopped = true;
    for (const timer of this.restartTimers.values()) clearTimeout(timer);
    this.restartTimers.clear();
    for (const session of this.active.values()) this.stopSession(session, "Home Agent is stopping");
  }
}
