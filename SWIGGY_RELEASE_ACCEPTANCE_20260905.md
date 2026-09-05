# Swiggy release acceptance — 2026-09-05 continuation

## Current verdict

Public release is held. The address-ID defect is fixed; the warm-native cart reversion is not. All mutations are cart-only and individually reversible. No checkout, payment or order has been attempted. GitHub default branches and Play have not been updated.

## Implementation and verified build evidence

- Backend `a05e171`: map the full saved ID to its validated native prefix only after complete fresh saved-list membership/uniqueness checks. Apply accepts only the exact saved ID or its uniquely bound native ID; outbound IDs stay unchanged. V2 plans bind both IDs and revalidate before claiming the nonce. Unknown/ambiguous/incomplete lists fail closed. Ten new address test methods plus existing cases passed (428 tests at that stage).
- Backend `d9b5c1b`: pre-body admission, body/query/header caps, total body deadline, shared atomic Firestore request budgets, callback bounds and generic feedback storage errors. 468 pytest cases passed. Scoped TTL is ACTIVE. Independent reviewer found no remaining code-level security blocker for the protected canary; promotion and old-tag cleanup remain public-release gates.
- Backend `5899759`: a live ten-item review incorrectly treated the parent-company banner in `A TATA Product - Organic India ...` as the requested Tata product brand. The review was cancelled without applying. Only anchored ownership banners are excluded from identity matching; display names and product IDs are unchanged. Full suite: 471 passed, 73 subtests; strengthened strict-pack regression separately passed with all 14 identity tests.
- Backend `52f966f`: after the brand fix, another fresh review selected garlic/herb butter for generic Amul butter. It too was cancelled without applying. A bounded score penalty now makes plain butter/milk outrank unrequested known flavours/bases even with strong history. Explicit qualifiers and strict learned titles are exempt; candidates remain available for one final review. The known `buttr` typo retains the same preference. Focused identity tests: 18 pass; final full suite: 475 passed, 73 subtests, 13 existing warnings. Other product families/variant types remain unchanged; this is not a universal dietary classifier.
- Android production code remains `0e706ab`. Fresh hosted-canary `testDebugUnitTest assembleDebug assembleDebugAndroidTest`: BUILD SUCCESSFUL; 174 units, no failures/errors/skips. No new Android permission/dependency or checkout flow was introduced. Earlier release-bundle/lint evidence remains historical, not a fresh Play-installed acceptance claim.

## Live verification ledger

| Test | Outcome | Interpretation |
| --- | --- | --- |
| Nonempty current-cart address contract/context | 3/3 pass on address-map revision; 3/3 again on incoming-guard revision | Saved hint maps to exactly one saved record; no empty-cart skip |
| Five additions, native Swiggy warm | Immediate apply/readback success; independent read about 40 seconds later reverted to original baseline | **FAIL persistence**. User explicitly confirmed no manual changes. No automatic retry |
| Fresh five additions, native Swiggy force-stopped | Independent reads at 21, 59, 128, 274 seconds retained every exact line/quantity and original baseline. Native cold-launch displayed all six lines | **Conditional cart-only PASS**, not proof of warm-native safety |
| Five-item cleanup | Each introduced pack removed individually; independent fingerprint and address exactly equal original baseline | PASS. No clear-cart tool used |
| Incoming guard probes | health 200; unauthenticated cart 401; oversized feedback 413; capped callback 422; GET health with body rejected 400 by upstream | PASS bounded probes; no load/penetration-test claim |
| Recent orders on incoming-guard | Six orders / 36 entries / longest 11 / six safe plan previews. Seven entries had no current candidates. No additional page reported | PASS read-only; not six real additions, not proof of stockout cause |
| Regular five-query discovery | 5/5 expected candidates; 4.669 seconds observed | PASS read-only, one sample not p95 |
| Seven-query brand/generic discovery | 6/6 defined product expectations; vague symptom phrase not guessed as medicine; 6.017 seconds | PASS expected safe handling; not seven added products |
| Ten unusual queries | 8/10 expectations; tahini/sesame paste and edamame lacked expected candidates; 7.216 seconds | **Fixture FAIL** on two expectations. No unrelated substitute accepted; not proven stockouts |
| Ten-item branded final review | Final `52f966f` review selected Tata Crystal Salt and Amul Safed Makkhan/White Butter; all ten products, exact packs/quantities and original Home address reviewed together | PASS scoped brand/flavour defaults. The two earlier faulty reviews were cancelled without applying |
| Ten actual additions, native Swiggy initially force-stopped | One apply at 13:21:12 UTC. Immediate UI 10/10 verified; independent reads at 28, 112 and 275 seconds matched all eleven exact lines (ten additions + original water) and the original address. Native cold-launch at 165 seconds displayed all eleven exact packs with quantity one | **Conditional cart-only PASS**, including delayed native handoff. Does not fix warm-native conflict |
| Ten-item cleanup | Each introduced pack removed individually; 13:27:53 UTC independent read returned one original water, quantity one, exact baseline fingerprint and original address | PASS. No order, checkout, payment or clear-cart action |

