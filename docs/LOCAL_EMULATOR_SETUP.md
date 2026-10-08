# SYSU-GO isolated testing configuration

Debug now has application ID com.sysu.go.debug, synthetic demo-sysu-security Firebase resources, and Android Emulator host 10.0.2.2. API/socket port 3000, frontend 5173, Auth 9099, Firestore 8085, Storage 9199. GoApplication validates project and exact endpoint allowlists before AuthManager/listeners/activities, then attaches all three Firebase SDKs to emulators. Startup validation failure is fatal; it never silently falls back to production. Real-device host routing and Google sign-in/POD flows are not yet accepted. An emulator being unavailable causes errors, not production fallback.

Release keeps the existing production app ID/project/HTTPS URLs, validates exact configuration, and uses its own network_security_config with no cleartext exceptions. Release remains unsigned. Production Google Services JSON is a separately supplied ignored input; debug's committed JSON contains synthetic emulator values only. No production installation/deployment was performed.

## Backend configuration

Supply environment variables explicitly; do not copy a production .env into emulator runs:

| Variable | Emulator value |
|---|---|
| SYSU_ENV | emulator |
| FIREBASE_PROJECT_ID | demo-sysu-security |
| FIRESTORE_EMULATOR_HOST | 127.0.0.1:8085 |
| FIREBASE_AUTH_EMULATOR_HOST | 127.0.0.1:9099 |

Remove FIREBASE_SERVICE_ACCOUNT_JSON, FIREBASE_SERVICE_ACCOUNT and GOOGLE_APPLICATION_CREDENTIALS from the test process. Backend emulator mode currently covers Auth/Firestore only, and rejects Storage Admin emulator settings. Android Storage uses its own SDK emulator; start Storage before POD tests. Guarded initialization rejects missing mode, remote emulator hosts, demo/live project mixing, production project in staging, credential project mismatch, and a default Admin app initialized elsewhere. Legacy config/firebase.js delegates to the guarded singleton and no longer writes credential files.

Staging/production require SYSU_ENV=staging/production, explicit FIREBASE_PROJECT_ID, matching service-account JSON in one approved environment variable, no emulator hosts and no GOOGLE_APPLICATION_CREDENTIALS override. Production requires sysu-web-5a25d; staging forbids that project. Existing deployments must configure these variables before taking this code: the old implicit production configuration will be rejected. New staging Android configuration needs owner-supplied project/URLs and its own reviewed variant; debug cannot be repurposed to contact a live project.

## Evidence and remaining gates

Executed Gradle testDebugUnitTest/testReleaseUnitTest, lintDebug/lintRelease, assembleDebug/assembleRelease and scoped JaCoCo reports. Executed backend npm test with experimental source coverage, plus real Admin Auth/Firestore integration using Firebase emulators:exec --config firebase.test.json --project demo-sysu-security --only auth,firestore from the sibling sysu-app test workspace. The test guards both emulator hosts and absence of credentials, creates only synthetic owned docs/users and deletes those specific records. This run uses the web test rules; Admin SDK bypasses client rules, so it does not close G-07.

For the next client-rule test, deliberately exercise the mobile backend/firestore.rules and compare against the web source. The mobile Firebase deploy config currently still references that permissive file: **do not deploy it**. Authoritative deployed rules remain unverified; G-07/G-16 must validate legitimate owned Android profile/progress/POD/dropdown writes as well as exploits. Inspect merged APK/resources and device startup routing before claiming complete G-04 acceptance.

The demo-project approach and explicit SDK routing follow [Firebase emulator guidance](https://firebase.google.com/docs/emulator-suite/connect_auth). This document is an index of executed checks and configuration requirements, not permission to deploy or launch against production.

Artifact check completed: generated debug/release Firebase projects match expected values; actual release network resource has no cleartext domain exceptions. EnvironmentPolicy coverage is 100% lines/branches. Device startup/routing, safe staging variant and clean artifact/CI remain acceptance work.
