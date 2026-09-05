# Swiggy recovery and usability verification — 2026-09-05

## Verdict

**Later continuation, 17:45 IST:** the address-ID blocker below is now resolved by backend `a05e171`; it is retained below as historical context. Nonempty address checks pass. A controlled five-addition cart-only test persisted through 274 seconds and native cold-launch when native Swiggy was first force-stopped; cleanup restored the original cart/address exactly. The earlier warm-native test reverted after immediate verification, with no user changes. This cross-client persistence risk still blocks public release. Backend incoming-request protection `d9b5c1b` passes 468 pytest checks; deployment verification is recorded separately. These additions do not upgrade acoustic tests or unresolved unusual-product lookups to passes.

The app is better hardened for an elderly user's interruptions and mistakes. These checks do **not** establish release-ready cart completion. The saved-address/cart-address identifier contract still blocks exact-address cart acceptance. No cart or address was changed and no checkout, payment or order was attempted in this continuation.

This report continues `SWIGGY_RATE_LIMIT_FIXES_20260905.md`; it does not overwrite the earlier unusual-product availability failure or convert read-only recent-order previews into cart-addition passes.

## Defects fixed

1. **Late voice results:** each recognition session now has its own listener and generation. Cancellation, a new session, backgrounding and destruction invalidate callbacks before teardown. Duplicate final/error callbacks are consumed once.
2. **Switching to typing:** a manual composer edit cancels active recognition. Programmatic speech partials do not accidentally cancel it. Backgrounding also stops TTS. A restored partial remains editable and requires a fresh review; it never auto-applies.
3. **Late TTS initialization:** initialization after shutdown cannot create a fallback engine or queue speech.
4. **Telemetry:** release code previously stripped unknown-event parameters but could still send the unknown event name. Numeric values could bypass string-enum validation. Unknown names now stop before emission; enums, Booleans and bounded counts have distinct allowed types. No evidence of an actual private-data transmission was found. Consent defaults and Boolean serialization are preserved.
5. **Address-ranking analytics:** the actual `Same area` labels are allowlisted, avoiding a strict-debug validation exception when an opted-in user selects one.
6. **Interrupted HTTP response:** a body-read IOException now reaches the owner as a failure rather than leaving it waiting. Parser errors cannot double-deliver the callback. An uncertain cart response tells the user to review Swiggy; no automatic retry is introduced. Mutation 5xx failures are no longer marked retryable, including non-JSON gateway responses.

Production scope: `OrderVoiceInputController.kt`, `MainActivity.kt`, `IndianEnglishTextToSpeech.kt`, `BetaTelemetry.kt`, `SwiggyMcpClient.kt`. No backend runtime, provider selection, address-equivalence, cart matching, checkout, or payment logic changed here.

## Evidence

Physical device: CPH2487, Android 36, existing signed-in installation, in-place debug update. Debug backend: hosted `elderly-canary`, not local Docker.

| Check | Verified result | Limit of evidence |
| --- | --- | --- |
| Voice lifecycle | 9/9 initial instrumented cases passed | Fake recognition sessions; includes actual MainActivity edit/pause hooks, not acoustic recognition |
| Voice error UI | NETWORK, PERMISSION and NO_MATCH update the real status without losing/disabling composer | One additional harnessed device case, no actual network/mic failure induced |
| Review interactions | 4 new + 3 existing device cases passed | Real dialog widget with synthetic callbacks; no actual cart apply |
| Large text | Font scale 2.0: Stop visible; confirmation, full caption and tenth row reachable by scrolling. Separate home check: connection, composer, mic and Continue reachable; Continue text fits and is not ellipsized | Does not prove full TalkBack/acoustic acceptance |
| Draft lifecycle | 3/3 device tests passed: recreate, debounce/prepare-clear/recreate, failed-clear preserves text and blocks prepare | Synthetic encrypted draft restored afterward; no apply request sent |
| TTS lifecycle/probe | 2/2 device cases passed | Probe records prerequisites; late-init test checks shutdown boundary |
| Google TTS synthesis | 1/1 fresh phone test passed | Synthetic five-item English fixture rendered privately to WAV; not recognition or a listener's quality judgment |
| Live read-only address/cart | 1/1 contract capture passed at 15:30 IST; recognized empty cart, selected saved ID length 33, no current cart ID | Empty cart cannot prove address-ID equivalence |
| Coordinator failure UI | 3/3 passed: throttled wait, reconnect once, uncertain apply demands cart review/no retry. Repeated failure callback ignored; composer preserved | Synthetic callback state in real coordinator, no actual network fault/cart mutation |
| Unit suite | Final suite 174/174 passed, zero failures/errors/skips | Includes one additional gateway-recovery regression after the first 173-test run |
| Build/policy | Debug/test APK and signed release bundle build; release-manifest gate passed | Bundle is not uploaded, installed from Play, or approved |
| Lint | 0 errors / 92 warnings | Existing warnings remain; not a zero-warning claim |

## Speech-file recognition safety stop

The attempted file-backed recognition preflight did **not** pass. The phone returned success for the microphone-privacy shell command, but the public microphone-mute readback remained false. The runner therefore did not start recognition. Microphone permission remained denied; font and privacy settings were restored.

