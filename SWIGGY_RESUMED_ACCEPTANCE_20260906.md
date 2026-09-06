# September 6 resumed acceptance

## Verdict: public release held

The user authorized resuming tests and supplied the new wireless-debugging port. Cloud sign-in was restored. No orders, checkout, payment, automatic write replay or Swiggy email occurred. Root coordinated smaller bounded workers and used the repository-required read-only Claude Opus reviewer; Cursor/Grok was not used.

## Physical-phone results

| Check | Observed result |
| --- | --- |
| Fresh baseline | Original one-line cart and saved address preserved. SKU/cart IDs had changed overnight, so today's private baseline was used rather than yesterday's IDs. |
| Warm native + fresh Instamart VIEW task | One reviewed salt addition at 09:00:18 IST; one upstream update_cart, 50,582ms apply. Immediate, 45-second and pre-handoff checks passed. Opening native Swiggy removed the test item and switched the reported address. Independent MCP read confirmed it. |
| Explicitly aligned native/MCP address + warm fresh task | Repeated once after selecting and independently verifying the original saved address in both surfaces. Apply at 09:08:25 IST, one update_cart, 50,934ms. Verified checks passed, then native handoff again removed the test item and changed address. |
| Exact restoration | Original saved address selected again. Final independent read after all subsequent read-only/UI checks: HTTP200, one line, exact original spin/SKU/quantity fingerprint and address. No test items remain. |
| Harpic discovery | Three read-only cleaner queries returned actual Harpic candidates. The old Android rule reproduced a false no-match for `Harpic toilet cleaner 1 litre`. |
| Harpic after fix | Installed rebuilt app reached final review with `Harpic Original Toilet Cleaner, 5 Min Action, 10X Advanced, Disinfectant` in exactly one 1-litre pack and expected saved address. Cancelled without applying. |
| Device recovery/review | Nine current-build tests passed: pending-state corruption/recreation, stale callback and double-tap guards, ten-row review, expiry, rate limits and uncertain-apply no-retry UI. These are not acoustic voice recognition tests. |

Both cart failures preceded today's cookie isolation patch. Neither the patch nor the removed fresh-task experiment is claimed to solve the handoff problem. No additional repetitive cart mutations were run after the two controlled comparisons.

## Implemented fixes and evidence

- Android measured-pack parsing no longer treats `10X Advanced` marketing as a multipack. Regressions preserve rejection of true prefix/suffix multipacks, pack-of-ten, mixed measures and ambiguous `10X 1L`. The observed full Harpic label is a regression fixture.
- Removed the unsuccessful NEW_TASK/CLEAR_TASK experiment. Restored ordinary launch behavior; the pre-handoff read-only verification remains but is not sufficient release proof.
- Existing candidate work preserved: no-backup pending-cart sentinel; exact persistence flag; no write replay; MAGGI numeric-descriptor parsing; opt-in, versioned exact UTC retention/once-emission rules.
- Backend `f6b1645`: OAuth and MCP build each HTTP request, strip ambient Cookie, then send without redirect following. Shared connection pooling, per-request bearer, timeouts and retry limits remain. MockTransport proves seeded cookies are absent on sequential and overlapping concurrent requests. This addresses a conditional cross-installation risk, not proven exploitation or the observed cart issue.
- Monitoring inspector uses supported per-prefix descriptor filters instead of an unsupported OR expression. Live read-only inspection succeeds and finds enabled 5xx/429/p95 latency alerts. Existing budget is advisory, not a hard cap; email delivery remains untested.
- Corrected candidate Play/privacy copy to retain opt-in campaign-measurement purpose and strict exclusion of shopping/address/GPS/free-text data. The app consent wording no longer incorrectly calls install-linked analytics anonymous. Ads storage/user-data/personalization consent remains denied; no permission was silently granted and no Ads conversion import is claimed. No public assets/forms were changed.

## Verification

