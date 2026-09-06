# Android Beta Swiggy MCP Instructions

## Project context

This is a Swiggy Instamart-only Android cart assistant. The active app uses the
authorised Swiggy MCP connection and supports interchangeable voice and text,
saved-address confirmation, recent-order/product ranking, exact cart-plan
review, confirmed cart updates and cart readback.

Blinkit, Zepto, screen-assisted automation, AccessibilityService, screen
capture, media projection and overlays are deliberately absent from the active
repository. They are preserved only in private archive repositories. Do not
restore or re-advertise them without an explicit product decision and a fresh
Play/privacy review.

## Safety boundary

The user explicitly authorised Swiggy MCP checkout/payment/order implementation
on 2026-09-06. This supersedes the former cart-only implementation boundary.
The user's later 2026-09-06 instruction explicitly approves enabling checkout
and releasing to GitHub/default branch and Play after implementation checks.
Keep the backend kill switch, explicit final user confirmation and offline
verification. The owner's latest September 6 instruction separately authorises
ONE live purchase: one packet of Vicks cough tablets, two Amul dark chocolates,
and one pack of mosquito patches. Confirm the exact products, delivery address,
full total and payment method before that transaction. No additional purchases
or ambiguous retry attempts are authorised. Other transaction tests remain mocked.
Never store payment credentials, bypass Swiggy safeguards, run high-volume automation or scrape
large amounts of data. Other live tests may search, discover, plan, add to cart and
verify the resulting cart, then must stop. Preserve the user's pre-existing cart
and remove only clearly identified test-added items.

## Permissions and privacy

The active manifest should need only Internet plus optional microphone and
coarse/fine location. Microphone and location are requested just in time.
Location is used on-device to rank saved addresses; raw GPS must not be sent to
the backend or analytics. Grocery text, product/cart content, addresses, tokens,
OTPs and feedback must never enter Analytics or Google Ads payloads.

Firebase Analytics and Crashlytics are opt-in and disabled by default. Android
Advertising ID and personalised advertising remain disabled.

For Play listing, Data Safety or privacy changes, read
`PLAY_STORE_POLICY_KB.md` first and keep these aligned:

- `app/src/main/res/values/strings.xml`
- `play_store_assets/privacy-policy.html`
- `PRIVACY_POLICY_DRAFT.md`
- `PLAY_CONSOLE_SUBMISSION_GUIDE.md`
- `PLAY_STORE_TESTING_PREP.md`

Package: `live.betaapp.android`. Namespace: `com.example.beta`. Never claim a
Play form, release or review was submitted without current API/Console proof.

## Testing strategy

Use Espresso for the app UI, UI Automator only for Android/Swiggy UI that cannot
be controlled in-app, ADB for installation/logs and Gradle for builds. Prefer
stable resource IDs and deterministic waits over arbitrary sleeps.

Use the smallest relevant command first:

```powershell
.\gradlew assembleDebug
.\gradlew testDebugUnitTest
.\gradlew assembleDebugAndroidTest
.\gradlew lintRelease
.\gradlew bundleRelease
```

For one connected test class:

```powershell
.\gradlew connectedDebugAndroidTest "-Pandroid.testInstrumentationRunnerArguments.class=com.example.beta.BetaAppSmokeTest"
```

Release verification must fail if the merged manifest/AAB contains overlay,
media-projection, AccessibilityService, legacy service classes, Blinkit or Zepto
package queries.

## Hosted backend

Default Android and release verification target the hosted Cloud Run backend:

- Project: `beta-496723`
- Service: `beta-backend-staging`
- Region: `asia-south1`
- URL: `https://beta-backend-staging-kvuem5t7mq-el.a.run.app`

Verify before live testing:

```powershell
gcloud run services describe beta-backend-staging --project beta-496723 --region asia-south1 --format="value(status.url)"
curl.exe -s https://beta-backend-staging-kvuem5t7mq-el.a.run.app/health
```

Fetch backend keys from Secret Manager without printing them. Local backends are
for isolated debugging only and are not production proof.

## Observability

Use the `BetaAgent` tag for coarse lifecycle and failure events. Do not log item
names, grocery instructions, addresses, recent-order contents, GPS, OAuth codes,
access tokens, OTPs or payment data. Live recent-order probes must emit aggregate
counts only.

Useful coarse events include connection state, instruction source/length,
address-count/confirmation, discovery result counts, cart-plan confirmation,
verified cart success/failure and reconnect-required state.

## Required workflow

Before changes, inspect the real package, `MainActivity`, XML layout, coordinator,
MCP client and relevant tests. Share a short plan and make the smallest coherent
change. Run focused tests before broader verification. Diagnose exact failures
before patching.

Use Claude Opus through the repository's `claude-consult` skill for non-trivial
architecture, Android lifecycle/security work and final adversarial review.
Claude is read-only; Codex remains responsible for edits, tests and decisions.

## Definition of done

A release task is complete only when:

- backend and Android test/build status is explicit;
- the hosted service and physical-phone cart-only flow are verified;
- checkout has explicit final user confirmation, durable duplicate prevention,
  and enabled release follows the owner's express authority with honest test limits;
- manifest, privacy, listing and Data Safety are aligned;
- final screenshots come from the verified build;
- GitHub/Play/Cloud actions are read back from the live surface; and
- remaining external blockers are reported without overstating completion.

## ChatGPT handoff

Canonical folder: `G:\My Drive\ChatGPT handover`.

After meaningful work, update:

- `BETA_ANDROID_PROJECT_STATUS.md`
- `BETA_ANDROID_ROADMAP.md`
- `BETA_ANDROID_DECISIONS.md`

Use repo/test/live evidence. Do not copy secrets, tokens, private user data or
sensitive logs. If the Drive path is unavailable, report that the canonical
handoff still needs syncing.
