# Blinkit Personalized Reliability Plan

## Goal and boundary

Validate Beta against the user's de-identified 6-12 month Blinkit ordering patterns. Automation stops after search, exact product selection, add-to-cart, and cart-increment verification. It must never open checkout, proceed to payment, or place an order.

The same historical baskets must be expressible with minimal prompts: bare category words such as `mints`, `aata`, `bhindi`, `juice`, or `chips`, without requiring an `order` verb or full product title.

The existing Blinkit cart is user state. Record its baseline before each case, preserve every pre-existing item, and roll back only increments introduced by that test.

## Profile-derived coverage

The read-only trailing-12-month review covered 37 de-identified fulfillment cards. Representative baskets contain 1-11 distinct product lines and commonly include quantities of 2-4. The category mix includes produce sold by piece or weight, chilled/frozen goods, beverages, cough/digestive products, confectionery, stationery, party/disposable supplies, and 2x/3x/6x multipacks. Important ambiguity cases are single-unit versus multipack variants and several visually similar products from the same brand.

Print and service orders are out of scope. Raw history screenshots, dates, addresses, payment references, filenames, and other private fields must not be retained in repo artifacts.

## Personalized preference contract

- Store user-specific shorthand on-device in `PreferenceStore`/private `SharedPreferences`.
- Resolve preferences locally before the first backend call; do not add a model or network round trip.
- Seed `mints` and `mint` to `Impact Sugar Free Mint Candies Ice Mints` at confidence 1.0.
- Seed `aata` and `atta` to `Organic Tattva Multigrain Organic Atta 1 kg`.
- Seed `bhindi` to `Lady Finger 250 g`, `juice` to `Raw Pressery Valencia Orange Juice Pack of 2`, and `chips` to `To Be Honest Crispy Beetroot with Himalayan Rock Salt Chips`.
- Preserve requested quantity: `2 mints` becomes `2 Impact Sugar Free Mint Candies Ice Mints`.
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
| P07 | Historical-shape 11 | Largest observed basket shape, including multipack and fresh goods | Eleven lines complete in order with exact per-item outcomes |
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
| P18 | Minimal historical basket | An 11-line basket expressed only as category words and quantities | Same exact products/quantities as the corresponding historical basket; every line accounted for |
| P19 | Minimal long baskets | 12/20/25 category-word lines using the private preference profile | No dropped/merged lines, no per-item preference network call, and the same timeout/safety criteria as P13-P15 |

## Large-list timing policy

Each item retains its 120-second deadline plus a 15-second transition allowance. The sequence timeout is `itemCount * 135 seconds`, clamped to a minimum of 10 minutes and maximum of 60 minutes. The elapsed-time check and watchdog must use the same captured value. Expected budgets include 7 items = 15m45s, 12 = 27m, 20 = 45m, and 25 = 56m15s.

## Result requirements

A functional case passes only if `items_failed=0`, each requested item reports the expected `qty_requested` and `qty_added`, the exact target SKU/form factor is supported by tree/cart evidence, no pre-existing cart content changes, rollback returns to baseline, and no checkout/payment action occurs.

For a 12/20/25-item stress case, a bounded explicit failure is a safety pass but not a reliability pass. Beta cannot be called reliable for that size until every item succeeds and rollback is verified.

## Current execution record

| ID | Build/surface | Result | Evidence summary |
| --- | --- | --- | --- |
| P00 | Hosted health | Pass | Revision `beta-backend-staging-00070-kqf` serves 100% traffic; `/health` returned HTTP 200 with service status OK |
| P01/P02 | Installed Beta + live Blinkit | Pass | Original 2 cart items preserved; each completed test increment was removed and baseline restored |
| P03 (`mints`) | Installed Beta + live Blinkit | Pass | Bare shorthand rewrote locally to exact Ice Mints, added quantity 1, verified success, and rolled back from cart 3 to baseline 2 |
| P17 (`aata`, first run) | Installed Beta + live Blinkit | Fail safe / Android root cause fixed | Coordinate search click was displaced by the overlay and opened a category surface; quantity 0 and cart unchanged. Fresh accessibility search focus plus mandatory editable-field confirmation now passes unit/build verification |
| P17 (`aata`, second run) | Patched Android + hosted revision 00070 | Fail safe / backend root cause fixed in source | Correct search results showed the exact 1 kg product, but compact tree data omitted ADD geometry and the backend scrolled past it. Target-name/ADD column pairing and device-to-upload scaling now pass 199 Blinkit backend tests; live rerun pending deployment |
| P13-P15 policy | Current source | Unit pass | Dynamic 1/7/12/20/25/very-large timeout tests pass |
| P04 preference override | Current source | Unit pass | Explicit variant bypasses the exact `mints` shorthand preference |
| P03 (quantity execution) | Earlier build + live Blinkit | Pass after verifier fix | Exact mint reached quantity 2 and cart moved from 2 to 4; test increments were removed and baseline 2 restored |
| P17 parser/profile path | Current source and phone | Pass | Seven exact shorthand mappings were batch-seeded from a private gitignored profile and read locally before any backend request |

## Fix-to-live sequence

1. Deploy the tested target-name/ADD geometry fix to hosted staging and verify `/health` plus revision traffic.
2. Install the current Android APK with hosted credentials, restore Beta-only permissions/services if needed, and verify all seven local preferences remain seeded.
3. Re-run bare `aata`; verify the exact 1 kg target, then roll back its one increment to cart baseline 2.
4. Run bare `bhindi`, `juice`, and `chips` one at a time with exact target evidence and per-item rollback.
5. Run mixed 3/6/11-line historical-shape lists; fix the first reproducible root cause before expanding.
6. Run 12/20/25 stress lists only after the observed 11-line shape passes; stop at cart verification and preserve the original cart.
7. Run final Android/backend suites and an approved adversarial review, then update the canonical Drive handoff.