- Android: 182 unit tests, zero failures/errors/skips. `assembleDebug`, `assembleDebugAndroidTest`, `lintRelease`, `bundleRelease`, Gradle manifest policy and standalone manifest gate passed. Lint has 94 warnings, no errors; existing compiler/deprecation warnings remain.
- Release AAB rebuilt after the consent wording correction, SHA256 `50BCD839BC32624D5C9A7FFDB078C4B184D46978EC7EE422DDDE4DF460A5FDA4`. This is not a Play upload or final release acceptance.
- Final rebuilt debug APK installed without clearing app data; the Swiggy-only home smoke test passed. This is additional to the nine device recovery/review tests above.
- Backend: 505 pytest cases passed, 13 warnings. Focused transport tests also pass.
- Initial focused Gradle invocation failed without a backend key; a root invocation then found conflicting SDK environment paths. Supplying the real Secret Manager keys and retaining the existing SDK configuration resolved both; final full build passed. An initial phone-driver invocation failed foreground precondition and was not counted as acceptance.
- Current Play Publisher readback: beta/open testing version16 completed; production empty; internal version3 draft; alpha empty. Temporary read-only edit deleted, no commit.
- GitHub defaults read live: Android `master` at `b8a56991fc70f19c7e07dac44b42356829c6a5ac`; backend `main` at `13776908f9ea85f06a1991efab9aa6e8ad63dfd5`. Candidate code is not merged to either default.

## Independent review adjudication

Claude verdict: APPROVE WITH CAUTIONS for reviewed cookie/parser/pack changes, agree release hold. Root agrees on the cookie boundary and conservative ambiguous-pack handling. Root rejects its statement that cookie isolation was present during the two live failures: it was not yet deployed. Root also rejects loosening true-multipack detection to accept ambiguous `10X 1L`; safe rejection is intentional. Claude explicitly did not deeply re-audit analytics in this pass; unit coverage is not equivalent to live GA4/Ads delivery proof.

## Hosted transport verification

Backend build `31569410-8f64-4cc7-8d67-cb4e296a66f5` succeeded for source `f6b1645`. Digest `sha256:75510cabbb938eb28bb526e2cf87ad5ea32b3986901396e954e0059ad15fdfa1` is deployed as `beta-backend-staging-cookies-20260906`, elderly-canary tag, zero ordinary traffic. Fresh service readback confirms ordinary traffic remains 100% on `beta-backend-staging-f0fa9e1-approved`; all other tags unchanged.

Both health endpoints return200. The actual phone passed current-cart and three-query cleaner read-only probes against the new tag. Cloud Run request logs confirm `/swiggy/cart`, `/swiggy/addresses`, and `/swiggy/recommendations/batch` all returned200 on this exact new revision. Post-deploy cart fingerprint and address still match the original private baseline. Fresh OAuth consent/token exchange and mutations were not retested on this revision, so no full post-patch authorization or persistence claim is made.

## Remaining release gates

1. Supported native-app handoff with persistent exact cart/address under warm, cold and background scenarios. Do not force-stop the user's other app, clear their cart, re-add automatically, or implement checkout as a workaround.
2. Complete remaining live/acoustic and unusual-product acceptance; older passes remain in the September5 reports and are not silently counted as retested today.
3. Guarded rollout including obsolete public-tag retirement; live telemetry/Ads delivery and conversion import; final signed-artifact/screenshots/Data Safety readback; only then default-branch and Play publication. Person login and paid trial/subscription remain unimplemented separate work, not silently enabled.

## Provider references

Swiggy's [official repository important notes](https://github.com/Swiggy/swiggy-mcp-server-manifest#important-notes) warns that simultaneous native-app and MCP use can cause session conflicts. This supports a plausible explanation but does not establish the exact mechanism here. The actual top-level cart schema and replacement request agree with the current [get_cart](https://mcp.swiggy.com/builders/docs/reference/instamart/get_cart/) and [update_cart](https://mcp.swiggy.com/builders/docs/reference/instamart/update_cart/) references. The newer [Builders Club](https://mcp.swiggy.com/builders/) supports authorized third-party apps; do not treat the older README prohibition as current proof against Beta's whitelist.

Raw cart/address/OAuth receipts remain only in private device/local diagnostic storage, never this report or Drive handoff.
