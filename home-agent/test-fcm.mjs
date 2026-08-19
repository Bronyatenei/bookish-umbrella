import fs from "node:fs/promises";
import path from "node:path";
import process from "node:process";
import { fileURLToPath } from "node:url";
import { initializeApp, cert, getApps } from "firebase-admin/app";
import { getMessaging } from "firebase-admin/messaging";

const __dirname = path.dirname(fileURLToPath(import.meta.url));
const channelArgument = process.argv[2]?.trim().toLowerCase();
const configPath = path.resolve(process.argv[3] ?? path.join(__dirname, "config.json"));

async function readJson(file) {
  return JSON.parse(await fs.readFile(file, "utf8"));
}

async function main() {
  const config = await readJson(configPath);
  if (!config.fcmToken || config.fcmToken.includes("PASTE_")) {
    throw new Error("Set config.fcmToken to the phone's current FCM registration token");
  }
  const fallbackChannel = Array.isArray(config.channels) ? config.channels[0] : "";
  const login = (channelArgument || fallbackChannel || "").trim().toLowerCase();
  if (!login) {
    throw new Error("Pass a Twitch login or add at least one login to config.channels");
  }

  const serviceAccountPath = path.resolve(path.dirname(configPath), config.firebaseServiceAccountPath);
  const serviceAccount = await readJson(serviceAccountPath);
  const firebaseApp = getApps()[0] ?? initializeApp({ credential: cert(serviceAccount) });
  const eventId = `manual-test:${login}:${Date.now()}`;
  const messageId = await getMessaging(firebaseApp).send({
    token: config.fcmToken,
    data: {
      type: "stream_online",
      event_id: eventId,
      login,
      display_name: login,
      title: "Тест Home Agent",
      game: "Проверка FCM",
      viewers: "1"
    },
    android: {
      priority: "high",
      ttl: 60 * 1000
    }
  });

  console.log(`Test FCM sent for ${login}. Firebase message ID: ${messageId}`);
  console.log("The phone should start the alarm if Home Agent mode is selected and this streamer's toggle is enabled.");
}

main().catch((error) => {
  console.error(error.message);
  process.exitCode = 1;
});
