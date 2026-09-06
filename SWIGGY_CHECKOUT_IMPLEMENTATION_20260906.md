# Swiggy checkout implementation — 6 September 2026

## Outcome and authority

Implemented an approval-gated Android/backend checkout path. It is **disabled
by default**, not live-tested, not deployed, and not uploaded to Play in this
pass. This is an implementation candidate, not proof of production readiness.
The owner's instruction permits implementation but forbids live checkout,
payment and order tests until separately approved.

Active Android worktree: `.codex-worktrees/swiggy-mcp-primary`, branch
`codex/swiggy-mcp-release-readback`. Backend worktree:
`.codex-worktrees/backend-deploy-35e1265`, branch
`codex/swiggy-mcp-cart-hardening`. Do not build the older root checkout as though
it contained these changes. Preserve the unrelated backend Blinkit knowledge edit.

## User experience implemented

1. Existing grocery selection and one-shot cart confirmation remain separate
   from ordering. The checkout switch being off preserves the cart-only entry.
2. When enabled, review the **whole current cart**, not only Beta's additions.
   Display each item, quantity, unit price, Swiggy's bill lines, total, full saved
   delivery address and the chosen available payment method.
3. Read a shorter address, exact total and payment method aloud, followed by
   items. Long speech is split into bounded TTS chunks. Voice cannot place an
   order; the final, price-labelled button needs an explicit tap.
4. Refresh cart/address/options before dispatch. Changed review data requires a
   new review. Location is never silently substituted for the delivery address;
   the full address remains visible even when ordering elsewhere or GPS is off.
5. UPI opens only an allowlisted Swiggy HTTPS bridge. The user approves in their
   UPI app. Beta does not collect a PIN, card number or user VPA. COD is offered
   only if Swiggy returns availability. QR explains the second-device requirement.
6. A durable local attempt ID plus atomic payment-handoff marker survives restart.
   Once handed off, show **Check existing order**, not a second payment button.
   Corrupt/mismatched storage blocks another payment instead of resetting state.
7. Unknown outcomes remain unresolved and block a new order. Support opens the
   phone dialer; it does not call automatically or require CALL_PHONE permission.
   Definitive split-store results require explicit review of the separate outcomes.

The warning to keep Swiggy closed is risk-reduction advice, not a claim that Beta
can detect the other app. Foreground-return cart checks detect current cart or
address changes without reapplying items. Native/MCP cart persistence is still
not proven fixed by the earlier cookie isolation change.

## Backend guarantees implemented

- Typed review/place/status endpoints, existing backend and installation guards;
  no generic payment-tool or arbitrary provider-argument endpoint.
- A signed review binds installation, whole cart/address/fees/options and a
  15-minute review window. The longer window accommodates elderly readback;
  a fresh comparison still occurs immediately before dispatch.
- Conflicting alternate cart representations fail closed. Amounts compare in
  exact decimal INR, including one-paise drift; JSON numeric provider prices
  remain supported. Provider bill rows are displayed, not falsely claimed to be
  an independently audited accounting reconciliation.
- A durable encrypted compare-and-set record is saved **before** the single
  provider checkout call. No automatic checkout, payment-status or confirmation
  transport retry, including redirects. The latter two can complete an order.
- Cart planning/application is blocked while an order is unresolved. An
  in-progress status call durably hides the payment link before provider I/O.
- Only successful terminal payment can lead to explicit confirmation; already
  auto-confirmed orders are not confirmed again. A lost confirmation response
  never authorizes another confirmation or another checkout.
- Multi-order terminal interpretation requires matching counts and explicit
  per-order states. Contradictory pending-payment/multi-order hybrid receipts
  remain unknown. They are not guessed into a single payment transaction.
- The kill switch suppresses provider writes and previously returned payment
  links, while preserving local/server recovery evidence.
- Unresolved encrypted payloads retain the reviewed basket/address and bounded
  receipt for recovery. Terminal records remove these details. No automatic TTL
  forgets an unresolved attempt. Privacy drafts now describe this accurately.
- Coarse checkout route/tool counts and latency use existing instrumentation;
  shopping contents, address and order/payment IDs are not analytics dimensions.

## Verification

