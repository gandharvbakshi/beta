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

## Release record (pending)

Candidate: 0.3.4 / versionCode21. No GitHub default-branch push or Play v21
publication is established by this document yet. Final signed bundle hash,
complete test counts, Cloud revision/traffic and live Play/Git readback must be
added after the actual actions. Public privacy is live and verified; Photos-only
Data Safety correction was published and Console readback showed 0/2 selected.
Precise location remains declared for possible Android Geocoder network use.
