# Grocery intent v2 — offline checkpoint, October 4

## Scope and release boundary

The backend main-folder implementation adds independent evidence, exact-amount,
and draft-contract modules. They are **not imported by any live route, provider,
or Android code**. The hosted service still uses v1 / intent-v8-oct04. This is a
tested foundation, not an enabled v2 experience or a release-quality claim.
The signed Android v21 bundle and Play submission are unchanged.

Modules in `D:\Projects\beta backend`:

- `grocery_intent_evidence.py`: bounded, exact, unique word-aware primary and
  typed supporting quotes; UTF-16 offsets; non-overlapping item evidence;
  explicit shared-heading boundaries and conservative modifier ownership.
- `grocery_intent_quantities.py`: exact rational arithmetic, complete numeric
  atoms, matching physical units, compact `500g` spelling, and bounded
  count/measure/container grammar. Unknown or conflicting arithmetic is not
  rounded. Verified catalogue piece counts can convert logical pieces into
  retail packs only by exact division, with the existing 20-pack cart limit.
- `grocery_intent_amount_roles.py`: a closed-template guard against treating
  explicit packet contents as purchased totals, reusing one amount for two
  fields, or treating an explicit total/nutrition amount as a pack size.
  Unknown wording, alternatives and multiple container mentions stay unresolved.
- `grocery_intent_v2.py`: strict, separate `grocery-intent-v2` draft shape with
  packaging, typed supporting evidence, and numeric quote fields. Count/pack
  stays capped at 20; requested pieces are bounded separately at 1,000. This
  larger input bound is not permission to add 1,000 retail units.

Only `validate_basket_v2(instruction, payload)` is the untrusted model-payload
entry point. Directly constructing or parsing the `BasketIntentV2` wire shape
does **not** perform factory-level quote/arithmetic validation. Future provider
and route-envelope integration must use the factory and test that boundary.
No v1 fields, validation, request formats, or response formats changed.

## What validation proves — and does not

It checks structure, exact source locations, conservative quote attachment,
complete amount arithmetic and literal unit consistency. Regression examples
include cropped `two dozen`, `twenty-five`, decimal/fraction separators,
`20 plus 5`, same-anchor remote modifiers, and headings crossing another group.
It distinguishes standalone `one` from the letters inside `honey`, without
allowing ambiguous repeated standalone numeric evidence.

It does **not** prove product meaning, dietary preservation, complete product
coverage, or every number's role (purchased total versus per-pack contents versus
nutrition). Packaging is currently a preserved, typed field, not a semantic
proof. Model-inferred counts without literal numeric evidence are rejected.
These limits must not be hidden by a high JSON-validity score or combined review.

## Verification

- Focused evidence/quantity/amount-role/v2 suite: 135 tests and 163 subtests passed.
- Full local backend suite: 885 tests and 445 subtests passed; only the existing
  two manual localhost-demo tests were excluded. Existing deprecation warnings
  and a legacy return-value warning remain; these are not new v2 failures.
- An existing local Python 3.9.25 / Pydantic 2.13.4 container ran the 76
  quantity/amount-role/v2 unittest cases successfully, with network disabled and read-only
  source. This is not a newly built/deployed Cloud Run image. Its image has no
  pytest, so the pytest evidence suite was verified locally on Python 3.12.
- Independent read-only review reproduced two arithmetic/attachment defects;
  root fixed both and added tests. Integral discrete floats now normalize to
  integers without rounding. A subsequent review verified those reproductions
  closed. Additional attached/spaced numeric-quote ambiguity was also fixed.
- The initial foundation Claude consultation returned no substantive review. The Cursor fallback
  likewise returned an acknowledgement rather than a review; its remote agent
  was archived and read back as archived. Neither is counted as approval.

### Later amount-role review and verification

The subsequent Claude Opus consultation did return a substantive review of the
new amount-role guard. Claude and an independent code reviewer both reproduced
a false rejection of ordinary products such as sugar and protein powder.
The fix requires an explicit nutrition denominator (such as `per serving`),
not a bare nutrient-like product name. Added factory regressions pass, including
a non-first-item offset test. Final independent re-review found no new material
issue in this limited fix; Claude approved offline use with stated cautions.

This guard does not scan for omitted numeric fields or prove arbitrary roles.
`matched` covers only supplied fields in its closed grammar. The factory
currently uses raised contradictions only: the helper's unresolved status is
**not yet carried into review metadata**. Before any v2 route/Android rollout,
the adapter must preserve these unresolved outcomes as non-blocking review rows,
handle supporting-text corrections, and never equate generic review with
semantic correctness. Do not set the current Android hard-clarification flag
for every unfamiliar phrase. No v1 format or live behavior changed.

Captured-response replay after the guard remains 16/20 contract-valid and
15/20 at the semantic/contract intersection; no fresh provider requests were
needed. The known pack-role error is already in a contract-rejected basket,
so the new safety check does not inflate the measured basket score.

## Actual-format development experiment

Twenty serial Gemini 3.8 Flash / low calls used the v11 exact-evidence prompt and
typed supporting-quote schema. This reuses the already-examined evening set:
it is **not a fresh holdout**. All 20 returned schema-valid JSON. Model latency
median 5.102 seconds, nearest-rank p95 6.250 seconds, maximum 8.031 seconds.

After corrections to false-rejecting boundary checks, 16/20 outputs passed the
offline contract. An independent source-semantic audit rated 18/20 acceptable;
the intersection was 15/20. One otherwise structurally valid output dropped a
size descriptor. Another output confused retail packet contents with purchased
quantity. Four contract rejections involved unsupported remote attachment,
an inferred count without a numeric quote, or ambiguous amount roles. A
singular-container case also has a gold-versus-interpretation discrepancy;
the strict contract still rejects its unsupported explicit count.

Private prompts, raw model outputs, gold, and detailed audits remain under the
existing ignored `logs/model_benchmark_oct03` folder. No customer text or raw
fixtures belong in public release notes, analytics, or the Drive handoff.

## Next gates

1. Resolve numeric field-role validation and retention of forms/descriptors;
   preserve unresolved rows for one combined editable review, never silent
   deletion, invented quantities, or per-item interrogation by default.
2. Add explicit v2 negotiation and a disabled server switch; retain v1 unchanged.
   Update the Android reader/mapping and existing single-review journey before
   enabling any request to use v2. No automatic downgrade by stripping fields.
3. Qualify the actual pipeline on a newly frozen independent 15–20-item holdout,
   then safe hosted/phone catalogue checks. Require no known silent quantity or
   dietary errors. Development scores do not authorize widening the audience.

Owner cutoff is 22:30 IST October 4; the unfinished goal must pause then with a
clean checkpoint. The scheduled restart is 09:00 IST October 5. No new cart,
address, checkout, payment, or order mutations were performed in this work.
