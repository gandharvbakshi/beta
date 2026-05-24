# Onboarding a New Grocery/Commerce App Adapter

Use this as the fast-start checklist when adding a new shopping app adapter.
It is intentionally practical: learn the app, define the guardrails, prove
search-and-cart behavior, and only then widen the test matrix.

Adapter design note: keep each commerce app behind a common capability
interface so today’s accessibility/OCR/screenshot automation can coexist with
future official API or MCP connectors without changing the safety model.

## 0) Safety boundary

This automation stops at **cart verification**.

- Never tap place order, pay now, checkout, UPI/card/wallet flows, or final
  confirm buttons.
- Never save or edit real address/payment data.
- If the app asks for login, delivery confirmation, or a blocking permission
  that changes the flow, stop and record it.
- Treat any test as successful only when the right item is in cart and the app
  is ready for human review.
- Keep the adapter cart-only: no payment, no checkout-finalization, no
  irreversible order submission.

## 1) Preflight

Before writing adapter logic, gather the basics:

- App package name and launch entry point.
- What is available through Android Accessibility: resource IDs, content
  descriptions, focused/editable fields, clickability, enabled state, bounds,
  and whether product/cart rows are paired by stable IDs.
- Whether the UI is XML, Compose, hybrid, or mostly webview.
- Search entry path, product-card layout, cart badge, and cart row layout.
- Any required permissions, onboarding, login wall, address gate, or popup
  whitelist/blacklist.
- Whether accessibility tree, OCR, screenshots, or all three are useful on the
  first screen.

Record the above in the adapter notes before coding. If you cannot name the
search box and cart confirmation signals, do not start automation yet.

## 2) Address and home readiness

Before product automation, prove the app can be launched into a stable,
serviceable home/search state with the intended saved address selected.

Required checks:

- launch the app from a stopped state and verify it is foreground
- open the address picker from the app header or equivalent entry point
- select a saved safe test address such as Home
- verify the picker closes and the app returns to the home/search surface
- verify the selected address using app-visible signals, not only tap success
- detect store-unavailable, high-traffic, unserviceable, login, permission, and
  address-edit states as blockers
- rerun the script when Home is already selected and confirm it is idempotent
- keep the flow address-selection only; never create, edit, or save a real
  address

For Swiggy Instamart, this is covered by
`scripts/swiggy_address_preflight.ps1`: launch Swiggy, open the address picker,
search saved addresses for Home, select the saved Home row, and verify the
Home/search surface before any product probe.

Do not advance to single-item search until this gate passes on the target
device.

Clean-state preflight:

- Before the first product probe, learn how to return the app to a clean
  search-ready surface.
- Record the safe path for dismissing promos, closing sheets, and clearing any
  leftover cart state that the app exposes safely.
- Keep the generic rule shared: never treat stale cart contents or pre-query
  product rows as proof of the requested item.
- Put the exact app-specific cues in the profile; do not hardcode them into the
  shared onboarding checklist.

Generic capture helper:

- Use `scripts/onboard_commerce_app.ps1` for every new app before adding app
  logic. It records package metadata, focused window state, screenshot, UI
  text, resource IDs, and a node CSV with text, content descriptions, resource
  IDs, clickability, focus, and bounds.
- Run it for each scenario name you learn: `home`, `address_gate`,
  `home_after_address`, `search_entry`, `search_results_<item>`,
  `add_<item>_cart_banner`, `cart_page_<item>`, and cleanup/failure states.
- If a UI dump is flaky while the app animates, increase the retry count with
  `-UiDumpAttempts`; do not proceed from screenshot-only evidence unless the
  automation path can safely rely on OCR.

Zepto first-pass notes from the May 23, 2026 onboarding run:

- Package: `com.zeptoconsumerapp`; launch activity: `.MainActivity`.
- Cold launch can open a saved-address/location bottom sheet. Use an existing
  saved address such as Home; do not enable location, create addresses, or edit
  address data.
- Serviceable Home exposes `com.zeptoconsumerapp:id/homepage-search-box`, an
  ETA/address header, and bottom tabs.
- Search entry exposes a focused `EditText` with `content-desc="Search"` and
  Back/Clear controls. Zepto can show product cards before a query is typed, so
  never treat pre-query ADD buttons as requested-item results.
- Zepto live multi-item runs can leave stale previous-search rows on screen.
  Do not treat visible product rows as current-query evidence unless the search
  field/top query matches the requested item or the typing action was just
  performed.
- Search results expose UUID-paired `product-card-container...` and
  `product-card-add-button...` nodes. Match the container product summary to
  the requested item before tapping the paired ADD.
- After ADD, the result card becomes a minus/plus stepper and the page exposes
  `floating-cart-button` with a cart item count.
- The cart page exposes product-name and SKU quantity resource IDs, but also
  shows `Instant Order` and `Pay Now`; treat those as hard checkout/payment
  boundaries and stop before any payment action.
- Zepto learning should always inspect Accessibility before relying on OCR:
  the first pass found usable content descriptions, UUID-paired card/add IDs,
  cart-row resource IDs, focused search state, bounds, and explicit
  payment-boundary text.
