# SYSU-GO release test plan

2026-10-08. ECC Android architecture, TDD, code/security review and verification loop. This plan is authorized local work; no live environment writes or publication. Public readiness is blocked until tracker conditions are met, not until an arbitrary test count is reached.

## Ordered execution

1. Phase 0 baseline: reproduce debug/release compilation and full lint; portable signing/JDK; source-driven Kotlin/backend tests and safe demo variant. Use Gradle with installed JDK; backend node --test. Existing Android test copied queue sorting is not proof of shipped FIFO code.
2. Phase 1 exploit tests first: G-05/06 Auth/admin; rules escalation/cross-user access; QR mandatory Auth/atomic replay; dispatch/trip IDOR and state races; socket anonymous/foreign/revocation; private SAP and reachable route inventory. Every fix preserves valid own-user/staff paths and records executed RED/GREEN checkpoint before implementation.
3. Phase 2 Android business unit tests: production FIFO/status/date/identifier functions, retry and error classification, current-user state ownership, multiple dispatches/OBDs. Add meaningful tests at reusable seams; avoid broad architecture rewrites solely for testability. Measure changed logic coverage, not test fixture coverage.
4. Isolated integration: real Auth/Firestore/Storage emulators with guarded demo project and loopback endpoints; actual HTTP and Socket.IO, fault injection and concurrent callers. No service-account loading or remote fallback. Prove transaction rollback, no duplicate queue/assignment and private recipient isolation.
5. Device instrumentation: emulator/physical devices for min-supported and modern Android; login/approval/profile disable, QR camera, permissions denied/revoked, GPS accuracy/background/foreground notification, POD/signature capture, rotation/process death, logout/account switch, offline/reconnect. USB/device availability is required; adb currently shows no device.
6. Cross-app staging: web generation/driver registration, FIFO staff assignment with repeated OBD/item rows, driver-owned progress/POD, web display, cancellation/reassignment/history and revoked-role/session denial. Validate canonical UID != queue document ID. Both socket endpoints must satisfy same private session policy.
7. Release gate: clean fixed commit and signed artifact, full release lint, dependency/security review, real staged install/update, realistic measured load/soak, observability, deployed rules/indexes/Storage, restore/rollback and release-owner acceptance. Record required platform/publishing rules from current official sources when planning actual distribution.

## Acceptance matrix

| Area | Positive case | Adversarial/failure case | Required evidence |
|---|---|---|---|
| Identity | Active approved driver/staff uses owned endpoints | Revoked/disabled/pending/deleted/missing profile and Auth outage | Unit + real Auth + HTTP/socket |
| Roles | Administration through sysu-app | Self promotion/deletion of unrelated user; legacy duplicate endpoints | HTTP + rules; no mutation |
| QR/FIFO | Valid own scan and truthful truck-group position | Foreign UID, malformed/expired token, repeated/concurrent scan, mid-write failure | Actual controller/client + real transactions |
| Trip/dispatch | Own expected transition | Foreign queue/assignment, completed/stale generation, malicious field types, concurrent edit | HTTP + database final state |
| GPS/session | Authorized active assignment and fresh location | Unrelated socket, zero coordinates, stale/out-of-order data, expired session, process death | Real sockets + device |
| POD/signature | Correct driver/OBD and image displayed on web | Other-driver read/write, oversized/malformed photo, cancellation, permission loss | Rules/Storage + device + web |
| Recovery | Own queue/trip restored after reconnect | Account swap, server restart, partial outage, stale pending write | Integration/device/staging |
| Performance | Documented realistic peak/data size | Soak, burst, slow SAP/Auth/DB and resource exhaustion | Measured agreed latency/error/memory/cost budgets |

## Baseline commands and constraints

Executed Gradle baseline: gradlew.bat --no-daemon --console=plain testDebugUnitTest lintDebug assembleDebug assembleRelease. Supply JAVA_HOME for the installed JDK; do not commit developer-specific paths. Local build reports under app/build/reports; test XML under app/build/test-results.

Backend: npm --prefix backend test -- --experimental-test-coverage. Tests mock Firebase while HTTP binds loopback; real Firebase integration remains separate. Initial test runner's wildcard failed on Windows and was replaced by native node --test discovery. Whole-app 80% coverage is not established.

No test may default to production Firebase/SAP/API/socket. Until G-04 isolation exists, do not install/launch the current production-targeting APK as an automated integration test. Compilation/lint use Firebase build configuration but do not authenticate or mutate Firestore. Emulator pass alone cannot prove deployed indexes, Storage or production readiness.

## Credential containment gate (G-24)

Private-key export was tracked and exposed. Local index removal and Docker context restrictions are complete; IAM key deletion/rotation and exposure/history review require the deployment owner. Never print parser exceptions or credential source; inspect text markers and report file paths/booleans only. Do not force-push rewritten history without repository-owner coordination. Key invalidation and replacement deployment are required before release; history cleanup alone is insufficient.

## Executed QR checkpoint (2026-10-08)

G-08/G-09/G-10: 42 backend unit/HTTP/packaging, 18 real Firestore QR + 4 trip, and 19 shipped Kotlin QR transport + 2 basic Android cases pass (85 separate-run cases). Full debug/release build and lint pass; QrHttpClient scoped line/branch coverage 100%/92.73%, QR controller real integration 100%/91.36%. No device/staged acceptance claim. Next local critical targets: authoritative rules and owned Android writes (G-07/G-16), dispatch ownership/state (G-11), socket authentication/recipient privacy (G-13), SAP/diagnostic exposure (G-14), safe isolated variants (G-04). Real Auth plus combined QR HTTP, index deployment, timeout-refresh UI and cross-app end-to-end remain required. G-24 still requires IAM key invalidation and deployment replacement evidence before public release.
