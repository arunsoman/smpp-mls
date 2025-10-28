# smpp-mls (SMPP Multi-Level Sender)

SMPP-MLS is a Spring Boot service that sends SMS messages over multiple SMPP connections with:

- Priority-aware throughput allocation (HIGH vs NORMAL)
- Per-operator routing and failover
- Automatic reconnect with backoff, health checks, and session control (start/stop)
- Real-time dashboard with throughput, per-operator trends, and session controls
- Tracking APIs to query messages by id, requestId, msisdn, etc.

## Quick Start

### Prerequisites
- Java 21
- Gradle (wrapper provided) or Maven (optional)

### Build and Run (Gradle)
```powershell
./gradlew bootRun
```

### Build and Run (Jar)
```powershell
./gradlew clean build
java -jar build\libs\smpp-mls-*.jar
```

Open the dashboard:
- http://localhost:8080/dashboard.html


## Configuration
Main config: `src/main/resources/application.yml` with properties bound by `SmppProperties`.

Key sections you will typically set:
- Operators and sessions (host, port, systemId, password)
- Default timers: `enquireLinkInterval`, `reconnectDelay`, `systemType`
- TPS per session and share reserved for high priority (`priority.high.max-tps-percentage` in `JsmppSessionManager`)

Example (illustrative):
```yaml
smpp:
  defaultConfig:
    enquireLinkInterval: 15000   # ms
    reconnectDelay: 3000         # ms
    systemType: ""
  operators:
    AWCC:
      host: 127.0.0.1
      port: 2775
      sessions:
        - systemId: awcc_client1
          password: secret
          tps: 50
        - systemId: awcc_client2
          password: secret
          tps: 20
```


## Priority Messaging

Requests use `priority` to steer token allocation between high-priority (HP) and normal-priority (NP) buckets per session.

Submit request schema (`SubmitRequest`):
```json
{
  "msisdn": "+9377xxxxxxx",
  "message": "Hello Afghan Gateway",
  "priority": "HIGH|NORMAL",
  "clientMsgId": "optional-client-id",
  "encoding": "GSM7|UCS2",
  "udh": "optional-hex-UDH"
}
```

Behavior:
- `priority = HIGH` requests are allowed up to `priority.high.max-tps-percentage` of a session TPS per second (defaults to 20%).
- Remaining TPS is used for NORMAL traffic.
- Sessions enforce TPS using per-session schedulers in `JsmppSessionManager` via `SessionSender`.

You can send a priority message with cURL:
```bash
curl -X POST http://localhost:8080/api/v1/sms/submit \
  -H "Content-Type: application/json" \
  -d '{
        "msisdn": "+93770000000",
        "message": "Urgent message",
        "priority": "HIGH",
        "clientMsgId": "order-123"
      }'
```


## Dashboard
- URL: `static/dashboard.html` served at http://localhost:8080/dashboard.html
- Shows throughput, status distribution, and per-operator trends (1h vs 5m):
  - Success Rate (%)
  - Retry Rate (%)
  - Submit Delay (ms) — created → submit_sm_resp per operator
  - DR Delay (ms) — submit_sm_resp → delivery receipt per operator
- Session cards with start/stop buttons and state badges:
  - States: STARTING, CONNECTED, RETRYING, STOPPING, STOPPED
  - Stop disables retries; Start re-enables retries and binds


## Session Control & States
Two managers implement `SmppSessionManager`:
- `JsmppSessionManager` — real SMPP via jSMPP with enquire_link and reconnect
- `SocketSmppSessionManager` — lightweight TCP health checker

Session states tracked internally: `STOPPED`, `STARTING`, `CONNECTED`, `RETRYING`, `STOPPING`.
- Manual stop sets state to STOPPED and disables further retries
- Failures place the session into RETRYING with exponential backoff
- Start sets state to STARTING and launches the bind/connect loop


## API Reference

### SMS Submission (`SmsController`)
- `POST /api/v1/sms/submit` — submit an SMS (preferred)
- `POST /api/sms/send` — alias of the above

Request body: see Priority Messaging section. Response (`SubmitResponse`) contains identifiers and status for the submission.


### Admin & Dashboard (`AdminDashboardController`)
Base path: `/api/admin`.

- `GET /api/admin/dashboard` — one-shot payload with overview, sessions, throughput, performance, recent activity, alerts
- `GET /api/admin/overview` — totals, status breakdown, active vs total sessions, messages today/last hour
- `GET /api/admin/sessions` — each session state + per-session counters
- `GET /api/admin/throughput` — per-minute counts (10m), current TPS, peak TPS, and per-operator sums
- `GET /api/admin/performance` — average submission delay, success rate, avg delivery time, retry rate (1h window; kept for backward compatibility)
- `GET /api/admin/performance/operators` — per-operator metrics (Last 1h vs Last 5m):
  - successRate1h, successRate5m
  - retryRate1h, retryRate5m
  - submitDelay1h, submitDelay5m (created → sent)
  - drDelay1h, drDelay5m (sent → DLR)
- `GET /api/admin/activity` — latest 50 outbound messages
- `GET /api/admin/alerts` — computed alerts (disconnected sessions, high queue depth, high retry rate)
- `POST /api/admin/session/{sessionId}/stop` — stop and disable retries for a session
- `POST /api/admin/session/{sessionId}/start` — start (or restart) a session and enable retries

SessionId format: `Operator:SystemId` (e.g., `AWCC:awcc_client1`).

Examples:
```bash
curl -X POST http://localhost:8080/api/admin/session/AWCC:awcc_client1/stop
curl -X POST http://localhost:8080/api/admin/session/AWCC:awcc_client1/start
```


### Message Tracking (`MessageTrackingController`)
Base path: `/api/track`.

- `GET /api/track/message/{id}` — by internal DB id
- `GET /api/track/request/{requestId}` — by requestId assigned at submission
- `GET /api/track/smsc/{smscMsgId}` — by SMSC message id
- `GET /api/track/phone/{msisdn}` — by phone number (+E.164 or digits)
- `GET /api/track/client/{clientMsgId}` — by client-provided message id

Example:
```bash
curl http://localhost:8080/api/track/request/req-123
```


## Data & Persistence
The service persists messages and DLRs into the database (H2 by default in dev). Core tables used by the APIs:
- `sms_outbound` — outbound messages, status, operator/session, timestamps, retry counters
- `sms_dlr` — delivery receipts (joined to `sms_outbound` by id)

The dashboard SQL queries operate on these tables to compute throughput and operator metrics.


## Development Notes
- Java 21, Spring Boot, Gradle wrapper included
- Threaded bind/connect loops with exponential backoff
- Enquire-link timers and transaction timeouts set in session initialization
- Frontend uses Chart.js for visualization and a lightweight toast system for UX


## Running Tests
Some Python helpers and test scripts exist under the repo root to validate routing and API behavior. You can also use cURL/Postman to exercise the endpoints.


## Security
- Consider adding authentication (API keys/JWT) via `ApiKeyInterceptor` or Spring Security for production use.
- Never expose SMPP credentials in public repos. Externalize via env vars or secret stores.


## License
MIT (or your chosen license). Update this section as appropriate.
