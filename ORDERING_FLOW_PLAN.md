# Beta Ordering Flow — Multi-Phase Implementation & Test Plan

This brief is written for Codex (or any coding agent) to execute end-to-end. It
extends the existing prototype that today reliably handles single-product
ordering ("order butter", "order pencil", "order apples") into a robust,
context-aware ordering assistant for Blinkit.

It assumes the current architecture:

- **Android app** (`com.example.beta`) — `MainActivity`, `ScreenCaptureService`
  (overlay + screenshot pipeline), `MyAccessibilityService` (tree capture +
  gesture dispatch), `ActionExecutor`, `BackendProcessing`,
  `AutomationInstructionReceiver` (broadcast entry point used by tests/scripts).
- **Backend** (`D:/Projects/beta backend`, FastAPI on `https://10.0.2.2:8000`)
  — `app.py`, `Screenshot_experiments.py`. Returns recommended actions per
  screenshot + tree, plus session/workflow/verification fields.
- **Test infra** — `app/src/androidTest/.../BlinkitOrderingFlowTest.kt`,
  `scripts/run_blinkit_flow_test.ps1` (parameterised by `-Instruction`).
- **Companion brief** — `codex_android_blinkit_test_automation.md` (test
  harness conventions). **Robustness backlog** — `DEBUG_NOTES_FOR_CODEX.md`
  (R1–R15 are absorbed into Phase 1 below).

Universal **safety boundary** (do not violate in any phase):

- Never tap "Place order", "Pay", "Proceed to checkout", "Pay now",
  "Continue to payment", any UPI/card/wallet sheet, or any address-edit save.
- All instrumented tests stop at "cart contains the right items in the right
  quantities, ready for human review".
- After every test, call `Reset-BlinkitCart` (already in
  `scripts/run_blinkit_flow_test.ps1`) so the emulator is left clean.

Conventions used throughout this doc:

- **INTENT** sections state the goal in one paragraph, in plain English, so
  Codex can pattern-match cleanly.
- **WORK** sections list concrete code changes by file/area.
- **ACCEPTANCE** sections list the observable signals (logs / status overlay /
  cart state) that must be true.
- **TEST PLAN** sections list automated tests, scripted scenarios, and a
  manual smoke matrix.
- All log lines use the `BetaAgent` tag and uppercase event names so existing
  `Wait-ForLog` / `Wait-ForFlowOutcome` matchers in PowerShell continue to work.

---

## Phase 0 — Shared Infrastructure (do this first, once)

### INTENT

Before adding new product/cart behaviour, lock down two things every later phase
depends on: a **deterministic test fixture set** (a pinned list of probe
products) and a **structured, machine-readable per-item outcome log** so any
flow — single, multi, or quantified — can be verified by reading logcat alone,
without screenshotting.

### WORK

1. **Test fixture file**
   - New file: `scripts/test_fixtures/blinkit_probe_products.json`.
   - Schema:
     ```json
     {
       "in_stock_common": ["amul butter", "apple", "notebook", "pencil"],
       "in_stock_variant_heavy": ["pepsi", "lays", "maggi"],
       "out_of_stock_likely": ["raw mango (off-season)", "fresh ber"],
       "weight_packed": [
         { "query": "bhindi", "common_packs_g": [200, 250, 500] },
         { "query": "tomato", "common_packs_g": [500, 1000] }
       ],
       "count_packed": [
         { "query": "apple", "common_pack_count": 4 },
         { "query": "egg", "common_pack_count": 6 }
       ]
     }
     ```
   - Treat this file as the **only** place to update probe product names; all
     scripts and instrumented tests load from here.

2. **Per-item outcome log line**
   - In `BackendProcessing.kt`, when a single item finishes (success, oos,
     low-confidence, max-steps, etc.), emit exactly one line:
     ```
     ITEM_RESULT item="<query>" status=<success|oos|not_found|misclick|skipped|low_confidence|timeout> matched_sku="<title or empty>" qty_requested=<n> qty_added=<n> notes="<short>"
     ```
   - Also surface this in the overlay status as `STATE: ITEM_RESULT (<item>: <status>)`.

3. **Per-order outcome log line**
   - At end of an order (single or multi), emit:
     ```
     ORDER_RESULT items_total=<n> items_succeeded=<n> items_failed=<n> failures="apple:oos;notebook:not_found"
     ```
   - And `STATE: ORDER_DONE` on the overlay.

4. **Test runner upgrade**
   - `scripts/run_blinkit_flow_test.ps1` already wraps a single instruction.
     Add a sibling: `scripts/run_blinkit_matrix.ps1` that:
     - takes `-Scenario <single|multi-clean|multi-noisy|quantity|context>`,
     - reads probe products from `blinkit_probe_products.json`,
     - loops over scenarios, calls `run_blinkit_flow_test.ps1` per instruction,
     - parses the new `ORDER_RESULT` line and writes a CSV row per run to
       `logs/matrix_<scenario>_<timestamp>.csv` with columns
       `instruction,items_total,items_succeeded,items_failed,duration_s,failures`.
   - This CSV is the artifact every later phase reports against.

### ACCEPTANCE

- `adb logcat -d | Select-String "ITEM_RESULT|ORDER_RESULT"` returns at least
  one of each line for every order.
- `scripts/run_blinkit_matrix.ps1 -Scenario single` produces a CSV with one
  row per probe item, all columns populated.

### TEST PLAN (Phase 0)

- **Unit (Android)**: a JVM unit test in `app/src/test/...` that constructs a
  fake `OrderOutcome` and asserts the exact log/overlay strings (regex on the
  format above). This guards the contract that PowerShell relies on.
- **Smoke**: rerun the existing `BlinkitOrderingFlowTest.orderButterStopsAtCartVerification`.
  It must still pass and additionally produce one `ITEM_RESULT` line and one
  `ORDER_RESULT` line.
- **Matrix**: `scripts/run_blinkit_matrix.ps1 -Scenario single` over the four
  `in_stock_common` items. Expect 4/4 success rows in the CSV.

---

