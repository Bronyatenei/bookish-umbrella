# FCM delivery notes for Twitch Alarm

## Source findings

- High-priority Android FCM is intended for time-sensitive, user-visible content; repeated high-priority messages that do not result in a visible notification can be deprioritized.
- `onMessageReceived` has only a short processing window. The payload should be handled immediately; long work should use a lifecycle-extending mechanism.
- FCM acceptance by the server does not prove delivery to the Android SDK. Delivery reports, labeled data messages and BigQuery export can distinguish acceptance from delivery.
- FCM collapsible messages retain only the newest queued state while a device is unavailable.

## Product decision

`stream_online` remains a direct high-priority event for low latency. In addition, the Windows Agent keeps a bounded pending stream-alert envelope and includes it in subsequent heartbeat v2 packets until expiry. This creates a second transport attempt through the channel that is demonstrably reaching the device, without adding a persistent Android service or local Twitch polling.

## Sources

1. https://firebase.google.com/docs/cloud-messaging/android-message-priority
2. https://firebase.google.com/docs/cloud-messaging/understand-delivery
3. https://firebase.blog/posts/2025/04/fcm-on-android/