- Android JVM tests: **193 passed, zero failures/errors**.
- `testDebugUnitTest assembleDebug assembleDebugAndroidTest lintRelease
  bundleRelease`: final repeat after the last recovery guard **BUILD SUCCESSFUL**
  in 17 seconds. Release manifest policy gate passed. Lint: zero errors,
  96 warnings (not a zero-warning claim).
- Default generated debug and release checkout flags: **false**.
- Backend selected offline suite: **252 tests + 119 subtests passed**, six
  dependency/framework deprecation warnings, in 22.75 seconds.
- Android store/recreation/dialog instrumentation tests were **compiled, not
  executed**. They must run on a dedicated disconnected emulator with synthetic
  storage, never an authenticated user's installation.
- Firestore transaction behavior is covered with a fake adapter, not a live
  Firestore concurrency experiment. No real orders, payment initiation, payment
  polling, confirmation, phone install or phone cart mutation occurred here.
- Final disabled release AAB SHA-256:
  `e534f1441d1902e5bd32e4e592f047f4a5fc42c5322dbd12f7988bf658f2cca9`.

## Independent review and adjudication

Claude's architecture adviser supported the pre-dispatch persistence,
single-dispatch and foreground-recovery approach. Its final reviewer invocation
failed; there is no final Claude approval. The independent Codex reviewer found
stale payment-link and partial-result recovery issues, which were fixed and
regression-tested.

Cursor **Grok 4.6 Extra High Fast** completed two read-only reviews. Accepted:
conflicting cart aliases, safe pre-dispatch lock failure proof, corrupt local-ID
recovery, persistent payment-handoff latch, unknown-state caching, and support
access. Fixed the contradictory multi-order/pending-payment receipt case.

Not adopted: requiring every receipt to repeat optional address/total fields,
rejecting JSON numeric prices, or treating a documented explicit PLACED result
as proof of payment failure. Swiggy's contract makes those receipt fields
optional and uses numeric prices. Returned contradictory fields are rejected;
absence alone does not negate Swiggy's explicit placement result. The UI says
order confirmed, not that every COD/UPI order has been debited.

## Remaining release gates — do not conceal these

1. Actual checkout tool entitlement, response enums, exact bridge host and UPI
   app handoff are unverified. The client/server default hosts are
   `mcp.swiggy.com`, `swiggy.com`, `www.swiggy.com`; only extend them after trusted
   provider evidence and coordinated client/server review.
2. There is no documented atomic cart-hash/maximum-charge parameter on Swiggy
   checkout. A fresh review plus Beta's lock cannot prevent the user/native app
   changing the provider cart between that read and dispatch. Returned amount
   or address mismatches become unknown; this is not a guaranteed price lock.
3. If checkout's response is lost before usable order/payment IDs arrive,
   automatic resolution may be impossible. Missing/corrupt local IDs, ambiguous
   database writes, expired polling windows and uncertain confirmation remain
   blocked for support reconciliation. Do not delete recovery records or invent
   a 'failed' outcome simply to allow another order.
4. Multiple still-pending store payments have no verified per-order transaction
   mapping yet. Unsupported/hybrid shapes stop for support; automated multi-store
   UPI completion is not claimed. Verify the supported shape before broad enablement.
5. Run the isolated Android UI/lifecycle/large-font suite, then an explicitly
   approved bounded live acceptance. Even opening the bridge can create a
   pending order; COD can place immediately. Approval must name the basket,
   permitted action and spend limit. A real UPI PIN remains the user's action.
6. Before publication: verify hosted protection/routing and deployment, Play
   Data Safety, public policy, actual screenshots and descriptions, then the
   enabled candidate. Build success alone is not a release approval.

## Provider references checked

- [Checkout contract](https://mcp.swiggy.com/builders/docs/reference/instamart/checkout/)
- [Current cart schema](https://mcp.swiggy.com/builders/docs/reference/instamart/get_cart/)
- [Payment options](https://mcp.swiggy.com/builders/docs/reference/instamart/get_payment_options/)
- [Payment status and terminal flags](https://mcp.swiggy.com/builders/docs/reference/instamart/check_payment_status/)
- [Order confirmation](https://mcp.swiggy.com/builders/docs/reference/instamart/confirm_order/)

See `SWIGGY_CHECKOUT_ACCEPTANCE.md` for the later test checklist. No listed
acceptance scenario should be read as already executed merely because it exists.