## Phase 1 — Single-Item Robustness (incl. Out-Of-Stock)

### INTENT

Today `order butter`, `order pencil`, `order apples` work, but the flow has
not been stress-tested. The goal of Phase 1 is to make a single-item order
succeed (or fail loudly with the right reason) for any reasonable Blinkit SKU,
including products that **are not in stock**, products with confusing
sponsored cards, products that are already in cart, products inside a flavour
modal, and products gated behind a "Notify me" wall. No multi-item, no
quantities, no preferences yet — just rock-solid single-item.

### WORK (Android)

1. **Out-of-stock detection** in `ActionExecutor` /
   `BackendProcessing.processScreenshotWithInput`:
   - When the chosen card's accessibility/OCR text contains any of
     `Out of stock`, `Sold out`, `Notify Me`, `Currently unavailable`,
     skip the card. Do **not** tap "Notify Me".
   - If every visible card for the query is OOS, emit
     `ITEM_RESULT status=oos` and stop. Surface
     `STATE: FAILED reason=out_of_stock` on overlay.

2. **Already-in-cart detection** (R4 from `DEBUG_NOTES_FOR_CODEX.md`):
   - If the chosen card shows the `−  N  +` stepper instead of `ADD`,
     treat as `status=success` with `notes="already_in_cart"` and skip
     to "View cart" verification.

3. **Sponsored / wrong-brand guard** (R1):
   - Backend already has `target_visible_in_words`. Add a card-bounded check:
     the matched product's leading title token must start with the user's
     query token (case-insensitive). If the card has the word `Sponsored`
     near the top, skip it unless no other candidate exists.

4. **Variant modal disambiguation** (R6):
   - When `ui_state == "modal"` (e.g. Pepsi → Pepsi/Pepsi Black/Diet/Zero),
     prefer the variant whose title equals the query exactly. On tie,
     emit `STATE: NEEDS_USER_CHOICE` and stop with `status=needs_user_choice`.

5. **Cart-content verification** (R9):
   - Existing flow already checks `verification_status.item_found_in_cart`.
     Tighten it: also require that the cart line text contains the original
     query token (case-insensitive). If not, downgrade to
     `status=misclick` and try the next candidate (max one retry per item).

6. **Per-action confidence policy** (R11):
   - Lower the floor for `ADD` taps to 0.55, keep `type/scroll` at 0.70,
     keep `checkout-finalize` at 0.85 (we never run those, but be defensive).

### WORK (Backend)

- In `Screenshot_experiments.py`, when scoring candidate cards, return an
  extra structured field per response:
  ```json
  "candidate_cards": [
    { "title": "...", "is_sponsored": true, "is_oos": false,
      "stepper_visible": false, "bbox": [x,y,w,h], "score": 0.83 }
  ]
  ```
  Android does not have to consume this immediately, but it lets the matrix
  test script verify "we saw 3 candidates, picked card 1, it was not OOS".

### ACCEPTANCE

- `ITEM_RESULT status=oos` is emitted for an out-of-stock probe.
- `ITEM_RESULT status=success notes="already_in_cart"` is emitted when an
  item is already in the cart from a prior run.
- For every candidate-rich query (`pepsi`, `lays`, `maggi`), the chosen SKU
  passes the cart-text-must-contain-query check.

### TEST PLAN (Phase 1)

#### Instrumented tests (`app/src/androidTest/...`)

Add a parameterised test class
`BlinkitSingleItemRobustnessTest` that drives one product per parameter
through `submitInstructionFromOverlay`:

```kotlin
@RunWith(Parameterized::class)
class BlinkitSingleItemRobustnessTest(private val probe: Probe) { ... }
```

Probes:

| Probe                | Expected `status`            | Why this probe          |
|----------------------|------------------------------|-------------------------|
| `amul butter`        | `success`                    | smoke                   |
| `pencil`             | `success`                    | non-grocery             |
| `apple`              | `success`                    | count-packed            |
| `pepsi`              | `success`, exact-variant     | variant modal           |
| `maggi`              | `success`                    | many cards              |
| `raw mango`          | `oos` OR `not_found`         | likely off-season       |
| `aijdhqweh`          | `not_found`                  | nonsense token          |
| `pencil` (already in cart) | `success notes=already_in_cart` | stepper path |

Each probe asserts:

- `ITEM_RESULT` line matches expected `status`.
- Final overlay reaches `STATE: ORDER_DONE`.
- `Wait-ForBackendCartVerified` returns true **only** when `status=success`.

#### Scripted scenarios

```powershell
.\scripts\run_blinkit_matrix.ps1 -Scenario single
```

Pass criteria: ≥ 6/8 probes match expected status, with exactly the right
failure reason on the failing 2.

#### Manual smoke (one pass before merging Phase 1)

1. Fresh install + fresh launch.
2. Run probe `amul butter`. Confirm `STATE: SUCCESS`, "View cart" badge = 1.
3. Run probe `amul butter` again. Expect `notes="already_in_cart"`.
4. Run probe `raw mango`. Expect overlay `STATE: FAILED reason=out_of_stock`.

---

## Phase 2 — Multi-Item Deterministic Orders

### INTENT

Once single-item is solid, accept well-formed comma-separated lists like
`order butter, apple, notebook` and process them sequentially. We assume the
text is **clean** — comma-separated, no fillers, no quantities yet. Each
item should run through the same Phase 1 pipeline and we emit one
`ITEM_RESULT` per item plus a single `ORDER_RESULT`.

### WORK (Android)

1. **Instruction parser** — new file `app/src/main/java/com/example/beta/InstructionParser.kt`:
   - Pure Kotlin (no Android deps), JVM-testable.
   - Strips a leading `order ` / `buy ` / `add ` / `get me ` verb.
   - Splits on `,`.
   - Returns `List<ParsedItem>` where for Phase 2:
     ```kotlin
     data class ParsedItem(
         val rawText: String,
         val query: String,
         val quantity: Quantity = Quantity.Default
     )
     sealed class Quantity {
         object Default : Quantity()                  // 1 unit, "as available"
         data class Count(val n: Int) : Quantity()    // 6 apples
         data class Weight(val grams: Int) : Quantity() // 500g bhindi
     }
     ```
   - Phase 2 only emits `Quantity.Default`. The `Count`/`Weight` parsers
     come in Phase 4 — keep the data class so we don't refactor later.

