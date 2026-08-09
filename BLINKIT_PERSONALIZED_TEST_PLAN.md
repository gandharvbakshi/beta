# Blinkit Personalized Reliability Plan

## Goal and boundary

Validate Beta against the user's de-identified 6-12 month Blinkit ordering patterns. Automation stops after search, exact product selection, add-to-cart, and cart-increment verification. It must never open checkout, proceed to payment, or place an order.

The same historical baskets must be expressible with minimal prompts: bare category words such as `mints`, `aata`, `bhindi`, `juice`, or `chips`, without requiring an `order` verb or full product title.

The existing Blinkit cart is user state. Record its baseline before each case, preserve every pre-existing item, and roll back only increments introduced by that test.

## Profile-derived coverage

Representative historical baskets contain 1-7 distinct product lines and commonly include quantities of 2-3. The category mix includes produce sold by piece or weight, chilled/frozen goods, beverages, cough/digestive products, confectionery, stationery, party/disposable supplies, and 2x/3x/6x multipacks. Important ambiguity cases are single-unit versus multipack variants and several visually similar products from the same brand.

Print and service orders are out of scope. Raw history screenshots, dates, addresses, payment references, filenames, and other private fields must not be retained in repo artifacts.

## Personalized preference contract

- Store user-specific shorthand on-device in `PreferenceStore`/private `SharedPreferences`.
- Resolve preferences locally before the first backend call; do not add a model or network round trip.
- Seed `mints` to `Impact Sugar Free Mint Candies Strong Mints` at confidence 1.0.
- Preserve requested quantity: `2 mints` becomes `2 Impact Sugar Free Mint Candies Strong Mints`.
- Apply a preference only to the exact normalized shorthand. An explicit request such as `fresh mints`, another brand, or a named pack size must bypass the generic `mints` preference.
- Preferences must remain editable/forgettable and must not silently learn from a single uncertain outcome.
- Seed alternate spellings such as `aata` and `atta` as separate exact tokens when both should resolve to the same history-backed product.
- Do not invent a brand, flavour, size, or pack preference for a broad category. Until history supports one stable choice, keep the category generic and require target-local evidence or an explicit safe failure.
- Support batch seeding from a private local profile file; keep that file out of source control and never log raw order history.

## Gates and execution order

1. Verify hosted Cloud Run `/health` and build the Android debug app with the hosted backend URL/key.
2. Confirm the physical phone, Beta accessibility service, whole-screen MediaProjection, overlay permission, Blinkit selection, and anti-sleep settings.
3. Record the non-empty-cart baseline.
4. Run the smallest test first. Expand only after the preceding class passes.
5. For every case, capture `ITEM_RESULT`, `ORDER_RESULT`, target-local cart evidence, elapsed time, and rollback result.
6. Stop immediately on checkout/payment UI, target ambiguity, loss of capture/accessibility, or a cart mutation that cannot be attributed to the test.

## Functional matrix

| ID | Case | Coverage | Pass criteria |
| --- | --- | --- | --- |
| P00 | Hosted/preflight | Cloud health, hosted URL/key, phone services | Healthy hosted backend and all required phone services active |
| P01 | Existing-cart baseline | Start with 2 unrelated cart items | Existing items remain; only the target increments; rollback returns exactly to baseline |
| P02 | Single exact item | One personalized packaged/pharmacy item | One exact target increment and explicit success |
| P03 | Quantity shorthand | `2 mints` | Local exact-SKU rewrite, quantity 2, no other mint variant |
| P04 | Explicit override | A named different mint flavour/brand | Generic shorthand preference is not applied |
| P05 | Mixed 3 | Produce + chilled/frozen + packaged | Three intended lines, correct unit interpretation |
| P06 | Historical-shape 6 | Produce, pharmacy, stationery, packaged goods; one quantity 2 | Six lines complete without dropped/duplicated items |
| P07 | Historical-shape 7 | Broad basket including multipack and fresh goods | Seven lines complete in order with exact per-item outcomes |
| P08 | Similar SKU | Same brand single versus multipack | Requested form factor selected; otherwise explicit safe failure |
| P09 | Quantity/pack ambiguity | Pieces, weight, and 2x/3x/6x packs | Requested count/measure is retained; no silent unit substitution |
| P10 | Unavailable/stock-limited | Missing SKU or capped quantity | Bounded failure or explicit stock outcome; no unrelated substitute |
| P11 | Noisy instruction | Fillers, conjunctions, mild spelling noise | Same parsed items/quantities as clean input |
| P12 | Interruption/recovery | Background/foreground or interrupted search | Safe resume or explicit failure with no duplicate add |
| P13 | 12-item stress | Long mixed list | No fixed ten-minute premature timeout; every item accounted for |
| P14 | 20-item stress | Large mixed list | Bounded completion/failure, no global action-budget collision |
| P15 | 25-item stress | Upper personalized list target | Stable bounded execution under the 60-minute cap; no checkout |
| P16 | Privacy/cleanup | Evidence and temporary history data | Only de-identified results retained; temporary raw captures deleted |
| P17 | Bare category prompts | `mints`, `aata`, `bhindi`, `juice`, `chips` without an order verb | Each parses as one line and resolves locally when a history-backed preference exists |
| P18 | Minimal historical basket | A 6/7-line basket expressed only as category words and quantities | Same exact products/quantities as the corresponding historical basket; every line accounted for |
| P19 | Minimal long baskets | 12/20/25 category-word lines using the private preference profile | No dropped/merged lines, no per-item preference network call, and the same timeout/safety criteria as P13-P15 |

