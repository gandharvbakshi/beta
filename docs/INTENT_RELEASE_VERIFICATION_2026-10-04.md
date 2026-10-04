# Optional intent integration — verified evidence, October 4

## Rollout decision

The new language interpretation is OFF by default, explicitly consented, and
limited to approved installations. Release UI exposes Gemini only; DeepSeek and
OpenAI adapters remain available in development. No unqualified public 90-percent
reliability claim is supported. Ordinary matching and provider choice safety are
not bypassed. Shopping text alone goes to the model, not address/GPS/history or
Swiggy credentials. An unavailable pilot offers explicit standard matching.

## Interpretation and review

Gemini 3.8 Flash with low thinking, prompt `intent-v8-oct04`, was selected after
the earlier shortlist. Numbers below describe bounded synthetic/source-derived
tests, not a random sample of users or live completed orders.

| Set | Source-meaning audit | Actual Android review preparation | Median model latency |
| --- | --- | --- | --- |
| New synthetic 20 (192 items; 8 long baskets) | 19/20 | Initially 19/20, with 18/20 both semantically correct and review-ready | 3.16 s |
| Saved new-20 replay after fixes | Same saved outputs, not a new model trial | 19/20 correct and review-ready; known lost bag-size output now blocked | unchanged saved timings |
| History-derived confirmation 20 (239 items) | 19/20 | 18/20; incorrect certification loss blocked, one valid shared-heading basket also blocked | 4.11 s |
| Evening unseen 20 (214 requested items; ten 15–16-item lists) | 4 fully faithful, 15 failed, 1 uncertain under explicit-form preservation | 15/20 reached review, all with acknowledgment; this is not semantic success | 3.57 s |

The new set was authored/frozen before its first inference. Gold errors were
recorded before inference and source wording remained authoritative. Later
replays are regressions, not new holdout results. Literal quotes and valid JSON
alone are not semantic proof. Early strict gold-field comparison was rejected
as a semantic score because descriptors in the name and enforced tags can be
equivalent. The lead corrected a missed certification-loss audit finding.

Known limitations: arbitrary translations are not deterministically proven;
some valid group phrasing fails closed. Source quotes may span shared groups.
Bounded lexical and pack checks are safety aids, not a complete meaning verifier.
No 100-percent reliable model or automatic purchase inference is claimed.

## Safeguards and general fixes

- Original wording, proposed products and quantities remain visible in one
  combined review. Uncited/shared or corrected wording requires acknowledgement.
- Review acknowledgement resets across plans; cancellation, backgrounding and
  stale callbacks cannot apply an old plan or bypass confirmation.
- Literal request evidence is separate from catalogue normalization. Prepared
  batter/dough does not prove ready-to-eat; dry substitute forms remain rejected.
- Invented diet/form properties, missing unambiguous protected requirements,
  unsupported identity words and known explicit-pack loss fail closed.
- Container-adjacent physical sizes must survive as per-pack constraints;
  total weight cannot silently replace a specified bag. This check is bounded,
  does not infer all quantities, and does not treat protein grams as pack size.
  Exact measured totals may divide into the specified pack size; grouped numbers,
  common litre/kilogram spellings and fractions are parsed without partial matches.
- Explicit common produce singular/plural pairs match in Android/backend;
  no blanket stemming. Product-family and variant checks remain mandatory.
- Reviewed Hindi/English category aliases preserve decimals, pack sizes and the
  original utterance. Catalogue aliasing is not proof of an entire dish identity:
  staple requests must not become snacks or sweets containing that ingredient.
- A temporary status-read failure is not treated as proof of logout. Address
  suggestions remain suggestions with explicit short-address confirmation.

## Hosted / phone boundaries

No live cart/address mutation, checkout, payment or order was tested today.
Earlier read-only v6 catalogue probes found candidates compatible with identity
for 3/3 everyday, 11/15 and 14/20 synthetic items. These are not whole-basket
success; pack/count incompatibility and inventory matter separately from language.
Final v11 catalogue repeat matched the same 3/3, 11/15 and 14/20. Identity
normalization accepted all returned candidates; exact count/pack compatibility
and missing catalogue results still left items unresolved. Model times were
2.133s, 4.367s and 5.459s; catalogue times 12.026s, 37.560s and 51.237s.
This does not demonstrate an end-to-end latency improvement. Synthetic gateway
UI tests do not prove a real cart write or payment.

Latest completed phone UI repeat: 13/13 tests, 12.090 seconds. Synthetic store
screenshots were captured from the real debug UI at phone/7-inch/10-inch profiles,
with zero order-placement calls. The shown address and cart are synthetic.
All 12 images were recaptured after the final source edits; the three capture
tests passed (8.053s, 8.766s and 7.220s). Emulator display overrides were restored.
Local final regression: 373 Android unit tests passed, one explicitly opt-in
private replay skipped; debug/test APKs built. Backend: 739 tests and 282 subtests
passed, excluding only the two legacy manual localhost-demo tests.

