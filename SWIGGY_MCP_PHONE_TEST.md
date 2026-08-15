# Swiggy MCP phone test

This is the phone checklist for the current MCP-first Swiggy experience.
Direct MCP is the default for Swiggy Instamart; the screen-assisted route is a
temporary, explicit fallback. Blinkit remains screen-assisted and unchanged
apart from its visible beta label.

Use a debug build and an approved staging backend. Keep checkout and payment out of scope.

1. Open Beta. Swiggy Instamart should be selected first. The accessibility and screen-capture setup card should be hidden for Swiggy.
2. Tap **Connect Swiggy**. Confirm only `https://mcp-staging.swiggy.com/auth/authorize` (or the approved production host) opens.
3. Complete phone and OTP inside Swiggy's browser page. Confirm Beta returns to **Swiggy is ready**; no OTP or access token appears in logcat.
4. Say “butter.” If history/go-to items contain a clear usual variant, Beta may preselect it; the final cart-change dialog must still name the exact variant and quantity.
5. Say an ambiguous item. Confirm Beta offers a short, readable variant list before planning the cart.
6. Use an account with multiple addresses. Confirm Beta asks which saved address to use.
7. Confirm the final dialog states that existing items stay and Beta stops before checkout/payment.
8. Tap **Add to cart** once. Confirm one update occurs and Beta reports success only after cart readback matches.
9. Change the cart in Swiggy before confirming. Beta must reject the stale plan and ask for a new voice order.
10. Reuse a confirmation token in a debug test. It must be rejected without another update.
11. Disconnect Swiggy. Confirm status becomes disconnected and the encrypted server-side token record is removed.
12. Select Blinkit*. Its existing screen-automation permissions and flow should reappear and continue to stop before checkout. Confirm the note `(This is still in Beta)` is visible.

Before widening the beta, repeat expiry/revocation, offline,
malformed-response, out-of-stock, unknown-cart-schema, large-text, TalkBack,
and font-scale checks. Keep every run cart-only and stop before checkout or
payment.
