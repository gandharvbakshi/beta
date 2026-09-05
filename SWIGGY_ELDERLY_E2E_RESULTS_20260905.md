# Swiggy elderly acceptance — 2026-09-05

Updated after the matching/draft fix pass. **Release gate: NOT PASSED.** See [current fix verification](SWIGGY_RELEASE_FIXES_20260905.md). The earlier observations below remain historical evidence; sesame-paste safety, keenwaa recognition and cold-restart draft recovery are superseded only as documented in that report. No checkout, payment, or order placement was attempted.

## Unattended continuation, approximately 10:30–11:03 IST

No cart writes were attempted in this continuation. Tests targeted the authenticated physical phone and the existing elderly canary. Production app/backend behaviour was not edited; changes were limited to test scaffolds, two existing AndroidX test-library versions, and evidence documents.

| Check | Observed result | Verdict / limit |
|---|---|---|
| Android verification | 154 unit tests, zero failures/errors/skips; `lintDebug` succeeds with 89 warnings and no errors; instrumented APK builds/installs | PASS; existing warnings remain |
| Backend verification | `python -m unittest discover -q`: 383 tests, 11.878 s | PASS, offline only |
| Seven-item grouped help | Actual input included `keenwaa` and `woh cough wali goli`; both were grouped into one help screen; other five items remained pending | PASS safe grouping; **FAIL keenwaa recognition**, not a stockout conclusion |
| Edit your list | Original seven-item text returned intact to the composer | PASS; keyboard autocorrection occurred during initial entry, so assertions use the observed input, not intended ADB keystrokes |
| Seven-item resolved review | two milk, Amul butter, Aashirvaad atta, mozrella, quinoa, Dabur honey, Vicks cough drops reached one review, with Add 7 lines; milk was 0 to 2 packs | PASS through review only. Suggested atta was high-protein and butter unsalted; ordinary-default quality merits further review |
| Ten-item resolved review | milk, bread, eggs, banana, coriander leaves, curd, soap, orange, Dabur honey, quinoa reached Add 10 lines | PASS through review only, not cart addition or exhaustive exact-brand acceptance |
| Background/return | Seven-line final review survived Home and return to Beta; then explicit Cancel | PASS for this transition; no Apply tap |
| Change address and non-matched area | Ten-line plan replaced by address selection and fresh ten-item search. An address marked not matched to the current area reached a review with an explicit warning and correction option | PASS for warning/research path; not proof of a measured GPS distance. Original Home selected again afterward |
| Real dialog interactions | `SwiggyReviewInteractionTest`: tenth-row scroll/Change and repeated action, stale row reference after new presentation, Back/dismiss invalidating Apply | **3 PASS**, 3.346 s. Real UI component with dummy callbacks; not live double-Apply/cart mutation proof |
| Home smoke / package | `BetaAppSmokeTest`: 1 PASS, 0.720 s; `ExampleInstrumentedTest` passed | PASS existing-install UI, not fresh onboarding |
| Recent orders on current canary | All 6 returned orders, 36 entries, longest 11; 6 read-only plan previews; 5 entries with zero candidates. Combined history/cart-schema run: 44.496 s | Plumbing PASS; semantic/exact-product acceptance remains incomplete. Partial-match warnings occurred; do not call all proposed products correct or all absent products out of stock |
| Microphone denied | Voice tap prompted explanation then Android permission; denied without recording. Composer and Continue remained available; subsequent text flow worked | PASS denial/text fallback. Microphone remains ungranted |
| Location permission denied | Verified fine/coarse both false. Recent Home remained selectable, no Same area claim; final review warned location unavailable | PASS through review, no cart write |
| Approximate location only | Verified coarse true/fine false. Flow reached saved-address choice and review; no reliable area hint was obtained and no proximity claim was made | PASS safe fallback; positive coarse-area ranking NOT PROVEN |
| Exact measured quantity | Observed input `1.5 liters milk` reached review as 3 packs of 500 ml | PASS through review, no rounding or cart write |
| Process restart at review | Force-stopped Beta before Apply, relaunched authenticated and ready, with no pending Apply or automatic mutation | Safety PASS; **draft lost**, so seamless crash recovery is not a pass |
| Final state | Fresh cart GET: items=0, cartAbsent=true (test 0.626 s). Global location ON; fine/coarse restored granted; mic remains denied; Home restored as most recent Beta selection | PASS preservation boundary. No changes to Swiggy's delivery address/cart were submitted |

### Counterfactual product search and confirmed semantic defect

The new opt-in read-only harness uses the most recently selected saved address and permits only GET addresses plus one POST recommendation batch. It passed transport/schema assertions in 7.142 s; this is **not** a semantic pass.

