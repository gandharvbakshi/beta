# Swiggy release-readiness fixes — September 5

Updated: 2026-09-05 11:54 IST.

Release gate remains **NOT PASSED**. This report supersedes the three matching/draft failures in the earlier E2E report, not its outstanding cart/address or acoustic-voice gates.

## Implemented

- Product identity is checked in the backend and Android, including optional Change candidates. Original query qualifiers survive broadened catalogue searches. Sesame paste is normalized to tahini; unrelated fish-fry/curry/ginger pastes are rejected.
- `keenwaa`, `keenwa` and `keen waa` resolve to quinoa. Quantities remain unchanged. Known `buttr` and `mozrella` spelling corrections still work.
- Protected real words cannot fuzzy-match in either direction (soup/soap, bitter/butter, floor/flour, ginger/finger). Complete short-name spelling matches remain bounded; long names and learned preferences retain all identity qualifiers.
- Draft text is stored locally, encrypted with Android Keystore, excluded from backup, bounded to 8000 characters and 24 hours. Cold restart restores only the composer text and requires fresh address/product review. No product IDs, plan tokens or automatic apply are restored.
- Before a cart apply, queued saves are cancelled and a durable encrypted clear is read back. Failure aborts before the provider call. A list is intentionally not restored after an attempted/unverified mutation, because a repeat could duplicate items. No new permissions or dependencies.

## Verification

| Check | Result |
|---|---|
| Android complete unit suite | 158 passed, zero failures/errors/skips |
| Backend complete suite | 394 passed; focused identity/elderly suite 21 passed |
| Debug APK and instrumented APK | Build and in-place phone install passed; authentication preserved |
| Release bundle and manifest policy gate | Passed; build proof only, not Play submission or final signed release approval |
| Lint | Zero errors, 92 warnings; deprecations/existing warnings remain |
| Physical-phone store/lifecycle/dialog tests | 12 passed: 6 store, 3 lifecycle, 3 actual-dialog dummy-callback tests |
| Manual cold restart | Composer and open review both restored grocery text only; no auto-apply |
| Actual `milk, keenwaa, bread` flow | Quinoa shown, Add 3 lines review reached; not applied |
| Actual `sesame paste, milk` flow | One grouped missing-product screen, milk retained, no unrelated paste; not applied |

The repeated lifecycle suite initially hit a test-fixture pre-layout Espresso error (zero-size view). This test checks the storage hook, so it now sets text on the Activity main loop, exercising the same watcher without depending on first layout. The corrected full 12-test suite passed. Actual manual composer/UI verification is separate evidence.

## Independent review

Claude Opus approved the draft anti-replay architecture with cautions. Root reproduced and fixed its soup/soap confusable finding on both platforms, but preserved the explicit tested buttr/butter typo instead of blindly rejecting that correction. Root retained the pre-apply draft clear despite its intentional convenience tradeoff. Claude did not independently review the latest backend diff; backend review and 394 tests are root evidence.

## Deployment and limits

- Backend source commit `adbcaeb`, branch `codex/swiggy-mcp-cart-hardening`.
- Final test revision `beta-backend-staging-identity-guard-20260905`, 0% ordinary traffic, tag `elderly-canary`.
- Build `20cecaf6-cbac-434e-946a-b253a314e655`, image digest `sha256:37e8adcc3ab882482fc032d243dab1bdcb64d6c88fc2fcb04afeeb619ec8611a`.
- Earlier identity-only test revision used digest `601ac830...`; it was superseded after the confusable review fix.
- No cart mutations, checkout, payments or orders in this turn. No default-branch push or Play upload. Authoritative address-ID mapping remains a release blocker; do not weaken it by inferring equivalence from GPS/street text.
- A missing catalogue candidate is not proof of a stockout. The unusual-item basket and exact-history semantic acceptance must be reported separately from endpoint/planning success.

## Final-canary live results

- Five regular queries: 5/5 expected top products, batch 3683 ms.
- Seven mixed queries: 6/6 defined-product expectations matched, batch 4294 ms. The symptom-only medicine phrase remains safely unresolved, not guessed as Vicks.
- Ten unusual queries: 7/10 expected matches, batch 5325 ms; tahini, edamame and parchment returned no suitable candidate at that harness-selected address. This assertion remains **FAIL**, not waived. The harness uses current-cart/first saved address, unlike the last-selected-address counterfactual, so availability differences are not a controlled same-address comparison.
- Counterfactual: sesame paste/tahini returned zero unrelated candidates; raw keenwaa returned 8 quinoa candidates. At the last-selected address, parchment had 2 matches, baking paper 1 and butter paper 4 in the first identity deployment. Stock varies and this does not prove universal availability.
- The final-canary combined run encountered HTTP 502 on a history plan preview and then cart read. Cloud request logs confirmed both errors; no cart or plan endpoint was modified by this matching patch, and deployment environment/runtime identity matched the preceding test revision. Exact provider error cause was not captured; do not claim it was diagnosed or fixed.
- One bounded diagnostic cart-read rerun, after inspecting the errors, returned HTTP 200 with empty/absent cart. The schema harness now writes an app-private timestamp/status receipt even on error, so an old successful JSON file cannot be mistaken for a fresh readback.
- Earlier identity-only canary completed all returned recent-order previews. A single bounded final-source history retry still failed on a plan preview with HTTP 502 (45.506 s); final-source recent-order acceptance remains **FAIL**. Do not replace this with the earlier successful history result. No added items or orders in any of these tests.
- Follow-up diagnostics now also preserve failed preview status/body privately and accept only the exact 409 `cart_no_changes` contract, not arbitrary error prose containing “no change.” This harness change is compiled; another live history run was deliberately not started after repeated errors.
- Final phone state: Beta connected/ready, synthetic composer draft cleared and cold-restart checked empty, location mode 3 unchanged, microphone permission unchanged. Latest successful timestamped cart read returned HTTP 200 with zero items. No default-branch/Play changes.
