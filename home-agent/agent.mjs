import { spawn } from "node:child_process";
import fs from "node:fs/promises";
import path from "node:path";
import process from "node:process";
import { fileURLToPath } from "node:url";
import { initializeApp, cert, getApps } from "firebase-admin/app";
import { getMessaging } from "firebase-admin/messaging";

const __dirname = path.dirname(fileURLToPath(import.meta.url));
const configPath = path.resolve(process.argv[2] ?? path.join(__dirname, "config.json"));
const statePath = path.join(path.dirname(configPath), "state.json");
const autoOpenPath = path.join(path.dirname(configPath), "auto-open.json");
const twitchUrl = "https://gql.twitch.tv/gql";
const twitchClientId = "kimne78kx3ncx6brgo4mv6wki5h1ko";
const HEARTBEAT_INTERVAL_MS = 5 * 60 * 1000;
const HEARTBEAT_STATE_KEY = "__homeAgentHeartbeat";

async function readJson(file) {
  return JSON.parse(await fs.readFile(file, "utf8"));
}

async function readState() {
  try {
    return await readJson(statePath);
  } catch {
    return {};
  }
}

async function writeState(state) {
  const temporary = `${statePath}.tmp`;
  await fs.writeFile(temporary, `${JSON.stringify(state, null, 2)}\n`, "utf8");
  await fs.rename(temporary, statePath);
}

/** The optional local switch is read on every polling cycle, so changing it needs no restart. */
async function isAutoOpenEnabled() {
  try {
    return (await readJson(autoOpenPath)).openOnLive === true;
  } catch {
    return false;
  }
}

/** Opens the system-default browser without waiting for it or affecting FCM delivery. */
function openStreamInBrowser(stream) {
  const streamUrl = `https://www.twitch.tv/${encodeURIComponent(stream.login)}`;
  const command = `start "" "${streamUrl}"`;
  const launcher = spawn("cmd.exe", ["/d", "/s", "/c", command], {
    detached: true,
    stdio: "ignore",
    windowsHide: true
  });
  launcher.on("error", (error) => {
    console.error(`[${new Date().toISOString()}] browser open failed for ${stream.login}: ${describeError(error)}`);
  });
  launcher.unref();
  console.log(`[${new Date().toISOString()}] browser opened: ${streamUrl}`);
}

function makeQuery(logins) {
  const aliases = logins.map((login, index) => `u${index}: user(login: ${JSON.stringify(login)}) { login displayName stream { id title viewersCount game { name } } }`);
  return `{ ${aliases.join(" ")} }`;
}

async function checkStreams(logins) {
  const response = await fetch(twitchUrl, {
    method: "POST",
    headers: {
      "Client-ID": twitchClientId,
      "Content-Type": "application/json"
    },
    body: JSON.stringify({ query: makeQuery(logins) })
  });
  if (!response.ok) throw new Error(`Twitch HTTP ${response.status}`);
  const body = await response.json();
  if (body.errors?.length) throw new Error(`Twitch GraphQL: ${JSON.stringify(body.errors)}`);
  const data = body.data ?? {};

  return logins.map((login, index) => {
    const user = data[`u${index}`];
    const stream = user?.stream;
    return {
      login,
      displayName: user?.displayName || login,
      isLive: Boolean(stream),
      title: stream?.title || "",
      viewers: Number(stream?.viewersCount || 0),
      game: stream?.game?.name || "",
      streamId: stream?.id || ""
    };
  });
}

function describeError(error) {
  const parts = [`${error?.name || "Error"}: ${error?.message || String(error)}`];
  const cause = error?.cause;
  if (cause) {
    const causeDetails = [cause.code, cause.message].filter(Boolean).join(": ");
    if (causeDetails) parts.push(`cause: ${causeDetails}`);
  }
  return parts.join("; ");
}

