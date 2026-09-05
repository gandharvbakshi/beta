# DRAFT-NOTSENT

## Superseded at 17:45 IST: address mapping found; do not send the old premise

The earlier complete-string comparison missed a native ID embedded in the saved ID. A fresh, complete 45-record list exposes composite IDs of the form `<native_id>__<22-character base64url suffix>`, with unique native prefixes in this capture. Backend commit `a05e171` validates exact selected membership and uniqueness, preserves the complete outbound saved ID, and accepts only that bound native ID on cart readback. Unknown/malformed/ambiguous mappings fail closed; no address-text or GPS equality is used. This observed encoding is not an officially documented long-term contract.

Live nonempty-cart address tests now pass. Five reviewed additions with native Swiggy force-stopped remained on independent reads at 21, 59, 128 and 274 seconds, including native Swiggy cold-launch/cart display. All five were then individually removed; the original one-line baseline and address fingerprint were restored.

A different issue remains: with native Swiggy warm, the same five additions initially verified, then reverted to the original baseline around 40 seconds later. The user confirmed no manual changes. A cross-client session conflict is the leading hypothesis, supported by Swiggy's official README warning, but the exact mechanism is not provider-confirmed. Do not claim an address-ID blocker still exists or that an advisory close-app instruction fixes persistence for ordinary users.

The historical report below remains for evidence provenance only. No provider message has been sent in this continuation.

## Historical, superseded address report

Date: 2026-09-05

Evidence summary from the read-only Swiggy provider/address checks:

- An earlier five-product cart review selected an address from `get_addresses`. The saved address had an opaque 33-character `id`, passed unchanged as `update_cart.selectedAddressId`.
- After the update, `get_cart` agreed on the selected address via `selectedAddress` and `selectedAddressDetails.id`, but the returned native cart address id was a different 9-character identifier, not one of the 45 saved-address ids.
- SKU and quantity matched for all 5 items, but address identity stayed unverified at the provider-native level.
- All 5 items were removed individually; no order, checkout, or payment path was taken.

Fresh read-only schema evidence, captured today around 13:08 IST:

- 45 saved address records were present.
- 39 records had exactly five keys: `addressCategory`, `addressLine`, `addressTag`, `id`, and `phoneNumber`.
- 6 records omitted `addressTag`.
- There is no secondary provider-native address id field in the saved-address records.
- The latest remembered selected id is 33 characters.
- The current cart was empty / cart absent, so a fresh non-empty cart-to-saved-address mapping could not be replicated from this read-only snapshot.

Contract notes:

- Public docs indicate [get_addresses](https://mcp.swiggy.com/builders/docs/reference/instamart/get_addresses/) returns a stable address `id`, and [update_cart](https://mcp.swiggy.com/builders/docs/reference/instamart/update_cart/) uses `selectedAddressId` derived from `get_addresses`.
- Public docs and the reviewed schema do not document a mapping from that opaque saved-address id to the native cart id.
- `get_cart` returns cart-address fields as id-like strings, but no authoritative doc was found showing how to map those back to the original saved address id.

Sanitized schema placeholders:

- `savedAddress.id = "<opaque_33_char_id>"`
- `selectedAddressId = "<opaque_33_char_id>"`
- `get_cart.selectedAddressDetails.id = "<native_9_char_id>"`
- `phoneNumber = "<redacted>"`

Open question for Swiggy:

- Which provider field maps the opaque saved-address id to the native cart id?
- Can `get_cart` return the original saved-address id directly?
- What is the recommended way to validate that a cart is attached to the same address without guessing from text or GPS?

No mail was drafted or sent, and no handoff files were updated.