Final six-example hosted run initially caught two issues: a strict test expected
the ready-to-drink tag even though the enforced item name preserved that form,
and literal grounding rejected the reviewed "tetta packs" correction. Exact
saved-shape unit regressions established the causes; the test now checks the
enforced meaning and grounding recognizes bounded tetra-pack spellings without
allowing the pack requirement to disappear. The repeat passed all six examples
in 13.696s (one active instrumentation test, identity-export test skipped).

Cloud revision `beta-backend-staging-intent-oct04-v11` uses image
`48198ac374351b35cbaa424e6add1cdfa5ff5ae0a9756048cfb046152375196a`;
build `43bbcd8a-154b-419f-86ad-f31474d9e361` and exact Python 3.9 image test
`64e02314-f2ea-4325-a026-c524d091939b` succeeded. Ordinary traffic was promoted
to 100 percent, health was read back as OK, and the AI audience remains allowlisted.
Non-intent runtime configuration and the runtime service account are unchanged.
Post-promotion phone repeat used the normal configured backend URL: all 13
synthetic UI tests and the six-example hosted intent test passed in 24.331s;
the identity-export test was explicitly skipped (14 active tests, one skip).

## Release record — submitted, Google review pending

Release: `0.3.4` / versionCode `21`, package `live.betaapp.android`, open-testing
track `beta`. Android runtime/source commit
`87585b191ae79c278fb383bd9885f446461cfc29` and backend commit
`d6ea016baeb063e8b32af2fcdee68b4c1498f8d9` were pushed to their GitHub `main`
branches and the remote hashes were read back. Later documentation-only commits
do not change the source of this bundle.

Final signed AAB SHA-256:
`fa35799d6b4031c1939057bfbd9788e9f5b62c35fb5497f8871f49a2bbcd59dc`.
Release lint and bundle generation passed (zero lint errors, 101 warnings).
The upload certificate and forbidden manifest/DEX gates passed. Capture source
and all 12 screenshot hashes are in `INTENT_STORE_CAPTURE_2026-10-04.json`.

Publisher edit `07870429999914262666` uploaded bundle 21, updated en-US/en-GB
descriptions and four screenshots for each of three form factors in each locale,
then returned `validated=ok` and `commit=ok`. Independent Publisher readback
confirmed bundle SHA-256, track version/status, both descriptions and all 24
uploaded screenshot hashes. The track status `completed` is the requested full
open-testing rollout, not evidence of Google approval or tester availability.

Live Play Console on October 4 showed `Changes in review`, with quick checks
running before automatic review submission, release `0.3.4` / `Start full
rollout`, listing changes and Data Safety included. Managed publishing is off.
Subsequent Console readback confirmed quick checks completed and explicitly
said the changes are now in review. Google approval and availability to testers
are NOT yet verified. Production and the internal draft track were not changed.
Do not resubmit merely because Google review is still pending.

Public privacy is live and verified; Photos-only Data Safety correction was
published and Console readback showed 0/2 selected. Precise location remains
declared for possible Android Geocoder network use. Remaining work is Google's
review outcome plus separately qualified improvements to long-list catalogue
latency, exact-count availability UX and real elderly-user acceptance; this
release does not establish the broad 90-percent whole-basket target.

## Post-submission investigation — interpretation changes withheld

A prompt-only v9 experiment strengthened the requirement that the source quote
include every supporting shared header, including containers. It was evaluated
once on the reused 20-case confirmation set. All 20 provider/schema responses
succeeded and 19 reached Android review, versus 18 for the saved v8 run. This
was not an improvement in faithful interpretation: a formerly rejected grouped
request now dropped its container description, while a certification requirement
was still lost and correctly rejected by Android. Final source-meaning audit:
18/20, with no fresh-holdout or population claim. The lead corrected the initial
small-agent audit that had missed the container loss.

V9 was rejected for deployment; ordinary v11 remains on prompt v8. A new
synthetic regression proves existing grounding accepts complete contiguous
introductory-header quotes and rejects missing-container or unrelated-sentence
borrowing. The focused grounding suite passed 19 tests after lead review. These
are test-only changes, not a different app bundle or relaxed validator.

### Fresh evening baseline and architectural findings

