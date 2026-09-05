# Beta: login, growth, subscriptions, security and cost

Verified 2026-09-05 against the active Android and backend worktrees. This is an assessment and implementation direction, not a claim that login, billing, Ads linking or billing alerts have been enabled.

## 1. Who is a user?

Today Swiggy OAuth connects an **installation**, not a durable Beta person account. Android generates a high-entropy installation bearer and protects it with Android Keystore. The backend hashes it, isolates the stored Swiggy connection by that ID and encrypts provider credentials with installation-bound authenticated encryption. Reinstalling or using a second phone creates another installation identity.

Recommendation: allow a guest first experience; offer Google sign-in to save/restore the Beta account, with phone OTP as an alternative if research supports it. Require a durable authenticated Beta UID before a server-enforced trial/subscription. Link existing installation state after authenticating; never merge users by address, GPS, name, a client-supplied UID or a phone number extracted from an address. Swiggy sign-in remains separate. No login dependency or screen was added in this continuation.

## 2. Activation and growth funnel

Primary activation should be **the person's first correctly addressed, explicitly reviewed, durably verified cart addition**. Connecting Swiggy or finding products is a leading indicator, not delivered value. The warm-native cart reversion currently means immediate `cart_update_verified` is weaker evidence than that definition.

Keep a compact funnel: first open → onboarding complete → Swiggy connected → request submitted → product discovery → address selected → final review → apply requested → cart verified / verification failed → activation. Current event names cover these stages, including `activation_completed` and `cart_update_verified`.

Track distinct users, conversion between stages, latency buckets, failure reason, app version, input mode, list-size bucket, permission denial, cart reconciliation failure, feedback, cost and cohort retention. Do not send grocery text, product names, address IDs/text, phone numbers, GPS, OAuth/installation tokens or raw voice to GA4/Ads. Use an opaque Beta UID only after account authentication and appropriate consent.

For Ads: keep Beta in its own Firebase/GA4 app/property and its own app campaign and conversion actions. The same business Google Ads account can be used; a new sub-account is not inherently required. Optimise eventually to activation, not every repeated cart event. Begin with install/connection only as explicitly labelled proxies if activation volume is too low. Do not combine SMS and Beta conversion goals or change campaign spend here.

## 3. Are the events clean today?

Partly, not yet sufficient for person-level acquisition reporting:

- `BetaTelemetry.kt` guards first open and activation once **per install**, daily active once per UTC day, and app-open emissions with a 60-second debounce. Event names, enum values and numeric types are allowlisted. Consent defaults off.
- No authenticated Beta UID is set. Local flags cannot guarantee once per person across reinstalls/devices. Use a server uniqueness key such as `(uid, milestone_version)` for person-level activation/trial milestones; retain per-session cart events separately.
- `RetentionMilestonePolicy.kt` uses the greatest milestone already reached: opening on D2 can emit `retention_d1`, D10 can emit `retention_d7`. These are return-after-threshold events, **not exact-day retention**. Exact D1/D7/D28 should be calculated from cohort activity dates or corrected explicitly. W1 should mean any return in days 1–7, not a synonym for D7; no W1 event is currently implemented.
- Late analytics consent can emit a previously completed activation on the consent date. That emission date is not the original activation date. Consent handling and original milestone dates need explicit dashboard semantics.
- D1/D5 feedback is eligibility-gated after activation and shown once at each local milestone; it is in-app, not a background push/reminder system.
- Firebase project configuration is `beta-496723`. Live GA4/Ads event ingestion, conversion imports and links were **not verified**: user ADC failed with `RefreshError`; the verified Publisher service account could list Analytics Admin account summaries but had no accessible Beta property, and user OAuth Firebase Analytics details returned 403 PERMISSION_DENIED. These results do not prove the property does not exist. Analytics consent currently keeps AD_STORAGE, AD_USER_DATA and AD_PERSONALIZATION denied; do not silently grant them to improve attribution.

Before acquisition: verify release-build events in DebugView/Realtime with consent on/off, repeat launches, reinstall, late consent and two devices; test once-per-person identity; confirm exported cohort reports and Ads conversion action mappings. Use successful cart activation as the primary KPI only after persistence is dependable.

## 4. Fifteen days free, then INR 199/month

Feasible, but not implemented or enabled. SMS's code is a useful architecture reference, **not the same commercial configuration**: it currently uses `pro_yearly`, a 14-day trial presentation and a legacy lifetime restore path.

Reuse server-authoritative entitlements, purchase verification, restore handling and billing-state separation. Create a Beta monthly subscription/base plan with the requested price; implement server-time trial eligibility, purchase token binding to UID, Play real-time notifications, expiry, cancellation, grace/account hold and refunds. Start a product trial at a clearly defined value milestone rather than wasting it during a failed setup. Decide whether this is a no-card product trial followed by an explicit purchase, or a Play subscription offer; never auto-charge merely because 15 days elapsed.

