# services/notification-service

## Purpose

Idempotent notification delivery service. Consumes `NotificationRequestEvent` and dispatches push notifications (FCM), SMS (Twilio), and email (SendGrid). Deduplicates on `eventId` — duplicate Kafka delivery never sends duplicate notifications.

## Error Handling

| Scenario | Response |
|----------|---------|
| Duplicate `eventId` | Skip silently |
| Provider transient error | Retry × 3 with backoff |
| Provider unavailable | Route to DLQ; alert fires |
| Template missing | Log error; send plain-text fallback |