The evening set was independently authored and frozen before its first inference:
20 utterances, 214 requested items, ten long lists. All 20 model responses passed
JSON/schema checks and returned in 2.047–6.062 seconds. Source-authoritative
audit then found only four fully faithful baskets, fifteen failed baskets and
one uncertain basket. Most errors dropped a specified container/unit form
(cup, jar, carton, bunch, block, etc.) while keeping its physical weight/volume.
One ambiguous correction also omitted a whole neighbouring product: 213 total
items returned. These results supersede any broad confidence extrapolated from
the easier earlier sets; they do not estimate real elderly-user success.

Android admitted 15/20 to combined review, all needing acknowledgment. Five
were rejected before review. Focused synthetic characterization reproduces:
unrelated container sizes inside broad contiguous quotes; later modifiers that
cannot be supported by a shorter quote; Hindi negation not represented by the
bounded lexical equivalence rules; an added lentil-category word; and the
existing 20-count contract cap before retail-pack conversion. These are not
fixed by making a validator more permissive. The regular Android suite now has
380 passing tests and one explicit opt-in replay skip (381 total); 33 focused
grounding/pack tests pass. No Android runtime or signed bundle changed.

An isolated v10 model-schema experiment adds explicit packaging and separate
exact supporting quotes. It is NOT a deployed response contract, app feature or
new holdout. Source-authoritative audits of the same reused 20-case development
set produced these results; none is an Android/runtime or fresh-holdout score:

| v10 prototype | Schema-valid | Whole-basket source fidelity | Median / p95 model latency |
| --- | --- | --- | --- |
| Gemini 3.8 Flash, low | 20/20 | 18 pass, 2 fail | 4.836 / 5.703 s |
| GPT 6 Luna, none, returned fast tier | 20/20 | 13 pass, 6 fail, 1 uncertain | 3.274 / 4.469 s |
| DeepSeek Flash | 13/20 | Not qualified: seven schema failures | Not used as a selection gate |

Gemini lost one shared container requirement and omitted a neighbouring product
after an ambiguous correction. OpenAI returned all 214 items but changed two
dozen eggs into two eggs, lost other explicit requirements and added an unstated
packaging constraint. Exact quotations and complete item counts did not prevent
these errors. DeepSeek's failures involved invalid quantity or pack units. No
automatic retries or production model switch were made. Existing v1 clients
cannot consume the new shape and must never receive it silently.

A completed, repository-free Cursor consultation requested Grok 4.7, xhigh,
fast; the requested model is not independently attested by the returned text.
It recommended negotiated v2, explicit bounded evidence binding, primary-only
quantity/pack validation, and separation of requested pieces from retail-unit
limits. Supporting quotes must not be concatenated to manufacture a protected
claim or borrow another item's attributes. Its review was advisory, not release
approval; the completed agent was archived and that status was read back.

### Next implementation boundary: negotiated evidence, not a prompt hot-swap

This is an implementation plan, not functionality in version 21:

- Keep `POST /swiggy/intent` v1 as the default. A future explicit contract-version
  request plus a separate server kill switch must select v2; unrecognised or
  unavailable versions fail explicitly, never silently downgrade/strip fields.
  Provider instances/schema validation must be keyed by contract as well as
  model. The current auth, audience, deadline and cost gates apply unchanged.
- Make container form first-class. Preserve a short primary quote and bounded,
  separately typed supporting fragments with field ownership. Resolve exact
  character spans without first-occurrence guessing; reject blank, unbounded or
  ambiguous spans. A shared header may govern several variants, but must not
  copy another item's identity, size or restrictions. Do not concatenate
  fragments before protected-phrase checks. Character indexing must specify
  Unicode behavior consistently between Python and Kotlin.
- Validate numbers from item-local evidence with exact decimal/rational
  arithmetic and a closed numeral vocabulary. Dozen is a multiplier, nutrition
  grams are not pack size, and a total volume is not an invented one-pack order.
  Unsupported/corrected arithmetic remains unresolved in the combined review.
  Keep the full original list visible so an omitted neighbour is not silently
  treated as cancelled. A model's self-audit cannot prove item completeness.
- Separate requested physical pieces from purchasable SKU quantity. Twenty-four
  requested eggs can become four units only after the chosen catalogue variant
  proves six eggs per retail unit and division is exact. The cart line cap stays
  twenty retail units. Unknown pack count, non-divisibility or an over-cap result
  cannot be rounded or silently substituted.
- Update `GroceryIntentDraft`, grounding/pack checks, `SwiggyMcpClient` and the
  coordinator together. Packaging and supporting evidence must survive into
  strict catalogue constraints and one accessible combined confirmation, not
  merely appear in hidden JSON. Preserve cancellation/stale-callback guards.
- Qualification order: pure adversarial fixtures and v1 compatibility; actual
  Android replay with meaning checks; a newly frozen independent long-list
  holdout; read-only hosted catalogue comparison; signed build and review.
  Reject unsupported diet/quantity changes regardless of average score. The
  current 18/20 reused Gemini prototype cannot authorize this rollout.

