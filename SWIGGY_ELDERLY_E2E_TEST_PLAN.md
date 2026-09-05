# Swiggy Elderly E2E Test Plan

Status: acceptance matrix. Cases start as `NOT RUN`; actual September 5 evidence and remaining failures are recorded separately in `SWIGGY_ELDERLY_E2E_RESULTS_20260905.md`.

Latest interruption, draft, large-text, telemetry and HTTP-recovery evidence is in [SWIGGY_RECOVERY_ACCEPTANCE_20260905.md](./SWIGGY_RECOVERY_ACCEPTANCE_20260905.md). Synthetic/device-harness passes are not full live voice/cart passes. The audio-injection preflight stopped without recognition because microphone mute could not be verified; keep true-audio rows open.

## Current UX direction (2026-09-05)

The user's latest direction supersedes mandatory questions for every generic or uncertain product: propose a complete basket, display exact brands/packs/quantities and the address together, offer optional **Change** actions, and ask for one final cart confirmation. A usable suggestion is not an immediate cart addition. Only requests with no safe candidate or quantity need help; group those into one explicit skip/edit screen, never silently omit them. Any older case wording about routine per-product clarification below is superseded by this rule.

Scope: Swiggy-only beta app, voice/text interchange, saved-address ranking, exact cart-plan confirmation, verified add-to-cart, and readback. Stop before checkout, payment, or order placement.

Safety rules for every live run:

- Preserve the user’s existing Swiggy cart and delivery address.
- Only remove clearly identified items that this test added.
- Never proceed past cart verification.
- Never infer medicine suitability from vague phrasing.
- For ordinary ambiguity, make a sensible suggestion and show its exact brand/pack/quantity in the combined review before adding anything. Ask for help only when no safe suggestion exists.
- Treat `woh cough wali goli` and similar phrases as clarification-required, not as a brand match.

Evidence levels:

- `offline` = parser, ranking, policy, or copy test without device or network.
- `contract` = backend / payload / schema / read-only API behavior.
- `live` = device or hosted backend execution against the Swiggy flow.
- `true-audio` = real spoken audio and recognition, not typed transcription alone.

Latency fields:

- `target_p50_ms` = planning target for a normal successful run.
- `target_p95_ms` = planning upper bound for a normal successful run.

Recommended execution order:

1. Offline parser and policy tests.
2. Contract tests against the hosted backend.
3. Live cart-only device tests.
4. True-audio spot checks for voice recognition and TTS.

## Fixture baskets

These fixtures are reused across the matrix so coverage stays concrete.

5-item fixture:

1. 1 litre milk
2. 6 eggs
3. quinoa
4. aashirvad aata
5. `woh cough wali goli` → must ask for exact medicine / lozenge brand and pack; do not infer

7-item fixture:

1. rice 5 kg
2. tahini
3. edamame
4. agar agar
5. chia seeds
6. rock salt
7. parchment paper

10-item fixture:

1. amul buttr
2. butter
3. mozrella
4. keen waa
5. mozz a rela
6. CR2032 battery
7. zip ties
8. frozen peas
9. paper towels
10. coconut water

## Case matrix

