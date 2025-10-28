# SMPP Client Implementation Backlog

**Project:** Afghan SMPP Gateway with Operator-Based Routing & Dual Priority Messaging

**Date:** October 28, 2025

---

## How to use this backlog
- Estimates are shown as "ideal dev days" (1 developer, full day). Convert to story points/sprints based on your team's velocity.
- Acceptance criteria use Given/When/Then for testability.
- Dependencies and risks are listed to help plan sequencing.

---

## Prioritized Backlog (Epics → Stories)

### Epic A — Core SMPP Session Infrastructure (highest priority)

1. **A1 — Project skeleton & infra**
   - Estimate: 1 day
   - Description: Create Maven/Gradle Spring Boot 3.3+ project using Java 21, modules: rest-api, core, smpp-client, persistence. Add CI skeleton and README.
   - Acceptance:
     - Given repo is created, When I build, Then build succeeds and app starts with default profile.

2. **A2 — Externalized config & secrets integration**
   - Estimate: 0.5 day
   - Description: Load `application.yml`, support env overrides and secrets via env vars; secure sample placeholder for operator credentials.
   - Acceptance:
     - Given `application.yml` and env vars, When app starts, Then operator sessions config is available in the Spring Environment without plaintext creds committed.

3. **A3 — SMPP session manager basic (connect & bind)**
   - Estimate: 3 days
   - Description: Implement session manager using CloudHopper: create configurable sessions per operator, support `bind_transceiver`, automatic reconnect on failure, expose session lifecycle events.
   - Acceptance:
     - Given operator config with N sessions, When app starts, Then N sessions attempt to bind and session health is reported (bound/unbound) in logs and metrics.

4. **A4 — Enquire_link & health monitoring + graceful startup/shutdown**
   - Estimate: 2 days
   - Description: Implement `enquire_link` scheduling, auto-reconnect with backoff, graceful shutdown that drains/pauses sessions and persists queued NP messages.
   - Acceptance:
     - Given session bound, When network broken, Then manager logs reconnect attempts and marks session unhealthy; on shutdown, NP messages are persisted and resumed on restart.

---

### Epic B — Routing & Prefix Management

5. **B1 — MSISDN normalization utility**
   - Estimate: 1 day
   - Description: Implement normalization to E.164 from common inputs (leading 0, +, no country code).
   - Acceptance:
     - Given various msisdn inputs, When normalized, Then output is canonical E.164 and unit tests cover edge cases.

6. **B2 — Prefix mapping table + hot-reload**
   - Estimate: 1.5 days
   - Description: Implement prefix→operator mapping config with hot-reload (file-watch or Spring Cloud Config) and validation.
   - Acceptance:
     - Given updated prefixes file, When change applied, Then new mapping applies without restart and routing logs changes.

7. **B3 — Operator resolver and fallback policy**
   - Estimate: 1 day
   - Description: Route msisdn to operator by prefix; support fallback operator behavior and policy settings (fail-fast vs queue vs cross-operator disabled).
   - Acceptance:
     - Given primary operator down, When fallback enabled, Then messages route to configured fallback according to policy; logs show route decision.

---

### Epic C — Dual Priority Queuing & Throttling

8. **C1 — Per-session dual queues (HP / NP)**
   - Estimate: 2 days
   - Description: Implement two logical queues per session with bounded NP queue and controlled HP handling.
   - Acceptance:
     - Given HP and NP messages enqueued, When session available, Then HP dequeues first and NP only when HP empty.

9. **C2 — HP bypass & HP token bucket**
   - Estimate: 1.5 days
   - Description: Add HP TPS limiting using token bucket per-session (or per-operator) with configurable max percentage.
   - Acceptance:
     - Given HP traffic above configured limit, When tokens exhausted, Then HP submissions are throttled (reject or queued) and metrics exposed for throttles.

10. **C3 — Sequence number policy for HP (optional)**
    - Estimate: 2 days
    - Description: Investigate and implement optional separate sequence-number allocation for HP messages if required by operator; warn on SMPP semantics.
    - Acceptance:
      - Given operator config enabled, When submitting HP PDUs, Then sequence numbers are allocated per policy and SMPP PDUs remain valid.

---

### Epic D — Submission API & Idempotency

11. **D1 — REST submit endpoint**
    - Estimate: 1.5 days
    - Description: Implement `POST /api/v1/sms/submit` with request validation, auth stub, returns requestId and queued status.
    - Acceptance:
      - Given valid request, When POSTed, Then response contains requestId, status=QUEUED and assigned operator/session fields; invalid inputs return descriptive errors.