async function sendHeartbeat(messaging, token) {
  const sentAt = Date.now();
  await messaging.send({
    token,
    data: {
      type: "home_agent_heartbeat",
      sent_at: String(sentAt)
    },
    android: {
      priority: "high",
      // A delayed heartbeat must expire rather than make an unavailable PC look healthy.
      ttl: 2 * 60 * 1000
    }
  });
  console.log(`[${new Date(sentAt).toISOString()}] heartbeat sent`);
  return sentAt;
}

async function sendAlarm(messaging, token, stream) {
  const eventId = `${stream.login}:${stream.streamId || Date.now()}`;
  await messaging.send({
    token,
    data: {
      type: "stream_online",
      event_id: eventId,
      login: stream.login,
      display_name: stream.displayName,
      title: stream.title,
      game: stream.game,
      viewers: String(stream.viewers)
    },
    android: {
      priority: "high",
      ttl: 60 * 1000
    }
  });
  console.log(`[${new Date().toISOString()}] FCM sent: ${stream.login}`);
}

async function main() {
  const config = await readJson(configPath);
  if (!Array.isArray(config.channels) || config.channels.length === 0) throw new Error("config.channels must contain at least one Twitch login");
  if (!config.fcmToken || config.fcmToken.includes("PASTE_")) throw new Error("Set config.fcmToken to the phone's current FCM registration token");

  const serviceAccountPath = path.resolve(path.dirname(configPath), config.firebaseServiceAccountPath);
  const serviceAccount = await readJson(serviceAccountPath);
  const firebaseApp = getApps()[0] ?? initializeApp({ credential: cert(serviceAccount) });
  const messaging = getMessaging(firebaseApp);
  const state = await readState();
  const intervalMs = Math.max(15, Number(config.pollIntervalSeconds || 60)) * 1000;

  console.log(`Home Agent started; checking ${config.channels.length} channel(s) every ${intervalMs / 1000}s.`);
  console.log("The first successful poll only initializes state; alarms are sent on offline -> online transitions.");
  console.log("A health heartbeat is sent after successful checks at most once every 5 minutes.");

  let stopping = false;
  const stop = () => { stopping = true; };
  process.on("SIGINT", stop);
  process.on("SIGTERM", stop);

  while (!stopping) {
    try {
      const results = await checkStreams(config.channels.map((x) => String(x).trim().toLowerCase()).filter(Boolean));
      const autoOpenEnabled = await isAutoOpenEnabled();
      for (const stream of results) {
        const previous = state[stream.login];
        if (previous && !previous.isLive && stream.isLive) {
          if (autoOpenEnabled) openStreamInBrowser(stream);
          await sendAlarm(messaging, config.fcmToken, stream);
        }
        state[stream.login] = { isLive: stream.isLive, streamId: stream.streamId, checkedAt: new Date().toISOString() };
      }
      await writeState(state);

      const lastHeartbeatAt = Number(state[HEARTBEAT_STATE_KEY]?.sentAt || 0);
      let heartbeatLog;
      if (Date.now() - lastHeartbeatAt >= HEARTBEAT_INTERVAL_MS) {
        const sentAt = await sendHeartbeat(messaging, config.fcmToken);
        state[HEARTBEAT_STATE_KEY] = { sentAt };
        await writeState(state);
        heartbeatLog = "heartbeat=sent";
      } else {
        const remainingSeconds = Math.max(0, Math.ceil((HEARTBEAT_INTERVAL_MS - (Date.now() - lastHeartbeatAt)) / 1000));
        heartbeatLog = `heartbeat=next in ${Math.ceil(remainingSeconds / 60)}m`;
      }

      console.log(`[${new Date().toISOString()}] checked: ${results.map((x) => `${x.login}=${x.isLive ? "LIVE" : "offline"}`).join(", ")}; ${heartbeatLog}`);
    } catch (error) {
      console.error(`[${new Date().toISOString()}] check failed: ${describeError(error)}`);
    }
    await new Promise((resolve) => setTimeout(resolve, intervalMs));
  }
  console.log("Home Agent stopped.");
}

main().catch((error) => {
  console.error(error.message);
  process.exitCode = 1;
});
