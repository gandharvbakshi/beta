# Swiggy Checkout Acceptance

Status: preview only.

The currently distributed Beta build remains cart-only. The checkout preview
described here is disabled by default and must stay off until a separate,
approval-reviewed release explicitly enables it.

## Scope

This checklist covers a future checkout-enabled Beta build that:

- still preserves the existing cart-only flow when the feature flag is off,
- adds a full cart, address, fees, total and payment-method review step,
- hands UPI selection off to a trusted provider bridge into the user’s UPI app,
- never collects or stores PIN, card or VPA data in Beta,
- may surface cash on delivery if Swiggy returns it,
- keeps the reviewed cart/address and provider receipt encrypted while an
  attempt is unresolved, then reduces resolved records to IDs/status/timestamps,
- emits no grocery, order or payment-ID analytics, and
- adds no new Android permissions.

## Offline acceptance checks

Run these against mocked data, feature-flagged builds or local-only harnesses.
Do not require live payment, live order placement or any external provider app.

### Flag and state safety

- Checkout preview stays off in the shipped build.
- A cold start with the flag off shows the existing cart-only experience.
- App restart does not enable preview by accident.
- Process death returns to a disabled or safe recovery state.
- Background/foreground transitions do not duplicate state.
- Partial local storage loss does not restore stale checkout state.
- A disabled preview never mutates backend order state.

### Review screen integrity

- Full cart review renders every item, quantity and price row.
- Saved address, fees, total and payment-method summary are visible together.
- Large font does not clip totals or hide the primary action.
- Accessibility focus order reaches review elements in a sensible sequence.
- Double taps on primary actions do not duplicate the pending checkout.
- Timeouts do not cause an automatic retry loop.
- Unmatched address, GPS-off or missing-address states force a safe re-review.

### Data-handling checks

- No PIN, card number, VPA or other payment credential is stored locally.
- No grocery, order or payment-ID analytics are emitted.
- No new permissions are requested for the preview path.
- Encrypted backend payloads remain opaque and recovery-only.

## Approval-required live acceptance checks

Run these only after separate explicit approval on a test build. Opening a UPI
bridge requires `checkout`, which can already create a pending order; COD may
place a real order immediately. Merely avoiding the UPI PIN does NOT make a live
checkout test harmless. Approval must specify whether order/payment initiation
and an actual purchase are permitted, along with the basket and spend limit.

1. Review the full cart, address, fees, total and payment method before any
   external payment handoff.
2. Confirm UPI selection launches the trusted provider bridge into the UPI app.
3. Confirm Beta never sees or stores PIN, card or VPA data during handoff.
4. Confirm cash on delivery appears only when returned by Swiggy.
5. Confirm the backend writes one durable encrypted order-attempt record;
   unresolved payloads support recovery, and terminal transitions remove the
   cart, address, selected method, payment link and provider transaction receipt.
6. Confirm backgrounding the app and returning later restores the same pending
   recovery state.
7. Confirm process death restores the same pending recovery state.
8. Confirm a timeout stops safely and asks for explicit review instead of
   retrying checkout automatically.
9. Confirm a failed or partial provider handoff does not create duplicate
   attempts.
10. Confirm large font and accessibility-assisted navigation still expose the
    same review details before handoff.

## Stop conditions

- Until explicitly approved, never invoke live checkout, payment status that can
  auto-confirm, confirm_order, or a final payment confirmation control.
- Never complete a real purchase without separate basket/payment approval.
- Never enable auto-retry for failed checkout steps.
- Never add grocery/order/payment-ID analytics.