The opt-in `SwiggyInjectedSpeechReadOnlyTest` remains gated by `-e liveSwiggyInjectedSpeech true`, denied app microphone permission, and an affirmative microphone-mute readback. Do not waive those checks to force a pass. Android documents that an implementation which ignores [the supplied audio source](https://developer.android.com/reference/android/speech/RecognizerIntent#EXTRA_AUDIO_SOURCE) may use the microphone. [AudioManager's public mute query](https://developer.android.com/reference/android/media/AudioManager#isMicrophoneMute()) is a conservative safety prerequisite here, not a claim of vendor privacy-toggle equivalence.

Actual spoken accents, unusual pronunciations, noise, and full voice-to-cart completion remain unverified. Typed spelling/alias tests and generated TTS must not be labelled acoustic acceptance.

## Independent review

Claude Opus advisor and separate reviewer inspected the scoped changes read-only. Reviewer found no P1/P2 defect in the five production diffs and flagged a low-risk inconsistency: mutation 5xx responses still carried `retryable=true`. Root independently confirmed no production caller consumes that flag, then closed the inconsistency and added a 500/502/503/504 non-JSON regression. Root ran all commands and device checks; Claude's approval is not test execution evidence.

Smaller agents supplied bounded tests and a test-claim audit. Root corrected fixture/import/matcher mistakes, reviewed production wiring and ran the failing commands again. Passing helper tests are intentionally separated from live acceptance.

## Reproduction and safety

- Worktree: `D:\Projects\beta\.codex-worktrees\swiggy-mcp-primary`, branch `codex/swiggy-mcp-release-readback`, base commit `9b67e03`.
- Build with the hosted debug URL and `BETA_BACKEND_API_KEY` obtained privately from Secret Manager; never print the key.
- Relevant local commands: `testDebugUnitTest`, `assembleDebug`, `assembleDebugAndroidTest`, `lintDebug`, `bundleRelease`, `scripts/check_release_manifest.ps1`.
- Install APKs with `adb install -r`, never clear app data or replace the installation identity.
- Run explicitly selected classes through `am instrument -w -r -e class ... live.betaapp.android.test/androidx.test.runner.AndroidJUnitRunner`. Do **not** run every device test: identity helpers and live mutations require separate review.
- Selected safe classes: `OrderVoiceInputLifecycleTest`, `SwiggyReadOnlyUiResilienceTest`, `SwiggyDeviceReadOnlyProbeTest`, `SwiggyDraftLifecycleTest`, `SwiggyReviewInteractionTest`.
- `SwiggyCoordinatorFailureUiTest` injects failure results into the actual coordinator with counter callbacks; it never starts an order, reconnects, or clicks the Open Swiggy action.
- The TTS fixture requires `-e betaSynthesizeFixture true`; read-only contract capture requires `-e liveSwiggyAddressContract true`.
- Large-font execution captures the original `font_scale`, changes it to `2.0` temporarily, verifies actual app resources report 2.0, and restores the original value in `finally`. Verified original/restored value: 1.0.
- Raw account/cart/address responses remain app-private. This report and handoff contain aggregates only.

## Remaining release gates

1. Authoritative Swiggy saved-address/cart-address mapping, with regression coverage. Do not infer equivalence from street overlap, GPS area, category or postal code.
2. Reviewed 5/7/10-item reversible cart-only acceptance at the exact confirmed address, with independent readback and cleanup. No checkout/payment.
3. Real controlled voice/text interchange and address readout; TalkBack, noise/accents and long spoken lists remain separate checks.
4. Final current hosted candidate and Play-installed build verification, then default-branch merge/push and listing/assets/release updates. No GitHub push, Play upload or backend promotion in this continuation.

## Final verification

- Completed 15:42 IST. **174 unit tests, zero failures/errors/skips. 30 distinct instrumented test methods passed** across selected runs: voice lifecycle/error UI 10, new dialog/home 5, device/TTS shutdown 2, draft 3, existing dialog interaction 3, coordinator failure 3, TTS synthesis 1, address/cart contract 1, cart schema 1, home smoke 1. Repeated probe/font configurations are not counted twice. Blocked audio recognition is excluded.
- Final Gradle build: debug APK, test APK, lint, signed release AAB all succeed; release manifest gate passes. Lint: zero errors, 92 warnings.
- Release AAB SHA-256: `fb4476725e732e5bb975c41db3a7f2850245c12b1aab736e2a4b1321c369cf87`. Version 17 / 0.3.0; release URL remains the ordinary hosted backend, while installed debug uses the elderly canary. Do not confuse building this bundle with testing/promoting its release backend.
- Final cart GET at 15:42 IST: HTTP 200, recognized items array count 0, `cartAbsent=true`. No additions/removals occurred in this continuation.
- Final probe: font scale 1.0 restored, microphone permission denied, microphone mute false as before; location mode 3 unchanged, no enabled AccessibilityService. Phone returned to MainActivity and home smoke passed.
- Cloud routing rechecked: main 100% `beta-backend-staging-f0fa9e1-approved`; `elderly-canary` remains `beta-backend-staging-rate-guard-20260905`, no ordinary traffic. No cloud configuration or backend code changed.
- Code and report are saved on the local release branch. No GitHub push/default-branch merge, Play action, external email, or persistent monitoring was performed.