| Case ID | Area | Scenario | Expected behavior | Evidence | Latency target | Status |
| --- | --- | --- | --- | --- | --- | --- |
| E2E-001 | Onboarding | Fresh install sees Swiggy-only home, no Blinkit/Zepto routing | Only Swiggy connection path is presented | live | p50 2s / p95 5s | NOT RUN |
| E2E-002 | Onboarding | App opens with Google TTS unavailable | App still works with text; TTS fallback is graceful | live | p50 2s / p95 5s | NOT RUN |
| E2E-003 | Permissions | Microphone permission requested only after voice tap | No premature mic prompt | live | p50 1s / p95 4s | PASS |
| E2E-004 | Permissions | Location prompt appears only when address ranking needs it | User can decline and continue with manual address choice | live | p50 2s / p95 6s | NOT RUN |
| E2E-005 | Permissions | App remains usable without overlay / accessibility / screen-cast permissions | No dependency on retired Blinkit path | contract | p50 0s / p95 0s | NOT RUN |
| E2E-006 | Home UI | Large-font readability on home screen | Primary controls remain readable without truncation | live | p50 2s / p95 5s | NOT RUN |
| E2E-007 | Accessibility | TalkBack reads the main state, connection, and primary action labels | Clear, linear narration | live | p50 5s / p95 12s | NOT RUN |
| E2E-008 | Accessibility | Focus order moves from connection to composer to address to action buttons | No trap, no hidden critical action | live | p50 5s / p95 12s | NOT RUN |
| E2E-009 | Voice UX | Tap mic, speak a short grocery request, then switch to text for a follow-up | Mixed modality stays seamless in one session | true-audio | target_p50 6s / target_p95 15s | NOT RUN |
| E2E-010 | Voice UX | Start with text, then use voice for the next item | State carries forward correctly | true-audio | target_p50 6s / target_p95 15s | NOT RUN |
| E2E-011 | Voice UX | Duplicate tap on mic while listening | Second tap is ignored or safely toggles without corruption | live | target_p50 1s / target_p95 3s | NOT RUN |
| E2E-012 | Voice UX | Speech recognition interruption mid-utterance | Partial text appears, recovery guidance is shown, no bad cart mutation | true-audio | target_p50 6s / target_p95 15s | NOT RUN |
| E2E-013 | Text parsing | 5-item fixture parsed from one sentence | Five distinct items extracted in order | offline | p50 0s / p95 0s | NOT RUN |
| E2E-014 | Text parsing | 7-item fixture parsed with commas and “and” | Seven distinct items extracted in order | offline | p50 0s / p95 0s | NOT RUN |
| E2E-015 | Text parsing | 10-item fixture with mixed generic and brand names | Brand/generic tokens preserved correctly | offline | p50 0s / p95 0s | NOT RUN |
| E2E-016 | Text parsing | “mIlk”, “doodh”, and noisy spelling variants | Canonical product normalization works | offline | target_p50 0s / target_p95 0s | NOT RUN |
| E2E-017 | Text parsing | “cough wali goli” / “woh cough wali goli” | Clarification request only; no medicine assumption | offline | target_p50 0s / target_p95 0s | NOT RUN |
| E2E-018 | Text parsing | “rice 5kg”, “500 g detergent”, “1 litre milk” | Quantity extraction is exact | offline | p50 0s / p95 0s | NOT RUN |
| E2E-019 | Text parsing | Brand vs generic: “Amul butter” vs “butter” | Distinct intent preserved if user states brand | offline | target_p50 0s / target_p95 0s | NOT RUN |
| E2E-020 | Text parsing | Unusual product names and category terms | Parser keeps the user’s wording and avoids over-assuming | offline | target_p50 0s / target_p95 0s | NOT RUN |
| E2E-021 | Address ranking | GPS near home address | Nearby saved address is only advisory; user still chooses explicitly | live | target_p50 2s / target_p95 6s | NOT RUN |
| E2E-022 | Address ranking | GPS unavailable | Most recently used saved address is only a ranking hint, not an auto-select | contract | target_p50 0s / target_p95 0s | NOT RUN |
| E2E-023 | Address ranking | Two very similar addresses exist | Confirmation reads the short label plus short detail | live | target_p50 3s / target_p95 8s | NOT RUN |
| E2E-024 | Address ranking | Current cart address differs from recent address | Current cart ownership is preserved and explained clearly | contract | target_p50 0s / target_p95 0s | NOT RUN |
| E2E-025 | Address readout | Final confirmation speaks a short address summary | Example style: “You selected Home, 8/18 in Lynwood Avenue.” | true-audio | p50 4s / p95 10s | NOT RUN |
| E2E-026 | Address readout | Long address compressed for elderly listeners | Pincode, excess landmarks, and clutter are omitted | true-audio | p50 4s / p95 10s | NOT RUN |
| E2E-027 | Address change | Change address before cart review | Pending item flow pauses and resumes safely | live | p50 3s / p95 8s | NOT RUN |
| E2E-028 | Discovery | Recent-order suggestions for a common staple | App surfaces a sensible exact match suggestion | contract | p50 1s / p95 3s | NOT RUN |
| E2E-029 | Discovery | Unusual grocery item from the 5-item fixture | App either finds exact product or asks for clarification | live | target_p50 5s / target_p95 15s | NOT RUN |
| E2E-030 | Discovery | Brand-only request for a known brand | Exact brand is preferred over a generic fallback | live | target_p50 5s / target_p95 15s | NOT RUN |
| E2E-031 | Discovery | Generic-only request for a commodity item | Generic product is found without brand bias | live | target_p50 5s / target_p95 15s | NOT RUN |
| E2E-032 | Discovery | Spelling mistake in a common item | App recovers to the intended product when confidence is high enough | live | target_p50 5s / target_p95 15s | NOT RUN |
| E2E-033 | Discovery | Vague phrase that could map to a medicine | Ask a clarifying question; do not guess the medicine | live | target_p50 5s / target_p95 15s | NOT RUN |
| E2E-034 | Clarification | User answers with exact brand / pack / quantity after ambiguity | Flow resumes with the clarified product only | live | target_p50 5s / target_p95 15s | NOT RUN |
| E2E-035 | Clarification | User refuses to clarify | Flow ends safely with no cart mutation | live | target_p50 2s / target_p95 5s | NOT RUN |
| E2E-036 | Cart plan | One-item add-to-cart from exact product selection | Cart update is confirmed before and after mutation | live | target_p50 8s / target_p95 25s | NOT RUN |
| E2E-037 | Cart plan | 5-item basket with mixed staples | Final cart whole plan is reviewed before mutation and cart readback matches | live | target_p50 15s / target_p95 45s | NOT RUN |
| E2E-038 | Cart plan | 7-item basket with one brand item and one ambiguous item | Brand is preserved; ambiguous item is proposed in the combined review with optional Change | live | target_p50 15s / target_p95 45s | NOT RUN |
| E2E-039 | Cart plan | 10-item basket with pack/weight/volume mix | Quantity conversions remain exact in the cart plan | live | target_p50 20s / target_p95 60s | NOT RUN |
| E2E-040 | Cart plan | Existing items already in cart | New test items are added without deleting user items | live | target_p50 10s / target_p95 30s | NOT RUN |
| E2E-041 | Cart plan | Test item already present in cart | Identical duplicates preserve quantity; no silent dropping or line corruption | live | target_p50 8s / target_p95 25s | NOT RUN |
| E2E-042 | Cart plan | Out-of-stock response on a requested item | No autonomous partial add; explicit reviewed approval is required before any skip or alternate | live | target_p50 8s / target_p95 25s | NOT RUN |
| E2E-043 | Cart plan | Unsafe substitution suggested by provider | App rejects or escalates the substitution, especially for medicine-like items | live | target_p50 8s / target_p95 25s | FAIL |
| E2E-044 | Cart plan | Partial basket succeeds, one item fails | Successful adds are preserved only if the user explicitly approves the reviewed skip | live | target_p50 10s / target_p95 30s | NOT RUN |
| E2E-045 | Cart readback | Post-mutation summary is spoken aloud | Readback names the added items and current address succinctly | true-audio | target_p50 5s / target_p95 12s | NOT RUN |
| E2E-046 | Cart readback | Readback after one item only | The result is short, calm, and easy for older users to follow | true-audio | target_p50 4s / target_p95 10s | NOT RUN |
| E2E-047 | Error handling | Backend unreachable or offline | App fails clearly and does not leave a half-finished cart state | live | target_p50 3s / target_p95 10s | NOT RUN |
| E2E-048 | Error handling | Network drops during discovery | App surfaces retry guidance and preserves the current draft | live | target_p50 5s / target_p95 15s | NOT RUN |
| E2E-049 | Error handling | Network drops after cart preview but before confirmation | No unchecked mutation occurs; user is told what is known | live | target_p50 5s / target_p95 15s | NOT RUN |
| E2E-050 | Error handling | Duplicate submit on the text button | In-flight mutation stays locked until terminal state is known or read back | live | target_p50 1s / target_p95 4s | NOT RUN |
| E2E-051 | Error handling | Voice followed immediately by text submission | New intent waits behind the active mutation instead of overwriting it | live | target_p50 3s / target_p95 8s | NOT RUN |
| E2E-052 | Safety | Test cleanup after a successful add-to-cart run | Only test-added items are removed, user cart stays intact | live | target_p50 3s / target_p95 10s | NOT RUN |
| E2E-053 | Safety | State baseline captured before a live run | Start cart count and address are recorded without exposing private data | contract | target_p50 0s / target_p95 0s | NOT RUN |
| E2E-054 | Safety | Re-run on the same day with the same phone | No stale state leaks into the next test run | live | target_p50 3s / target_p95 10s | NOT RUN |
| E2E-055 | Analytics | Consent off | No grocery content, addresses, or OTP-like values appear in analytics | contract | target_p50 0s / target_p95 0s | NOT RUN |
| E2E-056 | Analytics | Consent on | Only coarse events are emitted; no item text or address text leaks | contract | target_p50 0s / target_p95 0s | NOT RUN |
| E2E-057 | Retention | D1 / D5 / D7 / D28 reminder payloads | Feedback prompts are scheduled without spamming or exposing private content | contract | target_p50 0s / target_p95 0s | NOT RUN |
| E2E-058 | Voice/TTS | Controlled synthetic / approved audio for Indian English male voice | App prefers the installed Google TTS male voice if present | true-audio | target_p50 3s / target_p95 8s | NOT RUN |
| E2E-059 | Voice/TTS | Google TTS missing or unsupported | App remains functional without failing the main flow | live | target_p50 2s / target_p95 5s | NOT RUN |
| E2E-060 | Accessibility | Long shopping instruction read aloud in chunks | Voice and text remain legible and comprehensible for elderly users | true-audio | target_p50 6s / target_p95 15s | NOT RUN |
| E2E-061 | Mixed input | Start in voice, correct the last item in text, confirm in voice | The app keeps one coherent draft and one coherent confirmation | true-audio | target_p50 8s / target_p95 20s | NOT RUN |
| E2E-062 | Mixed input | Switch address, then immediately add the next item by text | Address context is preserved across modality change | live | target_p50 5s / target_p95 15s | NOT RUN |
| E2E-063 | Recovery | App backgrounded mid-flow and returned | Flow state is recoverable without repeating already confirmed steps | live | target_p50 5s / target_p95 15s | PARTIAL |
| E2E-064 | Recovery | Orientation change during a prompt | No item is duplicated and no confirmation is skipped | live | target_p50 5s / target_p95 15s | NOT RUN |
| E2E-065 | Safety | Explicit Hinglish request “do doodh” vs vague cough/medicine wording | Normal grocery can continue; medicine-like item still requires exact confirmation | live | target_p50 6s / target_p95 15s | NOT RUN |
| E2E-066 | Safety | User asks for “same as last time” without a stable history | App asks which exact item is meant rather than guessing | live | target_p50 5s / target_p95 12s | NOT RUN |
| E2E-067 | Safety | OOS item followed by an explicit user-approved alternate | Alternate is added only after explicit reviewed approval | live | target_p50 8s / target_p95 25s | NOT RUN |
| E2E-068 | Safety | Live run with a full 10-item basket but one item is unsafe to guess | App adds only the safe, exact items and leaves the rest pending for review | live | target_p50 20s / target_p95 60s | NOT RUN |
| E2E-069 | Address ranking | Location toggle is off | No location-based ranking hint is used; manual choice still works | live | target_p50 2s / target_p95 6s | PASS |
| E2E-070 | Address ranking | Permission denied for location | App falls back to saved addresses without auto-consent | live | target_p50 2s / target_p95 6s | PASS |
| E2E-071 | Address ranking | Permission permanently denied | App keeps working with manual selection and no repeated consent loop | live | target_p50 2s / target_p95 6s | NOT RUN |
| E2E-072 | Address ranking | Coarse location only | Coarse-only access can inform ranking, but never auto-selects an address | live | target_p50 2s / target_p95 6s | PARTIAL |
| E2E-073 | Address ranking | Stale location older than 5 minutes | Stale fix is ignored; no misleading area claim is shown | contract | target_p50 0s / target_p95 0s | PASS |
| E2E-074 | Address ranking | Future-dated fix | Future fix is ignored and treated as unusable | contract | target_p50 0s / target_p95 0s | PASS |
| E2E-075 | Address ranking | Inaccurate fix worse than 2 km | Inaccurate fix is ignored; no area promise is made | contract | target_p50 0s / target_p95 0s | PASS |
| E2E-076 | Address ranking | No area match while current cart exists elsewhere | Current cart address remains protected and is not silently replaced | live | target_p50 3s / target_p95 8s | NOT RUN |
| E2E-077 | Address ranking | Same area but different house | Full address remains visible in review; only the short spoken summary is compressed | true-audio | target_p50 4s / target_p95 10s | NOT RUN |
| E2E-078 | Address ranking | Duplicate Home labels in saved addresses | App asks for explicit correction instead of guessing between duplicate homes | live | target_p50 3s / target_p95 8s | NOT RUN |
| E2E-079 | Address ranking | Deliberately far-away address suggestion | Distance is never claimed from PIN alone; user sees review, not auto-consent | live | target_p50 3s / target_p95 8s | PARTIAL |
| E2E-080 | Address ranking | No GPS available but app still needs to work | Saved-address flow still completes without GPS | live | target_p50 2s / target_p95 6s | NOT RUN |
| E2E-081 | Address ranking | Geocoder or network timeout during hint lookup | Timeout ends boundedly and falls back to manual saved-address review | contract | target_p50 0s / target_p95 0s | NOT RUN |
| E2E-082 | Address ranking | Permission revoked mid-flow | App stops using location, clears the hint, and continues only with explicit review | live | target_p50 3s / target_p95 8s | NOT RUN |
| E2E-083 | Address ranking | Full address visible, short spoken readout only | Review screen shows the full address; spoken confirmation stays short | true-audio | target_p50 4s / target_p95 10s | NOT RUN |
| E2E-084 | Address ranking | Explicit final review / correction / cancel from any address state | User can confirm, correct, or cancel; no cart movement or cart clearing occurs | live | target_p50 3s / target_p95 8s | NOT RUN |

