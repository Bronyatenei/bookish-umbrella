import assert from "node:assert/strict";
import test from "node:test";
import { createRecordingOptions } from "./stream-recorder.mjs";

const configDirectory = "/agent";
const monitored = ["bellmarytank", "tyuuba"];

test("recording is disabled without a recordChannels list", () => {
  assert.equal(createRecordingOptions({}, configDirectory, monitored), null);
});

test("recording options receive stable defaults and deduplicate logins", () => {
  const options = createRecordingOptions({
    recordChannels: ["BellMaryTank", "bellmarytank", "tyuuba"],
    recording: { outputDirectory: "./captures" }
  }, configDirectory, monitored);

  assert.deepEqual([...options.recordLogins], ["bellmarytank", "tyuuba"]);
  assert.equal(options.outputDirectory, "/agent/captures");
  assert.equal(options.quality, "best");
  assert.equal(options.maxRestarts, 5);
  assert.equal(options.restartDelaySeconds, 20);
  assert.equal(options.streamSegmentAttempts, 5);
  assert.equal(options.playlistReloadAttempts, 5);
});

test("recording channels must be monitored by Home Agent", () => {
  assert.throws(
    () => createRecordingOptions({ recordChannels: ["unwatched"] }, configDirectory, monitored),
    /must also exist in config\.channels: unwatched/
  );
});

test("recording retry settings are kept within safe bounds", () => {
  const options = createRecordingOptions({
    recordChannels: ["tyuuba"],
    recording: {
      maxRestarts: 999,
      restartDelaySeconds: 0,
      streamSegmentAttempts: 0,
      playlistReloadAttempts: 999,
      streamTimeoutSeconds: 2
    }
  }, configDirectory, monitored);

  assert.equal(options.maxRestarts, 12);
  assert.equal(options.restartDelaySeconds, 5);
  assert.equal(options.streamSegmentAttempts, 1);
  assert.equal(options.playlistReloadAttempts, 10);
  assert.equal(options.streamTimeoutSeconds, 10);
});
