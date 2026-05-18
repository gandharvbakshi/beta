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

## 3) First-screen study

Spend time on the home screen before touching code.

Look for:

- Search bar location and label variants.
- Hero banners, sponsored rows, category chips, and sticky popups.
- Whether the first useful action is tap-search, type-search, or open a search
  icon.
- What the app shows after a search: product cards, quick-add buttons, variant
  modals, quantity steppers, or no-results states.

Capture at least one screenshot, one accessibility tree dump, and one OCR pass
for the home screen and the search-results screen. These become your baseline.

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