### Latency investigation

The observed 20-item catalogue request took 51.108s while 26 provider HTTP calls
summed to 20.948s. The sum includes concurrent calls and is not elapsed wall time.
Credential reads, durable quota admission and queue waits were previously
unmeasured. Backend commit `8a797361341752419aa024cfd297568468717886` adds four
fixed-label, request-local timing aggregates: connection lookup, quota admission,
quota wait and discovery queue. No customer text, identifier, token, address,
query or response is included, and quota/concurrency/retry behavior is unchanged.
All 747 local tests plus 282 subtests passed; the only exclusion remains the two
legacy manual localhost-demo tests. The telemetry image built successfully and
passed its exact Python 3.9 image suite. Zero-traffic v12 canary verification
passed on the phone (one read-only diagnostic, 123.033s). The same 3/3, 11/15
and 14/20 items had compatible catalogue suggestions; exact-pack/count and
availability gaps remain. Catalogue times were 12.783s, 39.436s and 54.383s.
Ordinary v11 traffic remains unchanged at this point.

For the 20-item request, server wall time was 54.294s; 27 provider calls summed
to 22.776s, connection lookups to 0.390s, quota admission to 1.476s and explicit
quota waits to 3.002s. Queue waits summed to 103.490s because concurrent waits
overlap: these sums are NOT a critical-path decomposition. Credential/quota
overheads alone do not explain the wall time. Local profiling isolated repeated
normalization in all-pairs history enrichment. An equivalent request-local index
reduced a synthetic 20-live/150-history call from median 223.35ms to 14.98ms
(seven runs); independent output and 288 matcher-equivalence checks passed.
This is a microbenchmark, not yet proof of improved hosted end-to-end latency.

Claude consultation attempts for this post-submission work returned
`stop_sequence` without a substantive review. No Claude approval is claimed.

### Qualified history-index optimization — promoted separately

Backend commit `65615f31157e18294db5da035ea05421c05ef560` keeps the standalone
identity matcher unchanged. Only bulk history enrichment uses a request-local
index of the same exact normalized product name, complete label and pack
signatures. History ordering, live IDs, frequencies and recency are preserved;
no cross-request cache or customer data retention was introduced. Differential
tests use an independent copy of the original nested algorithm, with mutation,
variant, numeric identity, unknown-pack and input-isolation checks.

All 750 local tests plus 282 subtests passed. Cloud Build
`5677efb2-899a-4307-93ea-47a7b7761933` built image
`70c1cec0c1365060b6a8ac0b4d091ae142ce5aa46b4d94c989cebb0dbc66fb43`;
exact Python 3.9 image suite `16c73feb-50d4-4dda-89a6-abab26635b3f` succeeded.
The v13 zero-traffic phone diagnostic passed in 48.840s, versus 123.033s for
the preceding v12 diagnostic. These are individual runs, not latency percentiles.

| Read-only catalogue list | v12 elapsed | v13 elapsed | v13 compatible items |
| --- | --- | --- | --- |
| 3 items | 12.783 s | 3.227 s | 3/3 |
| 15 items | 39.436 s | 11.455 s | 10/15 (previously 11/15) |
| 20 items | 54.383 s | 17.518 s | 14/20 |

The 20-item provider HTTP sum was 22.083s versus 22.776s previously, while
server wall time fell from 54.294s to 17.418s. This supports a material backend
CPU improvement; it does not establish product availability or full-basket
correctness. Returned catalogue candidates varied between runs, including one
additional unresolved item in the 15-item run. The same item resolved in the
20-item run; no silent substitute was accepted. Catalogue and quantity gaps
are still unresolved, and no cart/order/payment mutation was performed.

GitHub backend main was read back at the commit above. Cloud Run v13 now has
100 percent ordinary traffic, with health OK and unchanged non-image runtime
configuration hash. V11 remains available for rollback. The model prompt, AI
audience, consent, quota and concurrency behavior are unchanged. The signed
Android v21 AAB remains byte-identical; no new Play bundle was required.

Post-promotion verification against the normal configured URL passed all 14
phone checks in 59.100s: thirteen synthetic UI checks and the read-only catalogue
diagnostic. Compatible items returned to 3/3, 11/15 and 14/20; catalogue elapsed
times were 3.283s, 11.622s and 17.609s. This repeat supports the speed improvement
and shows the earlier additional 15-item miss was not persistent. It does not
remove the original catalogue/count gaps or prove cart writes/payment.

Owner scheduling: stop tests at 22:30 IST on October 4 and, if unfinished,
resume at 09:00 IST October 5. Separate one-shot thread follow-ups were created
and read back. The goal remains incomplete and active before the stop time.
