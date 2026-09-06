# BETA Usage Cost Model — 2026-09-05

Scope: `D:\Projects\beta\.codex-worktrees\swiggy-mcp-primary` (Android client) + `D:\Projects\beta\.codex-worktrees\backend-deploy-35e1265` (Swiggy backend path only).

Assumption for currency conversion: `1 USD ≈ ₹94.5` (XE mid-market, 2026-09-05, indicative only).

Swiggy billing note: I found Swiggy Builders rate-limit / access docs, but no published per-call billing page for this MCP flow. Treat Swiggy upstream usage as operationally counted and currently unpriced here.

## What the meter measures

The operational logger in `mcp_usage_telemetry.py` tracks wall-clock request duration, route label, status class, incoming-quota-denied flags, and provider call counts/durations. It does not directly measure billed CPU, billed RAM, or Firestore spend.

That means this note uses the logger for volume accounting and the pricing pages below for billing math.

- [Cloud Run pricing](https://cloud.google.com/run/pricing)
- [Firestore pricing](https://cloud.google.com/firestore/pricing)

## What I modeled separately

- Cloud Run request-time for Swiggy HTTP requests, including the 45-second cart-persistence wait.
- Firestore document operations in the incoming-request guard.
- Firestore document operations for Swiggy connection state, rate-limit state, cart nonce claims, and cart locks.
- Swiggy MCP upstream tool attempts, counted for capacity planning but not directly priced from a published Swiggy bill.

## What it does not measure

- Android device time, battery, or mobile data.
- Fixed infra/admin costs, including any always-on Cloud Run `min1` idle burn.
- Any non-Swiggy endpoint.
- A separate image-analysis endpoint in `app/main.py` that calls OpenAI; that path is outside the Swiggy flow and should be modeled separately if enabled.

## Swiggy cart session path

For a normal 5–10 item session, the Android client calls:

1. `GET /swiggy/addresses`
2. `GET /swiggy/orders`
3. `POST /swiggy/recommendations/batch`
4. `POST /swiggy/cart/plan`
5. `POST /swiggy/cart/apply` with `persistenceCheck=true`

Optional, only if the user opens the post-verification review action:

6. `POST /swiggy/cart/check`

The 45-second wait is inside `cart/apply`; it is not a separate Android request.

## Defensible upstream Swiggy MCP attempt count

Low end for a successful 5–10 item session: about 16 upstream MCP calls.

Representative high end for the same session: about 30+ upstream MCP calls if address pagination, empty-search retries, and cart readback retries all fire.

Rough breakdown:

- Addresses: 2–6 calls (`get_addresses` pages plus `get_cart`)
- Recent orders: 1 call (`get_orders`)
- Recommendations: 7–15 calls for 5–10 items (`your_go_to_items`, `get_orders`, plus one or more `search_products` calls and occasional retries)
- Cart plan: 2–7 calls
- Cart apply + 45-second persistence check: 4–10 calls
- Optional post-success review check: 2–7 calls

## Firestore request scope per successful session

The backend has two Firestore layers in the Swiggy path:

1. Incoming-request guard budgets
   - Each accepted Swiggy HTTP request uses 6 budget keys.
   - For the 5-request success path, that is about 30 document reads + 30 document writes before any Swiggy-specific state.

2. Swiggy service state / rate limits
   - Connection lookups: roughly 1 read per Swiggy request.
   - Rate-limit store: roughly 1 read + 1 write per upstream MCP attempt when Firestore storage is active.
   - Cart nonce / lock flow: a small number of extra reads/writes around `cart/apply`.

Practical band for one successful 5–10 item session:

- Low: about 45–60 reads and 40–55 writes
- High: about 70–100 reads and 60–90 writes

I did not verify current free-tier availability for this billing account, so I am not subtracting any free-tier credit from the model.

## Cost estimate

Pricing used from the official pages above:

- Cloud Run request-based billing in Mumbai (`asia-south1`) is priced at `$0.000024` per vCPU-second and `$0.0000025` per GiB-second while the instance is billable.
- Firestore’s pricing table includes Mumbai (`asia-south1`) and lists `$0.03` per 100,000 document reads, `$0.09` per 100,000 document writes, and `$0.01` per 100,000 document deletes. I am treating the region-specific Firestore numbers as the authoritative table values and not as a savings estimate.

### Session-time billing model

Live config for the current revision is 1 vCPU, 512 MiB memory, concurrency 80, minScale 1, maxScale 1, with no CPU-throttling annotation override. For a successful session, I modeled three total request-time bands across the whole Swiggy flow:

- Low: 60 seconds total
- Mid: 120 seconds total
- High: 240 seconds total

Those totals already include the 45-second cart-persistence wait. They are intentionally session-time ranges, not hard guarantees.

Per-session Cloud Run cost at 1 vCPU / 512 MiB:

- 60 sec: about `$0.001515` or `₹0.14`
- 120 sec: about `$0.00303` or `₹0.29`
- 240 sec: about `$0.00606` or `₹0.57`

Per-session Firestore document-op cost for a typical successful session in the 45–100 read / 40–90 write band:

- Roughly `$0.00005`–`$0.00011` per session
- Roughly `₹0.005`–`₹0.01` per session

### At 10 sessions/day, 300 sessions/month

Illustrative variable spend, excluding any always-on `min1` baseline and excluding any fixed infra/admin cost:

- Low: about `₹42.95`/month
- Mid: about `₹85.90`/month
- High: about `₹171.80`/month

That is a model, not an invoice. It is still conservative because it does not attempt to price the always-on minimum-instance baseline separately.

## Bottom line

- No paid LLM is invoked in the Swiggy MCP path I audited.
- The Swiggy path is mostly Cloud Run wait time plus small Firestore state checks.
- The `₹1000` advisory budget is a guardrail for review, not a cost cap.
