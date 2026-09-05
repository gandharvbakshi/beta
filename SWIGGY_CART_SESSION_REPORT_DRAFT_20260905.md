# DRAFT — NOT SENT

Subject: Builders Club: Instamart cart update persists with native app stopped, but reverts with warm native app

Hello Swiggy team,

Our authorised Beta integration is now working against `https://mcp.swiggy.com`. We resolved the saved/native address-ID comparison in our adapter with strict, uniquely bound mapping and unchanged outbound saved IDs. We are testing cart additions only; no orders or checkout/payment have been attempted.

We found a cross-client cart persistence issue on Android 36 with Swiggy 4.115.1 (versionCode 1817):

1. With native Swiggy already warm, Beta submitted one reviewed five-product `update_cart`. Our immediate `get_cart` readback matched all requested products and exact quantities, plus the original one-item baseline, at the intended address. Around 40 seconds later, independent reads showed only the original baseline. The account owner confirmed no manual cart changes. Beta did not send a subsequent update or retry.
2. With native Swiggy force-stopped without clearing app data, a fresh reviewed plan for the same products persisted. Independent reads at approximately 21, 59, 128 and 274 seconds all retained the six expected lines. A cold launch of native Swiggy also displayed exactly that cart. We individually removed only the test additions afterward and verified that the original cart and address were restored.
3. A separate ten-product test, also with native Swiggy initially force-stopped, passed on the final test revision. One update at 13:21:12 UTC was verified at 28, 112 and 275 seconds; native cold-launch at about 165 seconds displayed the same eleven lines (ten additions plus the original baseline), every quantity one. Individual cleanup again restored the exact baseline/address. We have not repeated a warm-native mutation or replayed the failed earlier update.

The first update was around 2026-09-05 11:23:02 UTC; the controlled second update was around 12:02:17 UTC. Both used the production MCP endpoint and the same authenticated installation/address. We have no evidence of a Beta cache or an automatic replay explaining the reversion.

Your official MCP README mentions keeping native Swiggy closed to avoid session conflicts. Is this warm-native cart overwrite a known limitation for Builders Club integrations? What is the supported production mechanism to preserve an MCP cart when the native app has been used recently or when a user opens it to review the cart?

In particular, is a specific cart/session identifier, refresh/handoff operation, or reconciliation sequence required? We cannot force-stop another Android app in a normal minimal-permission production app, and a “close Swiggy” instruction alone is not a dependable safeguard for elderly users. We will not automatically replay updates or place test orders.

We can supply minimal request correlation details privately if needed. This draft intentionally omits access tokens, installation bearers, saved/native address IDs, phone numbers and raw cart/address payloads.

Thank you.
