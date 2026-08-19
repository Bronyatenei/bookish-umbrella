# Twitch Alarm Home Agent

Home Agent is an optional Windows process that checks the configured Twitch channels and sends a high-priority, data-only Firebase Cloud Messaging event when a channel changes from offline to live. The Android application performs no Twitch polling in this mode, so the phone can remain idle while the home PC stays online.

## Prerequisites

Install Node.js 22 or newer on Windows. In Firebase Console, open **Project settings → Service accounts**, generate a new private key, and save the downloaded JSON as `home-agent/service-account.json` on the Windows machine. This file is a private credential and must never be committed or sent through chat.

The Android device must be registered in Firebase and must have an FCM registration token. In the app, open **Настройки**, choose the Home Agent strategy, then use **Скопировать FCM-токен**. Put the current token in `config.json`; FCM tokens can rotate, so update the file after reinstalling the app, clearing its data, or switching devices.

## Installation

Copy `config.example.json` to `config.json`, replace `fcmToken`, and list the lowercase Twitch logins to monitor. Set `pollIntervalSeconds` to the desired interval; values below 15 seconds are automatically raised to 15 seconds.

From PowerShell, run:

```powershell
cd C:\path\to\bookish-umbrella\home-agent
Copy-Item config.example.json config.json
npm install
npm start
```

The first successful poll only records the current state. The agent sends an alarm only when a later check observes an offline-to-live transition. `state.json` is created next to `config.json` so a restart does not produce a false alarm for an already-live channel. After a successful Twitch check, the agent also sends a lightweight Home Agent heartbeat no more than once every five minutes. The Android app uses this heartbeat only to detect that the PC is healthy; it never starts an alarm.

To optionally open a detected new stream in the Windows default browser, double-click `toggle-auto-open.vbs` in the agent folder. It toggles the local `auto-open.json` setting and takes effect on the next polling cycle without restarting the Agent. This option affects only browser opening; it does not change Twitch polling, FCM alarms, or heartbeats.

## Test FCM before waiting for a real stream

Set up the phone and `config.json` first, then run the following in the `home-agent` folder. Replace the login with an existing enabled streamer in the Android app:

```powershell
npm run test:fcm -- bellmarytank
```

This sends a real, high-priority test FCM event without querying Twitch. The Android app must be in **Домашний агент — проверки на ПК** mode for it to start the alarm.

For a full Russian setup guide, including Firebase, Windows Task Scheduler, security, test troubleshooting, and automatic startup, read [WINDOWS_SETUP_RU.md](WINDOWS_SETUP_RU.md).

## Android setup

In the Android app, select **Настройки → Домашний агент — проверки на ПК**. The phone ignores local polling strategies in this mode. It accepts an event only when the strategy is Home Agent, the matching streamer is present and enabled, and the event is not a duplicate. Turning off a streamer's notification toggle prevents that event from starting the alarm.

The expanded Home Agent settings are controlled entirely on the phone: choose the heartbeat-watch interval (5–25 minutes), the number of missed intervals before fallback, the fallback strategy (**Надёжный** or **Экономия**), and whether to return automatically after the PC recovers. For accurate short intervals on Android 12+, grant **Будильники и напоминания** through the app's **Разрешить точный контроль** button.

The agent uses Twitch's public GraphQL web client identifier, matching the existing Android implementation; no Twitch OAuth token or Twitch two-factor authentication is required by this project. Treat this as an implementation dependency that Twitch may change, not as a guaranteed public API contract.

## Running automatically on Windows

For a fully hidden always-on setup, use `run-home-agent-hidden.vbs` from a Windows Task Scheduler task at user logon; it starts the adjacent `start-home-agent.cmd`, which resolves and runs the adjacent `agent.mjs` by full path. The PC must remain powered on and connected to the internet. If the process stops, Task Scheduler can be configured to restart it.

## Security

Keep `service-account.json`, `config.json`, `auto-open.json`, and `state.json` local. They are excluded by the repository ignore rules. Never publish the service-account private key, and rotate it in Google Cloud if it is exposed.
