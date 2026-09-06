# September 5 test cutoff and release decision

Recorded at 21:29 IST. Store-facing testing ended before 21:30 IST; no more testing is authorized tonight by the current cutoff instruction.

## Decision: hold public release

The delayed persistence detector is useful but does not fix cross-client cart loss. A warm native Swiggy session reverted a cart after both the 45-second exact verification and the read-only pre-handoff verification had passed. This supersedes any implication that delayed verification alone makes the app release-ready.

## Live evidence

- 21:06:43: one intentional apply of four reviewed fixtures (detergent, handwash, salt and noodles). The unmatched Harpic request was explicitly excluded, not substituted.
- The backend completed delayed verification in 51,431 ms with exactly one `update_cart` attempt. Beta displayed four verified additions.
- 21:08:56: the read-only pre-handoff check passed. Resuming the already-running Swiggy showed only the original cart line; an independent MCP read confirmed the additions were gone. No user edits or repeated Beta writes occurred.
- A public Instamart VIEW intent with NEW_TASK/CLEAR_TASK was compiled as an experimental mitigation. The final controlled test failed cart verification before reaching that successful handoff, so this intent is NOT live-validated and must not be represented as a fix.
- The final controlled request exposed `MAGGI 2 Minute` being parsed as another two-pack request. The review showed the inflated quantity before mutation. The final apply failed closed with review-needed and did not retry.
- Final independent cart read at approximately 21:25 IST: exact original cart fingerprint, original address, one line and total quantity one. Native UI also showed the original water only. No test items remain, no checkout/payment/order actions occurred.

## Implemented and verified locally

- Exact delayed cart verification, bounded locked apply, no automatic mutation replay, and read-only pre-handoff verification.
- No-backup pending-mutation sentinel with actual Activity recreation/recovery coverage; explicit review is required after uncertainty.
- MAGGI numeric product descriptor protected from quantity splitting, parser version `2026.09.05.2`; explicit two-pack requests remain two packs.
- Consent-aware analytics once-flags, first verified-cart timing, exact UTC D1/D5/D7/D28 and W1 retention contract.
- Privacy-safe backend request/provider-attempt aggregates and quota-denial metering.

## Verification completed before 21:30 IST

- Android: 181 unit tests, zero failures/errors/skips; `assembleDebug` and `assembleDebugAndroidTest` successful against the hosted canary configuration with real secrets supplied without logging them.
- Physical device earlier this continuation: 23 lifecycle/recovery/review/voice-controller/smoke tests passed. These are not acoustic speech-recognition proof.
- Backend: 501 tests plus 77 subtests passed; 13 existing warnings.
- Final independent read-only cart schema test passed. No full replay of acoustic or all unusual-product scenarios is claimed.

## Deployment and growth state

- Backend source `62985a1` deployed only to `beta-backend-staging-persistence-20260905`, `elderly-canary` tag; ordinary traffic remains on the prior approved revision. No default-branch push or Play release this continuation.
- Existing Play open-testing release remains version 16; production track is empty per the read-only Publisher check earlier this continuation.
- Live Cloud Monitoring: enabled 5xx, 429 and latency alerts; new project-scoped INR1,000/month advisory budget with 50/90/100% actual and 100% forecast thresholds. Existing account-wide budget preserved. Idempotent second apply created nothing. Email delivery has not been tested; budgets are not hard spending caps.
- Beta GA4 property `550069966` is already linked to Google Ads `626-970-2350`, personalised advertising disabled. `activation_completed` and `cart_update_verified` are marked key events. End-to-end event delivery and Ads conversion import were not established in this pass.
- Sign-in/person identity and paid 15-day trial/INR199 subscription are not implemented or enabled; official dependency approval is still unanswered. Install identity is not person identity.

## Next actions

1. Diagnose cart changes across warm native Swiggy handoff using existing evidence. Do not force-stop another app as a release workaround, silently re-add products, or send the unsent Swiggy email.
2. At the next explicitly authorized store-test window, verify a supported cold/warm/background handoff and repeat exact cart preservation/cleanup. Finish acoustic and unresolved unusual-product acceptance.
3. Only after reliability gates pass: merge to actual defaults (Android `master`, backend `main`), promote guarded backend and retire obsolete tags, finish accurate listing assets/policy readback, then submit the Play candidate.