- Zepto search should be treated as a ranking pipeline: recall first, then
  relevance scoring, then final ranking. The top 2-3 results and the top fold
  matter most; use hard negatives, small distilled rerankers, and continuous
  `0..1` relevance labels when capturing or refining learned behavior.
- Keep Zepto behavior knowledge app-scoped and always-learning for live user
  runs: each confirmed live observation should update the profile/notes for
  that app instead of being treated as a one-off exception.
- Zepto non-voice typed/broadcast testing closed locally on May 24, 2026:
  single item, same-card ADD selection, quantity/pack, preference/variant,
  no-result continuation, and short soak all passed. Voice/input parity is the
  only Zepto phase left outside that scope.

## 3) First-screen study

Spend time on the home screen before touching code.

Look for:

- Search bar location and label variants.
- Accessibility structure for the search field, product cards, ADD controls,
  steppers, cart banners, cart rows, and checkout/payment boundaries.
- Hero banners, sponsored rows, category chips, and sticky popups.
- Whether the first useful action is tap-search, type-search, or open a search
  icon.
- What the app shows after a search: product cards, quick-add buttons, variant
  modals, quantity steppers, or no-results states.

Capture at least one screenshot, one accessibility tree dump, one accessibility
node table, and one OCR pass for the home screen and the search-results screen.
These become your baseline.

## 4) App profile setup

Create a small app profile for the new commerce app. Keep it explicit and easy
to update.

Minimum fields to track:

- package name
- launch activity or launch method
- search entry hints
- cart verification hints
- known popup texts
- known blocking states
- any app-specific quirks, such as variant modal behavior or quantity steppers

If the app differs from Blinkit/Swiggy patterns, write that difference down
early. The next app will be faster if the profile is accurate and short.

## 5) Evidence ledger

For every new app, keep a lightweight evidence ledger. This avoids guessing
later.

Record, per run:

- timestamp and device/emulator
- instruction used
- home-screen screenshot
- results-screen screenshot
- OCR text snippet
- accessibility tree snippet
- backend decision or adapter decision
- item chosen and why
- cart verification result
- failure reason if the run stopped

Use the same naming every time so failures are easy to compare. The goal is to
answer: “What did the app show, what did the agent see, and what did it do?”

## 6) Search-first strategy

Default to search first.

Why:

- search reduces navigation variance
- it is easier to verify with OCR and screenshots
- it exposes sponsored/noisy cards in a controlled way
- it works better for single-item and clean multi-item flows

Only fall back to category browsing when the app has no usable search path or
search is blocked. When search is available, make it the primary route in the
adapter and test plan.

Practical rule:

- first action: reach the search UI
- second action: type the target query
- third action: evaluate result cards and pick the best candidate
- fourth action: verify cart state

## 7) Cart-only testing

Test only up to cart readiness.

For every run, verify:

- the intended product is in cart
- quantity is correct
- no wrong-brand item was added
- the flow did not advance into payment or final checkout

If the app already has a quantity stepper or “already in cart” state, treat that
as a cart-verified terminal state only if the product matches the query.

## 8) Cleanup

Every run should leave the emulator clean.

- clear the cart or reset app state after the test
- dismiss transient overlays
- close the app if needed
- save logs and screenshots before cleanup so the failure remains reproducible

Do not carry stale state into the next app or the next instruction. A clean
start is part of the test.

## 9) Phase gates

Use these gates in order. Do not skip ahead unless the earlier gate is stable.

### Phase A — Single item

Prove one common item can be searched, selected, and verified in cart.

### Phase B — Noisy single item

Handle sponsored rows, unrelated cards, wrong-brand suggestions, and “already in
cart” states.

### Phase C — Quantity

Prove the adapter can interpret packs, multipacks, count-based items, and
quantity steppers without adding the wrong amount.

### Phase D — Preferences and variants

Handle flavor, size, pack, and brand choices. If multiple cards are plausible,
prefer the exact or closest safe match and stop if the result is ambiguous.

### Phase E — Substitution and out of stock

Detect out-of-stock, notify-me, sold-out, and substitute-like states. Do not
tap unsafe fallback buttons. Stop or escalate when the app needs a human choice.

### Phase F — Voice or assistant entry

Only after search/cart behavior is stable, test voice-driven or assistant-driven
entry points. Treat voice as an alternate input path, not a different workflow.

## 10) What to record so the next app is faster

At the end of the first adapter pass, leave behind:

- the app profile
- search entry and cart verification signals
- known popups and blockers
- the first-screen study notes
- one good example of each success state
- one good example of each failure state
- the evidence ledger format
- the cleanup/reset command or manual steps

If you do only one thing for future speed, make the profile and evidence ledger
accurate. That is what shortens the next app onboarding the most.

## 11) Keep the feedback loop tight

When a run fails, capture the smallest useful artifact set, fix the narrowest
issue, rerun the smallest scenario, and only then expand coverage.

The sequence is:

1. study the first screen
2. search for one item
3. verify cart only
4. clean up
5. widen to noisy, quantity, variant, substitution, and voice cases

That discipline keeps the adapter reliable and the diff small.