## Large-list timing policy

Each item retains its 120-second deadline plus a 15-second transition allowance. The sequence timeout is `itemCount * 135 seconds`, clamped to a minimum of 10 minutes and maximum of 60 minutes. The elapsed-time check and watchdog must use the same captured value. Expected budgets include 7 items = 15m45s, 12 = 27m, 20 = 45m, and 25 = 56m15s.

## Result requirements

A functional case passes only if `items_failed=0`, each requested item reports the expected `qty_requested` and `qty_added`, the exact target SKU/form factor is supported by tree/cart evidence, no pre-existing cart content changes, rollback returns to baseline, and no checkout/payment action occurs.

For a 12/20/25-item stress case, a bounded explicit failure is a safety pass but not a reliability pass. Beta cannot be called reliable for that size until every item succeeds and rollback is verified.

## Current execution record

| ID | Build/surface | Result | Evidence summary |
| --- | --- | --- | --- |
| P00 | Hosted health | Pass | Revision `beta-backend-staging-00068-9f8` serves 100% traffic; `/health` returned HTTP 200 with service status OK |
| P01/P02 | Installed Beta 0.2.10 + live Blinkit | Pass | One exact Vicks item added in about 24s; original 2 cart items preserved; test increment removed and baseline restored |
| P03 (generic query) | Installed Beta 0.2.10 | Fail safe | Exact mint product was visible but backend failed to pair the matching card with its own ADD; quantity 0, cart unchanged |
| P03 (preference) | Installed Beta 0.2.10 | Local rewrite pass / flow fail safe | `2 mints` rewrote locally to exact Strong Mints and retained quantity 2; same backend card-pairing defect prevented add; cart unchanged |
| P13-P15 policy | Current source | Unit pass | Dynamic 1/7/12/20/25/very-large timeout tests pass |
| P04 preference override | Current source | Unit pass | Explicit variant bypasses the exact `mints` shorthand preference |
| P03 (quantity execution) | Beta 0.2.13 + live Blinkit | App mutation pass / terminal verification fail | Exact Strong Mints reached quantity 2 and cart moved from 2 to 4; backend failed to associate the target card's visible stepper, then test increments were removed and baseline 2 restored |
| P03 verifier fix | Hosted revision 00068 | Unit/review/deploy pass; phone rerun pending | Strict target-local tree/OCR correlation, adjacent-row regressions, full 193-test suite, and independent review passed; wireless ADB must reconnect before live confirmation |
| P17 parser/profile path | Current source | Unit implementation in progress | Bare-category and long category-list regressions added; private batch-seed utility and live readback still required |

## Fix-to-live sequence

1. Deploy the tested backend card/ADD containment fix to hosted staging.
2. Build and install the current Android branch with hosted credentials, then restore Beta-only permissions/services and re-seed the local `mints` preference if uninstall cleared app data.
3. Re-run P03 and roll back exactly two mint increments.
4. Run P05-P12 in increasing order, fixing the first reproducible root cause before expanding.
5. Run P13-P15 only after small and historical-size baskets pass; keep per-test rollback and stop at cart verification.
6. Run final Android/backend suites and an Opus-only high-effort adversarial review, then update the canonical Drive handoff.