2. **Sequencer** — extend `BackendProcessing`:
   - Add `startMultiItemSequence(context, parsedItems)`.
   - Loops items in order. For each item, calls the existing
     `startActionSequence` with `query` as the input text. Waits for
     `ITEM_RESULT` (success or terminal failure) before starting the next
     item. Caps total duration at 10 min for the order.
   - Between items: navigate back to Blinkit Home/Search (already handled
     by `Ensure-BlinkitHomeScreen` on the script side; mirror it on-device
     by clearing the search field and pressing back once).
   - Emits `ORDER_RESULT` at the end.

3. **Receiver compatibility**:
   - `AutomationInstructionReceiver` already passes the raw instruction
     string into `submitAutomationInstruction(...)`. Inside
     `ScreenCaptureService.submitInstruction`, branch on
     `InstructionParser.parse(text).size > 1` to call
     `startMultiItemSequence` instead of `startActionSequence`.

### WORK (Backend)

- No changes required for Phase 2. Each item is a separate session as far
  as the backend is concerned. Optionally, if a `multi_order_id` is sent,
  group the runs in `debug_runs/` for easier analysis.

### ACCEPTANCE

- For `order butter, apple, notebook`, logcat contains 3 `ITEM_RESULT`
  lines and 1 `ORDER_RESULT items_total=3 items_succeeded=3 items_failed=0`.
- Cart UI shows all 3 distinct line items.
- A failure on any single item does not abort the rest of the order.

### TEST PLAN (Phase 2)

#### Unit (JVM, fast)

`InstructionParserTest.kt` (pure JUnit, lives in `app/src/test/...`):

| Input                                       | Expected items                          |
|---------------------------------------------|-----------------------------------------|
| `order butter, apple, notebook`             | `[butter, apple, notebook]`             |
| `Order Butter , Apple , Notebook`           | trimmed + lowercased same as above      |
| `buy butter,apple`                          | `[butter, apple]`                       |
| `order butter`                              | `[butter]`                              |
| `   ` / empty                               | `[]` and a clear error log              |

#### Instrumented

New test class `BlinkitMultiItemCleanTest` with three parameter sets:

1. `order butter, apple, notebook` — all in stock → 3/3 success.
2. `order butter, raw mango, notebook` — middle OOS → 2 success + 1 oos,
   `ORDER_RESULT items_failed=1`.
3. `order butter, aijdhqweh, notebook` — middle nonsense → 2 success + 1
   not_found, order continues.

All three assert:

- 3 `ITEM_RESULT` lines, in input order.
- Exactly 1 `ORDER_RESULT` line.
- Cart line items match the successful subset.
- Test ends inside the cart screen, **not** on any payment screen.

#### Scripted

```powershell
.\scripts\run_blinkit_matrix.ps1 -Scenario multi-clean
```

Includes the 3 parameter sets above plus a longer
`order butter, apple, notebook, lays, pencil` 5-item run for soak.

#### Manual smoke

Run the 5-item order on a fresh emulator boot. Expected:

- Each item visibly moves through `STATE: SEARCHING → ADDING_TO_CART → ITEM_RESULT`.
- Final `STATE: ORDER_DONE` shows on overlay; cart contains 5 lines.

---

## Phase 3 — Loose / Poorly-Typed Multi-Item Orders

### INTENT

Real users say `butter , and apple, and maybe notebook`,
`get me some butter & apples plus 1 notebook please`, or
`order butter apple and notebook` (no commas at all). Phase 3 makes the
parser robust to these without changing the executor. The executor still
receives a clean `List<ParsedItem>` from Phase 2.

### WORK (Android)

Extend `InstructionParser` (still pure Kotlin, JVM-testable) with:

1. **Verb stripping**: leading words from a small set
   `{order, buy, add, get, get me, please, fetch, bring, pick up}`.
2. **Filler stripping**: `{and, then, also, plus, &, with, some, a, an, the,
   maybe, perhaps, please, kindly, of, for me}` removed when they appear
   between item tokens.
3. **Splitting strategy** (in order):
   - Split on `,` and `;` and ` & ` and ` and ` and ` plus `.
   - For each segment, strip leading/trailing fillers.
   - If a segment still has multiple known product nouns (Phase 5 will
     give us a small dictionary), split greedily; otherwise keep it as one
     query.
4. **Confidence**: each `ParsedItem` gets a `parserConfidence: Float`
   between 0 and 1. Low confidence (< 0.6) is logged with
   `BetaAgent` tag `PARSER_LOW_CONFIDENCE` and surfaced on the overlay
   before execution begins.