Prior voice/recovery/large-font/permission-off tests are detailed in `SWIGGY_RECOVERY_ACCEPTANCE_20260905.md`. Actual acoustic voice-to-cart quality remains unverified; synthetic recognition callbacks, typed aliases and Google TTS WAV synthesis do not prove it.

## Deployment and operational boundaries

Incoming-guard build `fdf474f5-0069-40e4-948f-520b13632a90` and brand-guard build `3b65e322-0d4c-4087-b8d6-ccafcbb58f77` succeeded and were tested at 0% ordinary traffic. Final build `abbff605-df7a-4838-b22a-4ae51fdb37ab` succeeded; image `sha256:0016dd2e5511abfbe9c05e0418427b8059132a28e445abc5d3871f33d469af2d`; revision `beta-backend-staging-plain-default-20260905` now serves tagged `elderly-canary`, still 0% ordinary traffic, health 200. Environment/resources/runtime identity/concurrency config hash was exactly unchanged across the deployments.

Ordinary traffic remains 100% `beta-backend-staging-f0fa9e1-approved`; older public canary tags remain. Do not claim the public service is protected by the new incoming guard until the guarded revision is promoted and older routes removed/repointed and checked. Existing production cart mutation is enabled; Claude's assumption that it was already disabled was incorrect, and no global disable was performed.

GitHub live default refs checked: Android `master` at `b8a56991fc70f19c7e07dac44b42356829c6a5ac`; backend `main` at `13776908f9ea85f06a1991efab9aa6e8ad63dfd5`. Do not rename Android's default branch merely because the user calls it main. Read-only Publisher readback: beta completed version 16; internal draft version 3; alpha/production empty. Temporary Publisher edit deleted without committing. Version 17 not uploaded in this continuation.

## Independent review and next gate

Claude Opus's focused session review supports a native/MCP conflict as the leading explanation and recommends holding public writes rather than relying on a close-app instruction. Root agrees with the release caution, but does not accept a proven timer-based native overwrite, complete exoneration of all transport behavior, or the assumed disabled production flag. There were two controlled runs, not a provider-confirmed root cause. Android already opens Swiggy only after the user's explicit action; it does not automatically open it on success.

No intrusive permissions, external-app kill workaround or automatic mutation replay will be introduced. The local `SWIGGY_CART_SESSION_REPORT_DRAFT_20260905.md` is **not sent** and needs action-time direction before external coordination. Swiggy's [official MCP notes](https://github.com/Swiggy/swiggy-mcp-server-manifest#important-notes) warn about simultaneous native-app use, but do not supply a dependable elderly-app reconciliation contract.

The ten-item fixture was Amul butter, Tata salt, Maggi noodles, Parle G biscuits, Surf Excel detergent, Colgate toothpaste, Dettol hand-wash, Vim dishwasher gel and Harpic toilet cleaner, plus Nandini milk. This is a synthetic consented test basket, not the user's order history. Readback fingerprints and original baseline were compared locally; raw cart/address/connection payloads remain private on the phone, not in this report. The 475-test suite and delayed ten-item check are final-source evidence; earlier six-order and unusual fixtures ran on the incoming-guard revision and were not repeated after the two narrow ranking fixes.

The six-question login/growth/security/cost audit is in `SWIGGY_LOGIN_GROWTH_SECURITY_COST_20260905.md`. Login, subscription billing and Ads conversion configuration were assessed, not enabled.