A subscription for Beta's digital service should follow Play Billing requirements. It is separate from Swiggy grocery payments. Google's published auto-renewing subscription service fee is 15%; tax, country pricing and refunds also affect net receipts. [Google Play service fees](https://support.google.com/googleplay/android-developer/answer/112622)

## 5. APK and backend security

An APK can reveal the backend URL, embedded shared key, client-side logic and Firebase public configuration. Those values are not durable secrets. That alone does **not** reveal another installation's Swiggy token/cart/address: its separate installation bearer and encrypted server record remain required. Device compromise or theft of that installation bearer is a different threat.

Confirmed abuse gaps were incoming OAuth connection creation, callback traffic and feedback admission. Backend commit `d9b5c1b` adds pre-body local admission, header auth, 32 KiB request / 64 KiB feedback caps, a total 10-second body deadline, bounded callback query values and shared atomic Firestore budgets. Swiggy is capped at 20,000 admitted requests/day globally and 1,000/install/day, with burst/minute limits; connection creation, callbacks and feedback have additional tighter limits. Denials fail closed with 429/503 and Retry-After. No checkout or clear-cart endpoint exists even with valid credentials.

The guard was verified on `beta-backend-staging-incoming-guard-20260905` and retained in the final `beta-backend-staging-plain-default-20260905`, 0%-ordinary-traffic `elderly-canary`. Its counter TTL is ACTIVE. Live probes verified health 200, unauthenticated cart 401, oversized feedback 413 and overlong callback 422; authenticated phone address/cart checks passed. Unit/focused reviews are green. These checks are bounded probes, not a destructive penetration/load test.

**Not yet a claim of protected public production:** ordinary traffic remains on `f0fa9e1-approved`, and older public tags still route around the guard. Before public release, promote the guarded revision and remove/repoint obsolete tags, verify every remaining URL, test alerts and consider Play Integrity/App Check. Application quotas limit downstream work; they do not make internet-facing compute immune to denial-of-service or cap the entire Cloud bill. Main's existing mutation flag is true; it was not silently disabled in this continuation.

## 6. Cost at ten sessions a day

The current MCP cart path does not call an LLM. Android speech/TTS is not implemented as billed Google Cloud Speech/TTS requests. Our costs are hosting, Firestore, network/logging and operations; any Swiggy commercial/API fee is **unverified**, not assumed zero.

Planning examples for **300 sessions/month**, excluding free quotas, discounts, tax, fixed infrastructure and Swiggy charges:

| Scenario | Active billed seconds/session | Inbound requests/session | Firestore reads + writes, each/session | Deletes/session | Approx. monthly cost |
| --- | ---: | ---: | ---: | ---: | ---: |
| Short | 30 | 10 | 150 | 50 | $0.29 / INR 28 |
| Longer | 120 | 15 | 300 | 100 | $1.04 / INR 99 |
| Heavy/repeated searches | 300 | 25 | 600 | 200 | $2.53 / INR 241 |

These are sensitivity scenarios, **not measured per-user invoices or observed typical sessions**. They intentionally include multiple provider calls, address pagination, request counters, token/nonce/lock state and rate-limit operations. INR conversion uses a rounded planning rate of INR 95/USD, not a guaranteed billing FX rate.

For 1 vCPU/0.5 GiB, Cloud Run Mumbai is Tier 1: CPU $0.000024/vCPU-second, memory $0.0000025/GiB-second, requests $0.40/million. One completely idle minimum instance is about $9.86/month gross at 730 hours; shared utilisation/free quotas change the bill. Multiple retained tagged revisions can add fixed overhead. [Cloud Run pricing](https://cloud.google.com/run/pricing)

Firestore Mumbai prices read from Google's regional pricing data are $0.035/100k reads, $0.104/100k writes and $0.012/100k deletes/TTL deletes. Regional database location was verified as `asia-south1`. Storage, index reads, transaction retries and network costs are extra; TTL deletes are not covered by the free delete quota. [Firestore pricing](https://cloud.google.com/firestore/pricing)

Monitoring is incomplete: Cloud Run request latency/status logs and request-budget counters exist, but the retired screenshot-model cost telemetry does not meter today's MCP journey. Billing Budget API is disabled for the project; live budget/alert configuration could not be verified. Next: billing export/dashboard, actual/forecast budget alerts, route-level 429/5xx/latency alerts, pseudonymous per-user daily usage and measured cost/session. A budget alert is not a spending cap. Validate heavy-user margin before promising unlimited usage at INR 199/month.

## Release decision

Address identity is fixed. Controlled cold-native five- and ten-item cart tests are genuinely successful, including delayed and native-cart verification. The final-source ten-item run retained all eleven exact lines through 275 seconds and native cold-launch; individual cleanup restored the exact user's baseline and address. Warm-native reversion is still unresolved. Do not equate that conditional success, instrumentation unit tests or the test-only security deployment with public release readiness. No default-branch push, Play upload, new login, paid product or ad campaign was executed in this continuation.
