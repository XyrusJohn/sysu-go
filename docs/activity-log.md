# Activity log

## 2026-10-14 â€” Blank company trip-registration recovery

Affected: Android registration/profile/home/session and GO trip controller. Preserved registration's company across profile reads, added editable company validation before QR admission, recovered ambiguous owned QR admission by token, and persisted the confirmed company in the driver profile. Verification: backend 148/148; Android unit 81/81; native workflow 16/16; liveTest build, signature and ZIP alignment pass. Controlled beta deployment `64aea5f6-beec-4a75-a984-6a8c616ba2b1` SUCCESS; health 200 and anonymous trip registration 401. Pending: install the new APK, confirm phone-to-web registration, and resolve the three static RELRO release checks.

## 2026-10-14 â€” Registration confirmation recovery

Fixed ambiguous phone registration responses and provisional queue display. The backend accepts only an identical committed registration retry without writing again; the app checks the server queue state before showing a failure after transport or server errors, and shows pending registration until confirmed. Deployed backend runtime to existing controlled beta as `0aeeed39-a40d-4d7e-ab10-448b83b15b28` SUCCESS; health 200, private unauthenticated route 401. Verified backend 144/144, Android unit 81/81, native Android 15 workflow 15/15, liveTest APK build and ZIP alignment. Physical phone acceptance and three static RELRO failures remain open. No Firestore rules, data migration, production secrets, infrastructure settings, commit or push.

## 2026-10-14 â€” Controlled beta matching backend live

User requested deployment after the remaining Android compatibility failures were explained. Runtime-only private-rank controller/route changes deployed to the existing service: `5c2b152a-714d-46a8-9b33-6f6a3be41afd` SUCCESS. Production health/queue/CORS/authenticated sockets and six availability observations pass; new endpoint denies anonymous access with 401 and staff with 403. Exact old runtime retained for rollback. Matching signed APK is ready for user installation; three static RELRO failures and broader release/device gates remain open. No operational trip/GPS/permission writes or rule/secret/infrastructure edits; no commit/push. [Evidence](../../sysu-app/docs/controlled-beta-deployment-2026-10-14.md).

## 2026-10-14 â€” Native queue/tracking and artifact verification

Added native workflow and real Kotlin-to-web automation using existing tooling. Fixed cached shipment snapshots mutating authoritative tracking state, replaced cross-driver queue reads with authenticated own-rank polling, disabled app backup, and aligned liveTest resources with release network policy. Applied user-approved CameraX 1.6.2 / ML Kit 17.3.0. Native UI/camera 16/16, strict 16 KB camera/QR smoke 2/2, paired native-to-web 4/4, browser regression 16/16, backend 140/140, debug/release units 79/79 each and mirrored local Firestore contracts 29/29 per policy pass. Local policy changes are undeployed; broader guard/POD/signing/IAM/device gates remain. G-30 retains three static RELRO failures despite passing runtime smoke, signature and ZIP/load alignment. [Final evidence](../../sysu-app/docs/automated-release-validation.md). No production writes, commit or push.

## 2026-10-09 â€” Dynamic registered queue positions (G-28)

Aligned Home and Queue Details with registered/unassigned waiting/called web eligibility and timestamp/document-ID order. Details now listens for peer changes; waiting admissions cannot load historical assignments over the position. Empty/ineligible entries have no invented number; listeners are cleaned up and guarded against queue/status changes.

Verification: legacy predicate 3/8 passed / 5 failed; final debug/release 138/138 executions, web backend 149/149, mobile backend 119/119 on required Node 24, APK build/lint/signature checks pass. An unsupported system Node 20 run had one cancelled case before the successful Node 24 rerun. Updated production-configured APK is built; no phone/AVD available, so installation and live numbering acceptance remain pending. [Evidence](queue-position-recovery-2026-10-09.md). No database changes, commit or push.

## 2026-10-09 â€” Queue confirmation authentication and completion

Fixed bounded Firebase token refresh after queue HTTP 401 and connected queue admission to trip registration. Removed confirmation success on registration errors and preserved admitted queue identity for retries and recreation. Affected Activity, QR transport/service, new confirmation flow, and regression tests; unrelated user edits preserved.

Verification: new QR regression executed RED before recovery fix; final Android 53/53, backend authorization/ownership 39/39, debug APK build, QR transport 100% lines / 92.31% branches, whitespace check, and code/security review passed. No device connected. Exact production 401 cause and Android-to-web confirmation remain unverified; long combined helper names and lost registration responses need follow-up. See [TDD evidence](queue-confirmation-tdd.md). No commit, push, deploy, or install.

Production-configured `liveTest` beta APK also built successfully with its required lint check; generated configuration confirms production API and Firebase emulators disabled. Artifact identity is recorded in the TDD evidence.

## 2026-10-09: tracking regression and production compatibility

One mobile sender and guarded checkpoints; shared web ordering/freshness and marker reuse; backend timestamp/replay/closure and delayed-disconnect protections. Real browser tests found and fixed first-connect token-refresh and offline recovery defects. Approved existing super-admin profile activation verified without changing its role. Both tested backends deployed; current APK built/signed. Automated evidence and remaining actual-phone gates are owned by the [tracking plan](../../sysu-app/docs/live-location-optimization-plan.md#implementation-checkpoint--2026-10-09). No commit/push or rules/dependency changes.

## 2026-10-14 â€” Registration company persistence and provisional queue display

Confirmed company was only retained on-device during account registration and erased by pending login session clearing. Added persistent user company, bounded local rule support, login fallback, legacy driver alias reading, explicit unconfirmed queue display, and pre-admission API length validation. Backend registration tests passed 18/18; focused Android compilation/tests and whitespace check passed. New rules regression remains unexecuted without the Firestore emulator. Production rule reconciliation/deployment and device acceptance remain pending; no migration or production changes made. See [review and release requirements](registration-company-review.md).



## 2026-10-15 ? Second-brain scan follow-up

Corrected README prerequisite to Node 24.x and documented required Firebase environment isolation and safe local emulator configuration. Documentation-only change; configuration checked against backend/package.json, backend/.env.example and backend/config/runtimeConfig.js.


## 2026-10-15 â€” Documentation consolidation verification

Verified that the root README routes readers to the current Android/backend guides, the docs index distinguishes active guidance from archived records, and former app/backend and integration documents are preserved under docs/archive/. All 81 Markdown files have resolvable relative links. No app code changed and no tests or build were run for this documentation review.

## 2026-10-23 -- realtime loading fallback

Realtime subscriptions now fail their initial load after a bounded 20-second wait. My Trip, Assignments, and Queue Details then query Firestore's persisted local cache, keep cached records visible, or replace the skeleton with a tap-to-retry error when no cached data exists. My Trip also renders realtime assignment data if secondary driver/queue enrichment exceeds 15 seconds. Android emulator unit tests passed 95/95 and `assembleEmulator` passed. Physical-device/instrumentation testing was not performed. Audit also confirmed POD photo writes are not durably queued for offline retry; do not treat POD capture as offline-complete until that separate recovery path is implemented.