- `tahini`: 0 candidates; `edamame`: 0; `soybeans`: 0. Absence under these terms does not establish catalogue-wide stockout.
- `parchment`: 2 candidates, including an explicitly labelled baking/parchment paper. `baking paper` and `butter paper`: 8 candidates each. This differs from the earlier first-saved-address harness, so no same-address improvement or stock-change claim is made.
- `sesame paste`: 8 candidates, including unrelated cooking pastes. **Actual phone flow `sesame paste, milk` proposed Aruna Fish Fry Paste, 250 g, under Change sesame paste**, with Add 2 lines available. This is a confirmed wrong-product default and an additional release blocker. It was cancelled without adding anything.
- `keenwaa` failed on the phone; the explicit `quinoa` query worked. Earlier `keen waa` fixture coverage does not cover the concatenated spelling.

### Test infrastructure correction and consultation

Initial new-dialog tests failed before app launch: `ActivityInvoker$-CC` missing. The resolved graph was core 1.5.0 with monitor 1.7.1/runner 1.6.1. Claude Opus recommended test-only version alignment; its precise version inference was not accepted as proof. Root independently inspected the graph, updated existing ext-junit 1.1.5 to 1.2.1 and Espresso 3.5.1 to 3.6.1, verified core now resolves to 1.6.1, and rebuilt. See [official AndroidX Test releases](https://developer.android.com/jetpack/androidx/releases/test).

The next failures were test root selection: Espresso inspected the host Activity rather than the full-screen Dialog. Explicit `inRoot(isDialog())` fixed the fixture without changing production code. All three dialog tests then passed. These initial infrastructure failures are not hidden or counted as app-flow failures. The standalone fixture's proposed activity-recreation test was removed before use because it did not exercise MainActivity's owned coordinator lifecycle.

### Remaining gates

1. Resolve authoritative saved-address/cart-address identity, retaining strict verification and the no-retry boundary.
2. Reject unrelated product-category defaults such as sesame paste to fish-fry paste; cover concatenated pronunciation aliases and ordinary variant preferences without restoring routine per-item questions.
3. Complete controlled acoustic recognition/mixed voice-text, TalkBack, genuine network/recreation cases, draft restoration, and verified 5/7/10-item cart acceptance after the address blocker is resolved. No ambient speech was recorded while the user was away.

No GitHub default-branch merge/push, Play upload/listing changes or canary promotion occurred. At 11:02 IST, fresh Cloud Run readback still showed main traffic 100% on `beta-backend-staging-f0fa9e1-approved`, with elderly canary receiving no main traffic. Release remains blocked.

## Build and deployment under test

- Android worktree `swiggy-mcp-primary`, branch `codex/swiggy-mcp-release-readback`, version 17 / 0.3.0. Current UX/parser/location changes are not yet merged to the default branch.
- Android `testDebugUnitTest`: **154 passed, zero failures/skips**. Debug and instrumented APK builds passed. Existing Android deprecation/JDK source-target warnings remain.
- Backend branch `codex/swiggy-mcp-cart-hardening`, commit `dfa24bb`: **383 tests passed** (`python -m unittest discover -q`).
- Cloud Build `f7f4564e-6a25-485d-89af-4c4a2d2f605d` succeeded. Canary revision `beta-backend-staging-dfa24bb-elderly`, image digest `sha256:c3b4d448405d8241a1346fca243e1bce3c090bca0e9b0c880e02aa716a24cfc1`, is healthy and receives **0% main traffic**.
- The installed phone debug build targets `https://elderly-canary---beta-backend-staging-kvuem5t7mq-el.a.run.app`. Main traffic remains 100% on `beta-backend-staging-f0fa9e1-approved`, rechecked after testing.
- Play readback: beta/open testing still version 16, production empty. No Play upload, listing update, default-branch merge, or canary promotion was performed.

## Results

| Check | Evidence | Result |
|---|---|---|
| Approved-domain OAuth | Real-phone consent/callback and fresh backend READY | PASS, earlier today |
| All returned recent orders | 6 orders, 36 item entries; longest 11 items; six read-only discovery previews | Plumbing PASS; six missing candidates across previews. Not an exact-product/cart-add pass |
| New canary 5 regular queries | milk, bread, eggs, banana, coriander leaves; 5/5 expected top candidates | PASS read-only; batch 4,012 ms |
| New canary 7 mixed queries | 2 milk, amul buttr, aashirvad aata, mozrella, keen waa, dabur honey, woh cough wali goli | 6/6 defined-product expectations found; symptom phrase is not a product identity. Batch 4,924 ms |
| New canary 10 unusual queries | keen waa, tahini, edamame, agar agar, parchment, paper napkin, bhujia, curd, soap, orange | FAIL availability/matching expectations for tahini, edamame and parchment. 7/10 expected candidates found; 5,754 ms |
| Before/after canary comparison | Old backend failed mozrella as well; new backend found the expected cheese candidate | Typo-ranking improvement verified. Same catalog/session caveats apply |
| Real 5-item text entry | Exact input verified before Continue | PASS; invalid ADB/IME input attempts excluded |
| Location globally off | Saved address selection still available; recent Home suggested without a GPS proximity claim; final review warns location unavailable | PASS through review, not a verified-address cart success |
| One combined review | Five usable requests reached Your suggested basket without per-product choice screens | PASS for E2E-085 review UX |
| Optional Change | Changed proposed 500 ml milk to the listed 1 litre UHT product; eggs, coriander, bread and banana remained in the rebuilt five-line review | PASS for E2E-086 edit UX |
| Exact 5-item cart mutation | One explicit Add 5 lines action; Swiggy native cart and independent API read showed all five exact product variants, quantity 1 each | Mutation occurred once; **overall end-to-end FAIL** because address verification failed |
| Address readback safety | Reviewed saved address ID differs from returned cart address ID; returned cart ID not found among 45 saved address IDs | Guard correctly withheld verified success. Do not weaken it or assume the two IDs identify the same flat |
| Product identity diagnostic | All five current cart spin IDs found in fresh same-address candidates; all five SKU IDs present and equal; exact quantities matched | Product-ID mismatch not supported by this diagnostic |
| Cleanup | Individually removed exactly the five test rows in native Swiggy; then fresh `/swiggy/cart` read | PASS: zero items, cartAbsent=true. No generic clear-cart operation |
| TTS fixture | Google TTS synthesized the fixed synthetic grocery phrase into an app-private WAV | PASS synthesis only (1.682 s); not speech-recognition or acoustic quality proof |
| Real microphone recognition, mixed voice/text, denied/coarse location permutations, full 7/10 cart writes, retention instrumentation | Not completed in this session | NOT RUN; do not infer from units or previews |

Single observed batch latencies above are not p50/p95 estimates. Read-only harness failure is retained; missing search candidates have not been independently established as genuinely out of stock.

## Address discrepancy — unresolved release blocker

The chosen Home address exists in the saved-address list and has an opaque 33-character ID. The cart returns a numeric 9-character address ID which is not in that list. Their street text overlaps, but this is **not proof of identical house/flat or an authoritative ID mapping**. No raw addresses, IDs, coordinates, account history, or credentials are recorded here.

The first hypothesis was provider-added SKU metadata causing a fingerprint mismatch. That is a reproducible code-level edge case, and Claude agreed it would be a narrow potential fix. The live diagnostic instead found matching SKU IDs and an address-ID discrepancy. Therefore **no verification relaxation or speculative SKU fix was shipped**. A temporary hypothesis-only failing test was removed; the backend's complete suite remains green.

Provider contracts: [saved address IDs](https://mcp.swiggy.com/builders/docs/reference/instamart/get_addresses/), [cart-update identifiers](https://mcp.swiggy.com/builders/docs/reference/instamart/update_cart/), and [cart address schema](https://mcp.swiggy.com/builders/docs/reference/instamart/get_cart/). Treat these returned identifiers as opaque. Establish a documented or strongly verified mapping before accepting different IDs; do not infer delivery identity from GPS, category, street overlap, or PIN.

## Review and next gates

- Claude Opus reviewed the one-review/edit/epoch flow and found no material defect in the inspected paths. Lead reviewed/corrected worker fixtures and stale cancel/dismiss handling; all 154 Android unit tests passed afterward.
- User direction supersedes routine per-item clarification: propose a basket, make edits optional, confirm exact products/quantities/address together. Vague symptom products and impossible exact quantities still need grouped help; quantities are never rounded up or clamped silently.
- Resolve the cart address-ID contract first, then rerun one reviewed five-item mutation and exact-address readback, preserving baseline and test-only cleanup.
- Complete the 7/10-item, unusual-query counterfactual, voice/mixed-input, location-denied/coarse/far-address and lifecycle cases before claiming broad acceptance.
- Refresh screenshots for the actual one-review UI, verify Play disclosures and final signed artifact, then merge/push default branches and submit the tested release. Android's actual remote default branch is `master`, not `main`.
- Phone remains authenticated; location restored ON (its original setting); test cart restored empty. Latest app/test APKs remain installed for resumption. No email monitor is running.
