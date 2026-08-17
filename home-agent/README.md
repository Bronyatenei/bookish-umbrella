# Twitch Alarm Home Agent

Home Agent is an optional Windows process that checks the configured Twitch channels and sends a high-priority, data-only Firebase Cloud Messaging event when a channel changes from offline to live. The Android application performs no Twitch polling in this mode, so the phone can remain idle while the home PC stays online.

## Prerequisites

Install Node.js 20 or newer on Windows. In Firebase Console, open **Project settings → Service accounts**, generate a new private key, and save the downloaded JSON as `home-agent/service-account.json` on the Windows machine. This file is a private credential and must never be committed or sent through chat.

The Android device must be registered in Firebase and must have an FCM registration token. The token can be printed temporarily from the Android app during development or exposed through a future settings screen. Put the current token in `config.json`; FCM tokens can rotate, so update the file if the app reports a new token.

## Installation

Copy `config.example.json` to `config.json`, replace `fcmToken`, and list the lowercase Twitch logins to monitor. Set `pollIntervalSeconds` to the desired interval; values below 15 seconds are automatically raised to 15 seconds.

From PowerShell, run:

```powershell
cd C:\path\to\bookish-umbrella\home-agent
Copy-Item config.example.json config.json
npm install
npm start
```

The first successful poll only records the current state. The agent sends an alarm only when a later check observes an offline-to-live transition. `state.json` is created next to `config.json` so a restart does not produce a false alarm for an already-live channel.

## Android setup

In the Android app, select **Настройки → Домашний агент — проверки на ПК**. The phone ignores local polling strategies in this mode. It accepts an event only when the strategy is Home Agent, the matching streamer is present and enabled, and the event is not a duplicate. Turning off a streamer's notification toggle prevents that event from starting the alarm.

The agent uses Twitch's public GraphQL web client identifier, matching the existing Android implementation; no Twitch OAuth token or Twitch two-factor authentication is required by this project. Treat this as an implementation dependency that Twitch may change, not as a guaranteed public API contract.

## Running automatically on Windows

For a simple always-on setup, create a Windows Task Scheduler task that starts `npm start` in the `home-agent` directory at user logon, with **Start in** set to that directory. The PC must remain powered on and connected to the internet. If the process stops, Task Scheduler can be configured to restart it.

## Security

Keep `service-account.json`, `config.json`, and `state.json` local. They are excluded by the repository ignore rules. Never publish the service-account private key, and rotate it in Google Cloud if it is exposed.