5. **Disambiguation prompt** (optional, but cheap to add): if
   `parserConfidence < 0.4` for any item, the overlay shows
   `PARSED: [butter, apple, notebook] — proceed?` for 5 seconds with a
   "Cancel" tap target. Default = proceed (so test scripts don't stall).

### WORK (Backend)

- Backend stays unchanged. Parser is on-device.

### ACCEPTANCE

- Loose inputs produce the same `ParsedItem` list as the clean equivalent.
- Each `ITEM_RESULT` line includes `notes="parser_conf=0.xx"`.
- A `PARSED:` debug log line precedes execution for every multi-item order.

### TEST PLAN (Phase 3)

#### Unit (JVM, fast — this is where most coverage lives)

`InstructionParserNoisyTest.kt` table:

| Input                                                | Expected items              |
|------------------------------------------------------|-----------------------------|
| `butter , and apple, and maybe notebook`             | `[butter, apple, notebook]` |
| `order butter apple and notebook`                    | `[butter, apple, notebook]` |
| `get me some butter & apples plus 1 notebook please` | `[butter, apples, notebook]`(qty parsing belongs to Phase 4 — Phase 3 keeps `Quantity.Default`) |
| `please buy   butter,,, apple ;; notebook`           | `[butter, apple, notebook]` |
| `butter and butter`                                  | `[butter]` (dedupe)         |
| `i want nothing`                                     | `[]`                        |
| `order` (verb only)                                  | `[]`                        |

Coverage target: ≥ 95% of `InstructionParser.kt` lines.

#### Instrumented

`BlinkitMultiItemNoisyTest` runs the three top noisy inputs above through
the full pipeline. Assertions are identical to Phase 2 plus:

- The `PARSED:` log line is present and lists the same items as the
  unit-test expectation.

#### Scripted

```powershell
.\scripts\run_blinkit_matrix.ps1 -Scenario multi-noisy
```

#### Manual smoke

Type each of the 3 noisy inputs into the overlay (not via broadcast) and
confirm the same outcomes as Phase 2 multi-clean.

---

## Phase 4 — Quantities, Pack Sizes, and Smart Substitution

### INTENT

Now we want `500 gms bhindi, 2 butter, 6 apples`. This is the first phase
that cannot be solved by parsing alone. The agent must (a) understand
desired quantity, (b) read the available pack sizes from the product card,
(c) decide how many packs to add, (d) optionally substitute when the exact
quantity is not orderable. Example: user wants 500 g bhindi; only 200 g
packs are visible → add 3 packs (= 600 g) and log the over-supply, OR add
2 packs (= 400 g) and log the short-supply, depending on a configured
**rounding policy** (default: `round_up_if_within_25_percent`, else
`round_down`).

### WORK (Android)

1. **Extend `InstructionParser`** to parse quantity tokens before each item:
   - Counts: `\b(\d+)\s+(?=\w)` → `Quantity.Count(n)`.
   - Weights: `\b(\d+(?:\.\d+)?)\s*(g|gm|gms|gram|grams|kg|kgs)\b` →
     `Quantity.Weight(grams=...)`.
   - Volumes: `\b(\d+(?:\.\d+)?)\s*(ml|l|ltr|liter|litre)\b` →
     `Quantity.Volume(ml=...)` (new sealed-class member).
   - Quantity always binds to the **next** noun in the segment, not the
     previous one.

2. **Pack-size extractor** in `BackendProcessing` (post-search step):
   - From each candidate card's title/subtitle, regex out
     `(\d+(?:\.\d+)?)\s*(g|kg|ml|l|piece|pieces|pcs|pack of (\d+))`.
   - Build `PackOption(unitGrams: Int?, unitMl: Int?, unitCount: Int?, priceMinor: Int?)`.

3. **Pack solver** — new file `app/src/main/java/com/example/beta/PackSolver.kt`
   (pure Kotlin, JVM-testable):
   - Input: desired `Quantity`, available `List<PackOption>`,
     `RoundingPolicy`.
   - Output: `PackPlan(skuIndex: Int, packsToAdd: Int, deliveredQuantity: Quantity, deviationPct: Float, rationale: String)`.
   - Algorithm:
     - For each pack option, compute `packsNeeded` such that
       `packsNeeded * pack.unit ≥ desired`.
     - Compute deviation = `(delivered - desired) / desired`.
     - Score each option by `(deviationAbs, packsNeeded, priceMinor)`,
       lowest first.
     - If best deviation > policy threshold and any option allows
       `round_down` within threshold, pick that instead.
     - Return the top option with a human-readable `rationale`, e.g.
       `"500g requested; only 200g packs available; ordering 3 packs (=600g, +20%) per round_up_if_within_25_percent"`.

4. **Executor**:
   - For `Count(n)`, tap `ADD` once then tap `+` `n-1` times on the same
     card.
   - For `Weight`/`Volume`, run the solver to get `packsToAdd`, then tap
     `ADD` once + `+` `packsToAdd - 1` times on the chosen card.
   - After every `+` tap, re-read the stepper to confirm the count
     incremented by exactly 1; if not, log `STEPPER_DRIFT` and stop with
     `status=quantity_partial`.

5. **`ITEM_RESULT` line gains 3 fields** for this phase:
   - `qty_requested="500g" qty_added="600g" deviation_pct=20.0 rationale="..."`.

### WORK (Backend)

- Add `pack_options` to the `candidate_cards` block introduced in Phase 1
  (one `pack_options` array per card). This keeps the on-device regex
  simple — Android can prefer the backend-extracted options when present.
- Optional: a `policy_hint` in the response (`"prefer_round_up"` /
  `"prefer_round_down"`) so the backend can override the default per-product
  family later.

### ACCEPTANCE

- `2 butter` → 1 SKU with stepper showing 2.
- `6 apples` (when packs come in 4) → 2 packs, `deliveredQuantity=8`,
  `deviation_pct=33.3`, `rationale` mentions pack of 4. (Or 1 pack with
  short-supply if rounding-down policy is in effect — either is OK as
  long as it is **logged**.)
- `500 g bhindi` (when only 200 g packs are visible) → 3 packs,
  `deliveredQuantity=600g`, `deviation_pct=20.0`, rationale logged.
- Stepper drift → `status=quantity_partial`, order continues to the next
  item.

### TEST PLAN (Phase 4)

#### Unit (JVM, fast)

`PackSolverTest.kt` table (representative slice):

| Desired   | Available packs                      | Policy      | Expected plan                              |
|-----------|--------------------------------------|-------------|--------------------------------------------|
| `500g`    | `[200g, 250g, 500g]`                 | round_up_25 | 1× 500g                                    |
| `500g`    | `[200g, 250g]`                       | round_up_25 | 2× 250g (=500g, dev 0)                     |
| `500g`    | `[200g]`                             | round_up_25 | 3× 200g (=600g, dev +20)                   |
| `500g`    | `[200g]`                             | round_down  | 2× 200g (=400g, dev -20)                   |
| `6 count` | `[pack of 4]`                        | round_up_25 | 2× pack-of-4 (=8, dev +33)                 |
| `6 count` | `[pack of 4, pack of 6]`             | round_up_25 | 1× pack-of-6                               |
| `1 ltr`   | `[500ml, 1l]`                        | round_up_25 | 1× 1l                                      |
| `2 butter`| `[100g, 500g]` (no pack count given) | round_up_25 | 2× whichever pack the parser maps to (treat `2 butter` as count → ignore weight packs and tap ADD twice on the chosen card) |

`InstructionParserQuantityTest.kt`:

| Input                                | Expected                                           |
|--------------------------------------|----------------------------------------------------|
| `500 gms bhindi, 2 butter, 6 apples` | `[Weight(500g) bhindi, Count(2) butter, Count(6) apples]` |
| `1kg tomato`                         | `[Weight(1000g) tomato]`                           |
| `2 ltr coke`                         | `[Volume(2000ml) coke]`                            |
| `500g bhindi 2 butter 6 apples`      | same as comma version (split on quantity prefix)   |

#### Instrumented

`BlinkitQuantityTest` (parameterised). Each parameter is `(instruction, expected per-item plan)`:

1. `2 butter` → 1 SKU, stepper=2.
2. `6 apples` → either 2× pack-of-4 or 1× pack-of-6 depending on what
   Blinkit shows; assertion is "stepper count × pack size ≥ 6 and deviation
   ≤ 35%".
3. `500 g bhindi` → assertion: "delivered weight in [400g, 750g] and
   `rationale` is non-empty".
4. `500 g bhindi, 2 butter, 6 apples` → 3 cart lines, all within deviation
   bounds, single `ORDER_RESULT items_failed=0`.

#### Scripted

```powershell
.\scripts\run_blinkit_matrix.ps1 -Scenario quantity
```

CSV columns extended for this scenario with
`item,qty_requested,qty_added,deviation_pct`.

#### Manual smoke

Run the 3-item quantity order. Open the cart and visually confirm:

- Bhindi line shows pack count × pack weight ≈ 500 g (±25%).
- Butter line shows quantity 2.
- Apples line shows the expected pack count.

**Do not** advance past the cart screen.

---

## Phase 5 — User Context and Preferences

### INTENT

Different users mean different things by the same word. For me, "butter"
means **unsalted butter**; "makhana" means **plain or salt-and-pepper**, never
peri-peri. Phase 5 introduces a small, on-device, per-user preference store
seeded from past order history (read once with explicit user consent) and
used to disambiguate Phase 1–4 product choices.

### WORK (Android)

1. **Preference store** — new file
   `app/src/main/java/com/example/beta/PreferenceStore.kt`:
   - Uses Room or DataStore (pick one — DataStore is simpler for a flat
     map). Schema:
     ```kotlin
     data class Preference(
         val token: String,            // "butter"
         val preferredPhrase: String,  // "unsalted butter"
         val avoidPhrases: List<String> = emptyList(), // ["salted"]
         val source: Source,           // SEEDED, USER_OVERRIDE, INFERRED
         val confidence: Float
     )
     ```
   - Stored in app-private storage. Never sent over network unless the
     user explicitly enables sync (out of scope for this phase).

2. **Order-history seeder** (manual, one-time, explicit user action):
   - New screen launched from a button in `MainActivity`:
     "Learn from my past orders". Tapping it:
     - Walks the Blinkit "My Orders" / "Reorder" screens via
       `MyAccessibilityService` (read-only — never taps reorder).
     - Extracts repeated SKU titles per token (e.g. token "butter" appears
       in 7 past orders, 6 of them as "Amul Butter Unsalted 500g").
     - Computes preferred phrase as the modal SKU title with stop-words
       removed.
     - Writes to `PreferenceStore` with `source=SEEDED, confidence=count/total`.
   - Surfaces a one-screen review UI before save, so the user can untick
     anything wrong. Nothing is saved without explicit "Save preferences"
     tap.

3. **Wiring into the executor**:
   - In `InstructionParser.postProcess`, after producing each `ParsedItem`,
     consult `PreferenceStore.lookup(item.query)`.
   - If a preference exists with `confidence ≥ 0.6`, replace
     `item.query` with `preferredPhrase` and store the original under
     `item.rawText` (already there). Log
     `PREFERENCE_APPLIED token="butter" -> "unsalted butter" conf=0.86`.
   - If `avoidPhrases` are present, pass them to the backend as a
     `disprefer_keywords` field; backend down-ranks matching cards.

4. **User override**:
   - If an order completes and the user later edits the cart line (we can
     detect this by re-reading the cart on the next session), capture the
     change as `source=USER_OVERRIDE, confidence=1.0`. (Detection is
     best-effort; this is a stretch goal for Phase 5.)

5. **Category ordering decision policy**:
   - For broad requests like `soft drink`, `chips`, or `biscuits`, resolve in
     this order: category match, representative flavor/category, largest or
     default brand, then visible Blinkit rating/review count.
   - If the match is still ambiguous, stop for a review choice with simple
     options like `Always ask me` or `Pick the best match`.
   - Keep the decision wording plain and reuse the same choice on later runs
     only when the user has explicitly selected it.

### WORK (Backend)

- Accept and honour an optional `disprefer_keywords: List[str]` field in
  `analyze-screenshot`. Down-rank candidate cards whose titles contain
  any keyword. Surface this in `candidate_cards[i].score_components` for
  debuggability.
- Optionally accept `prefer_phrase: str` and re-run search ranking with
  the preferred phrase as a tiebreaker.

### PRIVACY

- Preference store lives on-device in app-private storage. Never exported.
- Order history seeding requires an explicit on-screen consent dialog
  ("Beta will read your last 50 Blinkit orders to learn your preferences.
  Nothing leaves your device.") and a "Forget my preferences" button.
- No screenshots from order-history reading are uploaded to the backend.
  Only the derived `Preference` objects are kept on-device.

### ACCEPTANCE

- After seeding, `order butter` produces logcat
  `PREFERENCE_APPLIED token="butter" -> "unsalted butter" conf=...` and
  the matched cart line title contains `Unsalted`.
- After seeding, `order makhana` does not match any card whose title
  contains a flavour in the user's `avoidPhrases`.
- "Forget my preferences" empties the store; subsequent orders log
  `PREFERENCE_NONE token="butter"`.

### TEST PLAN (Phase 5)

#### Unit

`PreferenceStoreTest.kt`:

- Insert + lookup round-trip.
- `lookup` returns `null` for unknown tokens.
- Confidence threshold: `lookup("butter")` with stored `conf=0.4` returns
  null; `conf=0.7` returns the preference.
- "Forget" empties everything.

`PreferenceSeederTest.kt` (uses fixture accessibility-tree XML files
saved under `app/src/test/resources/blinkit_orders/*.xml`):

- 7 past butter orders, 6 with "Unsalted" → preferred phrase
  `"Amul Butter Unsalted"`, conf ≈ 0.86.
- 5 past makhana orders, 3 plain, 2 salt-and-pepper, 0 peri-peri →
  preferred = "Plain Makhana", avoid = `["peri-peri"]`.

#### Instrumented

`BlinkitPreferenceTest`:

- **Scenario A — preference applied**: pre-seed
  `Preference(token="butter", preferredPhrase="unsalted butter", conf=0.9)`
  via a test-only API (`PreferenceStore.testSeed`). Run `order butter`.
  Assert cart line title contains `Unsalted`.
- **Scenario B — preference avoided**: seed
  `Preference(token="makhana", avoidPhrases=["peri-peri"], conf=0.9)`.
  Run `order makhana`. Assert cart line title does **not** contain
  `Peri Peri`.
- **Scenario C — no preference**: clear store. Run `order butter`. Assert
  the existing Phase 1 behaviour (no `PREFERENCE_APPLIED` log line).

#### Scripted

```powershell
.\scripts\run_blinkit_matrix.ps1 -Scenario context
```

Runs A, B, C above and asserts the preference-applied lines via
`Select-String "PREFERENCE_APPLIED|PREFERENCE_NONE"`.

#### Manual smoke (one pass before merging Phase 5)

1. Tap "Learn from my past orders". Walk through the consent dialog.
   Confirm the review screen lists at least 3 inferred preferences. Save.
2. Run `order butter, makhana`. Confirm cart contains the expected
   variants.
3. Tap "Forget my preferences". Re-run the same order. Confirm no
   preference logs and behaviour reverts to Phase 1 defaults.

---

## Phase 6 — Commerce App Adapter Architecture

### INTENT

Beta should become a shared grocery-ordering assistant, not a Blinkit-only
script. The core ordering brain must stay common, while each commerce app gets
its own wrapper for launch, search, result cards, modals, cart verification,
and app-specific behaviour.

### WORK

1. **Define a `CommerceAppAdapter` contract**
   - App identity: display name, package name(s), launch activity.
   - Readiness checks: app installed, app logged in enough to search, home/search
     surface reachable.
   - Search operations: focus search, type query, submit search, clear search.
   - Result interpretation: product cards, sponsored markers, out-of-stock
     markers, pack/variant modal shape.
   - Cart operations: open cart, verify cart line, stop before payment boundary.

2. **Move Blinkit-specific logic behind `BlinkitAdapter`**
   - Keep Blinkit-specific ideas here:
     - "Bought Earlier"
     - "People also bought"
     - Blinkit variant sheets
     - Blinkit `View cart` banner
     - Blinkit address/home handling
   - Keep global product logic out of this adapter.

3. **Keep shared logic central**
   - Instruction parsing.
   - Product aliases (`Lay's = lays`, `bhindi = lady finger`).
   - Quantity and pack solving.
   - User preferences.
   - Substitution policy.
   - Order result model.

4. **Adapter selection**
   - User can pick preferred grocery app.
   - If none is selected, default to the last successful app.
   - Future: route item-by-item across apps only after single-app flows are
     reliable.

### ACCEPTANCE

- Blinkit behaviour stays unchanged after moving it behind the adapter.
- Backend/app profiles can add Zepto or Instamart without modifying the shared
  parser, pack solver, or preference store.
- Logs include `APP_ADAPTER=<blinkit|zepto|instamart>`.

### TEST PLAN

- Unit tests for adapter selection and profile loading.
- Existing Blinkit Phase 1-5 tests pass without behavioural regression.

---

## Phase 7 — Preflight and Older-User Safety

### INTENT

Before placing anything in a cart, Beta should run a clear, friendly preflight
that catches common problems for non-technical users: permissions, app login,
location, selected address, stale cart state, and capture/accessibility health.

### WORK

1. **Preflight policy**
   - Add a policy object/config:
     - `production`: all safety checks enabled.
     - `emulator_test`: skip location-distance checks by default.
     - `manual_test`: developer can selectively disable checks.
   - Location checks must be off for emulator test scripts unless explicitly
     enabled.

2. **Required checks**
   - Accessibility service enabled.
   - Screen capture/overlay ready when required.
   - Target app installed.
   - Target app can be launched.
   - User appears logged in enough to search.
   - Cart state known or cleanup offered.
   - Stop-before-payment boundary active.

3. **Production location checks**
   - Device location services on.
   - App location permission usable.
   - Delivery address readable from the commerce app when possible.
   - If current device location appears far from selected delivery address,
     ask the user before proceeding:
     `"You seem far from the delivery address. Continue with this address?"`
   - Do not auto-change address.

4. **Older-user UI rules**
   - Plain language.
   - Large tap targets.
   - One decision per screen.
   - No technical errors; translate failures into actionable prompts.
   - Keep onboarding short and readable, with simple permission and
     accessibility guidance shown before the first run.

5. **Run controls**
   - The user can stop or dismiss Beta during any run and continue manually
     later; do not clean up the cart unless the user asks.
   - If the app is minimized or sent to the foreground by the system, pause
     automation, show a plain-language prompt, and resume only when the user
     returns or confirms continuation.
   - Use stop / pause / resume language consistently so older users can tell
     what will happen next.

### ACCEPTANCE

- Emulator runs can skip location checks and remain deterministic.
- Production mode blocks or confirms risky states before ordering.
- The user can understand what is wrong without reading logs.
- A run can be paused, stopped, dismissed, or resumed without an automatic
  cart cleanup unless the user explicitly requests it.

### TEST PLAN

- Unit tests for preflight policy decisions.
- Emulator tests with location checks disabled.
- Manual production-device smoke with location on/off and address mismatch.

---

## Phase 8 — Interactive Substitution Review

### INTENT

If an item is out of stock or low-confidence, Beta should finish the rest of
the list, then present a review screen with close alternatives. The user can
accept or reject each suggestion before Beta adds substitutes.

### WORK

1. **Substitution queue**
   - For each failed item, store:
     - original query
     - failure reason
     - closest alternatives
     - confidence
     - price/pack info when visible

2. **End-of-run review**
   - Show:
     - Added items.
     - Items not added.
     - Suggested alternatives.
   - User can accept/reject each alternative.
   - Accepted alternatives run through the same safe add/cart verification flow.

3. **Backend candidate alternatives**
   - For OOS/not-found/low-confidence states, return nearby candidate cards
     instead of only failing.
   - Mark why each suggestion was proposed.

4. **Safety**
   - Never substitute automatically unless user preferences explicitly allow it.
   - Never proceed past cart/payment.

### ACCEPTANCE

- OOS item does not abort the order.
- Alternatives are shown only at the end unless a safety issue needs immediate
  confirmation.
- Accepted alternatives produce normal `ITEM_RESULT` lines.

### TEST PLAN

- Unit tests for substitution ranking and review-state model.
- Emulator test with one OOS item and one successful item.
- Manual smoke where user accepts one substitute and rejects another.

---

## Phase 9 — Voice and Multilingual Input

### INTENT

The primary user input should become voice. Users may speak in English, Hindi,
Kannada, Tamil, Telugu, Malayalam, Marathi, Bengali, Gujarati, Punjabi, Odia, or
mixed language, while the commerce apps usually show English product names.
Voice and translation must feed the same ordering pipeline as typed text.

### WORK

1. **Input channel split**
   - Typed input, voice input, and test broadcasts all produce the same
     `OrderRequest`.
   - No ordering logic belongs in the voice layer.

2. **Speech-to-text**
   - Capture user speech.
   - Keep raw transcript for review/debugging.
   - Add confidence score.

3. **Language detection and translation**
   - Detect spoken language or mixed language before parsing.
   - Persist the user's likely preferred language after successful
     confirmation.
   - Re-run language detection when parsing confidence is low, the transcript is
     not understood, or the user explicitly changes language.
   - Translate/transliterate product terms into the English query used by the
     commerce app.
   - Examples:
     - Common Indian-language grocery terms to English product names.
     - Brand names preserved as spoken.
     - Quantities preserved exactly.

4. **Confirmation when uncertain**
   - If transcript or translation confidence is low, ask:
     `"I heard: butter, apples, pencil. Is that right?"`
   - Default to confirmation for older-user mode.

5. **Central multilingual product lexicon**
   - Shared across all apps.
   - Separate from app-specific knowledge.
   - Learns common terms over time.
   - Store language, script, transliteration, canonical English query, and
     confidence/source for every learned term.

6. **Language-agnostic backend contract**
   - Backend receives raw transcript/text plus optional user language hint.
   - Backend returns detected language, normalized English order text, parsed
     items, and confidence.
   - App adapters only receive canonical English product queries.

### ACCEPTANCE

- A voice transcript and a typed command produce identical `ParsedItem` output.
- Common Indian-language product terms can map to English app searches.
- Low-confidence translation triggers a confirmation screen before ordering.
- A previously confirmed user language is reused until confidence drops or the
  user changes language.

### TEST PLAN

- Unit tests for common Indian-language and mixed-language command fixtures.
- Manual voice smoke with English, Hindi, Kannada, Tamil, Telugu, Malayalam,
  Marathi, Bengali, Gujarati, Punjabi, Odia, and mixed-language lists.
- Emulator tests continue to use typed/broadcast input for determinism.

---

## Phase 9.5 — Accessibility Capability Knowledge

### INTENT

Blinkit accessibility data is inconsistent. Beta should continuously learn
which screen facts are reliable from accessibility, which require OCR, and
which require full screenshots/model reasoning. This keeps cost down without
pretending accessibility can answer everything.

### WORK

1. **Capability ledger per app/screen**
   - For each observed screen state, record whether the following were readable
     through accessibility, OCR, or screenshot/model:
     - search field
     - product title
     - price/pack
     - sponsored/ad marker
     - out-of-stock marker
     - ADD button
     - variant sheet
     - cart line
     - checkout/payment boundary

2. **Decision policy**
   - Prefer accessibility when it has been reliable for that screen fact.
   - Use OCR when accessibility lacks labels but text is visible.
   - Use screenshot/model only when the cheaper evidence is missing,
     contradictory, or low confidence.

3. **Blinkit-specific knowledge**
   - Store Blinkit capability observations in the Blinkit knowledge store.
   - Keep the same schema reusable for Zepto and Instamart.
   - Include examples of unreliable accessibility labels so future tests do not
     over-trust them.

4. **Telemetry**
   - Log `EVIDENCE_SOURCE=<accessibility|ocr|screenshot_model>` per decision.
   - Track screenshot calls avoided by accessibility/OCR.

### ACCEPTANCE

- Every order run updates the app capability knowledge when new evidence is
  observed.
- Debug logs explain why a screenshot/model call was needed.
- Cost telemetry can report screenshot/model calls avoided.

### TEST PLAN

- Unit tests for source-selection policy.
- Emulator/manual Blinkit probes that compare accessibility tree facts against
  OCR/screenshot facts.

---

## Phase 10 — Second Commerce App Adapter

### INTENT

After Blinkit is stable behind the adapter boundary, add one more app adapter
to prove the architecture. Pick Zepto or Swiggy Instamart based on which app is
easier to automate reliably on the test device.

### WORK

1. Add app profile and adapter.
2. Implement preflight for that app.
3. Implement search, product selection, add-to-cart, cart verification.
4. Keep shared parser/preferences/product semantics unchanged.
5. Add app-specific behaviour knowledge store.

### ACCEPTANCE

- Same typed `OrderRequest` can run on Blinkit or the second app.
- At least three single-item probes pass on the second app.
- No shared product-learning code is duplicated into the app adapter.

### TEST PLAN

- Adapter unit tests.
- Three single-item emulator/manual probes.
- One OOS/not-found probe.

### CURRENT FOUNDATION

- App support is profile-gated; adding a JSON profile is the first step, but a
  second app should not be enabled until its emulator/manual probes pass.
- Shared language/product/preference logic remains outside app profiles.
- Blinkit-specific observations stay in the Blinkit profile or knowledge store.

---

## Cost and Telemetry

### INTENT

Every order should expose a rough cost and latency profile so we can decide
which observations need multimodal/backend reasoning and which can be handled
locally.

### WORK

- Log per order:
  - screenshot/backend calls
  - input/output token estimate
  - OCR calls
  - model used
  - latency per step
  - total estimated cost
- Prefer local/parser/accessibility decisions where reliable.
- Use multimodal calls only when the cheaper evidence is insufficient.

### ACCEPTANCE

- `ORDER_RESULT` is accompanied by a cost summary in debug logs.
- Test scripts can export average cost/order for a scenario.
- Cost summary separates accessibility-only, OCR, and screenshot/model
  decisions so monthly user cost can be estimated from real usage.

---

## Cross-Phase Test Matrix Summary

| Scenario        | Trigger script                                                  | Pass criteria                                                                 |
|-----------------|-----------------------------------------------------------------|-------------------------------------------------------------------------------|
| `single`        | `run_blinkit_matrix.ps1 -Scenario single`                       | ≥ 6/8 probes match expected status, OOS probe returns `status=oos`            |
| `multi-clean`   | `run_blinkit_matrix.ps1 -Scenario multi-clean`                  | every cart line matches input order, `ORDER_RESULT items_failed=0` for clean  |
| `multi-noisy`   | `run_blinkit_matrix.ps1 -Scenario multi-noisy`                  | parsed list matches unit-test expectation; same cart outcome as `multi-clean` |
| `quantity`      | `run_blinkit_matrix.ps1 -Scenario quantity`                     | every item within `deviation_pct` bound, rationale logged                     |
| `context`       | `run_blinkit_matrix.ps1 -Scenario context`                      | A applies preference, B avoids, C is unchanged                                |
| `preflight`     | `run_blinkit_matrix.ps1 -Scenario preflight`                    | emulator policy skips location checks; production policy blocks risky states  |
| `substitution`  | `run_blinkit_matrix.ps1 -Scenario substitution`                 | OOS alternatives are reviewed and only accepted choices are added             |
| `voice-i18n`    | unit fixtures + manual voice smoke                              | transcript/translation produces the same `OrderRequest` as typed input        |
| `evidence-src`  | unit fixtures + Blinkit probes                                  | accessibility/OCR/screenshot source is logged and matches policy              |

CI hook (suggested, not required): nightly run of all scenarios on the
medium-phone API-34 emulator already used in `*.logcat` artefacts. CSVs
get archived under `logs/matrix_*.csv` and a small Python script (out of
scope here) flags regressions phase-over-phase.

---

## Current Test Status

### Today’s status

- Backend test suite: **88 tests OK**.
- Android unit tests: **OK**.
- Matrix runs: **single**, **multi-clean**, **multi-noisy**, and
  **quantity** passed.
- Fixture simulations: **preflight**, **substitution**, and **evidence**
  passed.
- Context matrix: **1 pass, 2 failures**; the failures are due to
  preference / natural-language wording coverage gaps.
- Real-device voice: **not possible today**, so voice remains blocked on
  a device-capable follow-up.
- Cloud staging: **private**; feedback smoke **previously passed**.
- OOS checkout cart UI patch: **covered in backend tests**.

### Latest plan additions

- Implement **store-unavailable handling before live tests**.
- Keep **UI redesign / onboarding** as upcoming work; it is **not done
  yet**.
- Preserve the current safe test boundary: cart verification only, no
  payment or final checkout actions.

---

## Day-by-Day Schedule (suggested)

| Day | Focus                                         |
|-----|-----------------------------------------------|
| 1   | Phase 0 infra + per-item logging contract     |
| 2   | Phase 1 OOS / variant / sponsored guards      |
| 3   | Phase 1 polish + run `single` matrix overnight|
| 4   | Phase 2 sequencer + clean multi-item tests    |
| 5   | Phase 3 noisy parser + extensive JVM unit pass|
| 6   | Phase 4 pack solver + quantity instrumented   |
| 7   | Phase 4 polish + soak test on quantity matrix |
| 8   | Phase 5 preference store + seeder UI          |
| 9   | Phase 5 wiring + preference instrumented tests|
| 10  | Final cross-phase matrix run + bug bash       |

If any day's phase fails its acceptance criteria, **stop and stabilise**
before moving to the next phase. Each phase's tests assume the previous
phase is green.

---

## What Codex Should Do First

1. Read this file end-to-end.
2. Read `codex_android_blinkit_test_automation.md` (test harness conventions
   are unchanged) and `DEBUG_NOTES_FOR_CODEX.md` (R1–R15 already mapped
   into Phase 1).
3. Implement **Phase 0** in a single PR. Do not start Phase 1 until the
   `single` matrix CSV exists and the new `ITEM_RESULT` / `ORDER_RESULT`
   log contract is unit-tested.
4. Open a separate PR per phase. Each PR must include the unit tests, the
   instrumented test class, and a CSV captured from the matrix script
   committed under `logs/sample_runs/<phase>.csv` for reviewers.
5. **Never** edit checkout, payment, address-save, or login code paths.
   If a Blinkit screen requires login or a new address, abort with
   `STATE: BLOCKED reason=login_required` and emit a single
   `ORDER_RESULT items_total=N items_succeeded=0 items_failed=N failures="all:blocked"`.