12. **D2 — Idempotency & duplicate detection**
    - Estimate: 1 day
    - Description: Use `clientMsgId` as idempotency key; deduplicate within retention window.
    - Acceptance:
      - Given duplicate request with same clientMsgId, When within window, Then system returns existing record and does not enqueue again.

13. **D3 — Message encoding support & splitting**
    - Estimate: 2 days
    - Description: Implement encoding support (GSM7, UCS2, BINARY) and concatenation (UDH) rules for multipart messages.
    - Acceptance:
      - Given a long message, When encoding chosen, Then message is split into concatenated parts with UDH and submission metadata reflects parts count.

---

### Epic E — Persistence, Retries & Replay

14. **E1 — Persistence schema & repository**
    - Estimate: 1 day
    - Description: Add `sms_outbound` and `sms_dlr` tables and repository layer (Spring Data JPA).
    - Acceptance:
      - Given a message submission, When persisted, Then row exists with status QUEUED and indexes created.

15. **E2 — Retry/exponential backoff & retry queue**
    - Estimate: 1.5 days
    - Description: Implement retry policy with configurable retries, backoff, and jitter; persist `retry_count` and `next_retry_at`.
    - Acceptance:
      - Given transient failure to submit, When retry schedule triggers, Then message reattempts until retries exhausted and status updated.

16. **E3 — Restart replay (NP persistence handling)**
    - Estimate: 1 day
    - Description: On restart, requeue persisted NP messages (safe ordering) without duplicating those marked sent.
    - Acceptance:
      - Given app restart, When persisted messages exist, Then they are re-enqueued and not re-sent if status=DELIVERED or SENT.

---

### Epic F — DLR Handling & Delivery Processing

17. **F1 — Deliver_SM parsing & correlation**
    - Estimate: 2 days
    - Description: Parse `deliver_sm` DLRs, map to `sms_outbound` via `smsc_msg_id` or `client_msg_id` fallback.
    - Acceptance:
      - Given incoming DLR with `smsc_msg_id`, When processed, Then `sms_outbound.status` is updated and `sms_dlr` row inserted.

18. **F2 — DLR webhook and Kafka sink**
    - Estimate: 2 days
    - Description: Implement webhook delivery with retry and optional Kafka publishing (configurable).
    - Acceptance:
      - Given DLR processed, When webhook target is configured, Then callback is sent and retried on failure up to N times; Kafka publish metrics recorded.

19. **F3 — Late/duplicate DLR handling**
    - Estimate: 1 day
    - Description: Deduplicate DLRs and handle late DLRs without corrupting state.
    - Acceptance:
      - Given duplicate or late DLR, When received, Then state remains consistent and an audit record kept.

---

### Epic G — Observability & Monitoring

20. **G1 — Prometheus metrics & Micrometer**
    - Estimate: 1 day
    - Description: Expose metrics (TPS, queue size, session health, reconnects, throttled counts).
    - Acceptance:
      - Given app running, When metrics are scraped, Then metrics names exist and reflect runtime state.

21. **G2 — Structured logging + masking PII**
    - Estimate: 0.5 day
    - Description: JSON logs with fields operator, sessionId, messageId; msisdn masked in logs.
    - Acceptance:
      - Given a log event, When emitted, Then it is JSON and msisdn is masked.

---

### Epic H — Security, API Governance & Rate Limits

22. **H1 — API auth & per-client rate limiting**
    - Estimate: 2 days
    - Description: Implement API key auth (or OAuth2 stub) and per-key rate limits and quotas.
    - Acceptance:
      - Given client with quota exceeded, When submits, Then API returns RATE_LIMITED and metrics incremented.

23. **H2 — Secrets rotation & storage guidance**
    - Estimate: 0.5 day (doc)
    - Description: Add runbook/docs for rotating SMPP credentials and required steps to rebind safely.
    - Acceptance:
      - Given rotation doc, When followed, Then sessions rebind without losing queued NP messages.

---

### Epic I — Testing, Load & Chaos

24. **I1 — Unit tests for routing & queue behavior**
    - Estimate: 1 day
    - Description: Add unit tests for prefix matching, msisdn normalization, HP/NP queue semantics.
    - Acceptance:
      - Given test suite run, When executed in CI, Then unit tests pass.