## Notes for the live execution script

Additional one-review acceptance cases:

| ID | Scenario | Expected | Evidence | Status |
|---|---|---|---|---|
| E2E-085 | Generic 5-item basket with usable candidates | No product wizard; one basket review and one final cart confirmation | live | PASS review UX only |
| E2E-086 | Change milk after reviewing five suggested items | Only milk changes; the other four selections and quantities persist | live | PASS review edit only |
| E2E-087 | Change the delivery address from review | Invalidate old plan/draft; refetch candidates for the new address | live | PASS |
| E2E-088 | Generic milk with a historical chocolate option and ordinary milk available | Propose ordinary milk; keep alternatives under Change | offline/live | PASS offline comparison and live plain-milk suggestion |
| E2E-089 | Explicit chocolate milk or learned exact preference | Preserve the explicit flavor/variant | offline/live | NOT RUN |
| E2E-090 | Two requested lines resolve to one SKU | Sum exact quantities into one cart line; never clamp totals over 20 | offline/live | NOT RUN |
| E2E-091 | Double tap Apply or use stale row/cancel callbacks | Only the current presentation's first action executes; no duplicate mutation | offline/live | PARTIAL |
| E2E-092 | Multiple unmatched or vague symptom items | One grouped skip/edit screen; skipped items remain visible in review | live | PARTIAL |
| E2E-093 | Provider returns a different address ID after cart update | Never report verified success or retry the mutation; require address investigation | contract/live | PASS guard; overall cart acceptance FAIL |
| E2E-094 | Typed pronunciation-like spelling: “keenwaa” | Resolve a known quinoa alias or safely flag it; never choose an unrelated product | live text | PASS after fix: quinoa in actual three-item review; not an acoustic test |
| E2E-095 | Sesame paste semantic identity | Sesame paste is not silently treated as fish-fry paste; final review shows the actual item outcome | live | PASS identity safeguard after fix: grouped missing-product help, no fish-fry suggestion; catalogue availability remains unproven |
| E2E-096 | 1.5 litres milk | Review shows 3x500ml equivalence for confirmation | live | PASS review only |
| E2E-097 | Cold process restart | Never auto-apply; recover the draft for a new explicit review | live | PASS after fix: force-stop/relaunch from composer and review restored text only |
| E2E-098 | Swiggy HTTP 429 during discovery | Preserve rate-limit reason and Retry-After; stop queued searches instead of reporting missing products | contract | PASS offline; live original failure diagnosed from provider HTTP logs |
| E2E-099 | Rate limit after cart mutation, before readback | Stop readback retries; report unknown outcome; never repeat mutation | contract only | PASS synthetic; no live mutation for this case |
| E2E-100 | Shared quota and capacity pressure | Atomic admission, fractional-time cooldown, bounded memory, shared Firestore state, readback quota reserve | contract | PASS offline; hosted Firestore admission exercised by read-only requests |
| E2E-101 | Latest remembered address removed from saved list | Fail the diagnostic instead of silently choosing a different/older address | harness | Implemented; current valid remembered selection exercised live |

- For every live case, record the starting cart count, ending cart count, and the exact items added by the test.
- For any case that mutates the cart, verify that the readback matches the confirmed cart plan before cleanup.
- For voice cases, use controlled synthetic audio or user-approved recordings; do not require private speech capture.
- Ordinary ambiguity belongs in the combined review; unavailable or unsafe requests require grouped help before a cart addition.
- For medicine-like phrases, the safe result is clarification or refusal, not substitution.
- If the backend or device fails, mark the case failed and preserve the evidence instead of retrying blindly.
- Execution note: September 5 results and evidence are recorded in [SWIGGY_ELDERLY_E2E_RESULTS_20260905.md](./SWIGGY_ELDERLY_E2E_RESULTS_20260905.md), with the latest rate-limit/history/stockout continuation in [SWIGGY_RATE_LIMIT_FIXES_20260905.md](./SWIGGY_RATE_LIMIT_FIXES_20260905.md). This plan keeps the remaining uncaptured cases as not run unless explicitly updated here. Native sold-out observations do not waive the unusual-basket fixture's failed availability assertion.

## What this plan expects versus what the existing automated tests already prove

- Expected behavior: the app can support voice recognition, TTS, address ranking, and cart-only add flows for elderly users.
- Current automated tests mostly prove parser, policy, ranking, and contract-level behavior.
- Current automated tests do not by themselves prove end-to-end speech recognition quality on real audio.
- Current automated tests do not by themselves prove live Swiggy UI discovery quality for every concrete item in the basket fixtures.
- Any true-audio or live case in this plan still needs direct execution evidence before it can be marked pass.

## Minimum pass criteria before a release candidate

- Offline parser, address, safety, and copy cases pass.
- Contract cases prove no grocery content leaks into analytics or retention payloads.
- At least one 5-item, one 7-item, and one 10-item basket succeed up to cart verification.
- At least one vague medicine-like request is safely clarified.
- At least one live voice run and one live text run both end with the same verified cart result.
- No case proceeds to checkout, payment, or order placement.