25. **I2 — Integration tests with SMPP simulator**
    - Estimate: 3 days
    - Description: Integrate SMPPSim-based tests for bind, `submit_sm` and DLR parse. Provide CI job.
    - Acceptance:
      - Given SMPPSim in CI environment, When tests run, Then end-to-end bind/submit/dlr flow passes.

26. **I3 — Load test harness and runbook (1000 TPS)**
    - Estimate: 2 days
    - Description: Create Gatling or k6 scripts and a runbook to validate 1000 TPS using multiple sessions; document tuning knobs.
    - Acceptance:
      - Given test harness, When executed against local cluster or test env, Then system sustains 1000 TPS aggregated and HP median latency < 200ms.

---

### Epic J — Deployment & HA

27. **J1 — Dockerfile, readiness/liveness, Kubernetes manifests**
    - Estimate: 1.5 days
    - Description: Containerize app, add health checks that read session health and mount secrets; add sample K8s manifests with HPA suggestions.
    - Acceptance:
      - Given container image, When deployed with K8s manifests, Then readiness and liveness probes function and secrets are consumed via env.

28. **J2 — Multi-instance coordination / partitioning plan**
    - Estimate: 2 days (design + small implementation)
    - Description: Design partitioning by prefix to avoid duplicate sends across instances (or propose central broker). Implement simple prefix-hash routing in config.
    - Acceptance:
      - Given multiple instances, When configured with partition mapping, Then each instance only handles assigned prefixes and duplicates do not occur.

---

### Epic K — Operational & Compliance (docs)

29. **K1 — Runbook & escalation procedures**
    - Estimate: 1 day
    - Description: Write runbooks for session flapping, credential rotation, and failover.
    - Acceptance:
      - Given runbook, When operator follows steps, Then sessions recover or escalate per instructions.

30. **K2 — Data retention & PII policy document**
    - Estimate: 0.5 day
    - Description: Document retention periods, masking, and encryption at rest requirements.
    - Acceptance:
      - Document present and approved by stakeholders.

---

## Dependencies & Sequencing (short)
- Epic A must be started first (project skeleton & session manager).  
- Prefix mapping (B2) and normalization (B1) required before API routing (D1).  
- Persistence (E1) should be available before final queuing/replay and graceful shutdown (A4, E3).  
- DLR handling depends on session manager and persistence availability.

---

## Suggested Sprint Plan (2-week sprints)
- **Sprint 1 (2 weeks):** A1, A2, A3, B1, D1, E1 — core bootstrapping and REST API
- **Sprint 2:** C1, C2, B2, B3, D2, D3 — routing + priority behavior + idempotency
- **Sprint 3:** E2, E3, F1, F3, I1 — retries, persistence replay, DLR parsing, unit tests
- **Sprint 4:** F2, G1, G2, H1 — webhooks/Kafka, monitoring, logging, API auth
- **Sprint 5:** I2, I3, J1, J2, K1 — integration tests, load tests, containerization, runbooks

---

## Acceptance Test Bundle (for release)
- **E2/E3 + C1/C2:** simulate HP burst and confirm HP ratio enforcement and no NP starvation beyond threshold.
- **F1/F2:** end-to-end submit → `smsc_msg_id` returned → DLR arrives → webhook delivered.
- **A3/A4:** kill session, ensure reconnect and persisted NP messages re-queued after restart.
- **I3:** achieve aggregate 1000 TPS across configured sessions with HP latency <200ms.

---

## Risks / Mitigations (concise)
- Multi-instance duplicate sends — mitigate via partitioning by prefix or centralized broker.
- Cross-operator fallback violating commercial agreements — enforce policy configuration to disable cross-operator fallback by default.
- HP starvation or NP starvation — enforce token buckets and backpressure with clear error codes.

---

## Assumptions
- Operators accept SMPP v3.4 `bind_transceiver` and CloudHopper is acceptable library.
- SMPP credentials are managed via external secrets (not stored in repo).
- Country code is +93 for Afghanistan; msisdn normalization will target E.164.

---

## Next steps (you can ask me to do any of these)
- Convert backlog to CSV or Jira-importable format.
- Start Sprint 1 by generating the Spring Boot project skeleton and implementing Story A1–A3.
- Generate sample `application.yml`, Dockerfile, and README for Sprint 1.

---

*Generated by the AI Systems Architect — backlog exported as `implementation_backlog.md`.*
