# Swiggy rate-limit fix and release verification

Updated: 2026-09-05 13:48 IST

This continuation supersedes the undiagnosed 502 and failed history-preview status in `SWIGGY_RELEASE_FIXES_20260905.md`. Release acceptance remains **NOT PASSED**; no checkout/payment/order was attempted.

- The earlier apparent `502` was traced to upstream `429` responses in the logs.
- Observed log window: `07:18:45Z` through `07:19:48Z`.
- Log mix during that window: `HTTP 200 x77`, `HTTP 429 x6`.
- The old adapter flattened provider 429 into 502, and discovery swallowed it into missing-product warnings/fallbacks. The [official quota documentation](https://mcp.swiggy.com/builders/docs/operate/rate-limits/) specifies 70 requests/minute, 30 writes/minute and an integer-seconds `Retry-After` on 429.

Backend rate admission now uses shared Firestore state per hashed connection with the following limits:

- 60 total requests per minute
- 25 writes per minute
- 20 requests per 10 seconds
- 6 readback slots reserved

Current provider behavior:

- Provider `429` is propagated with validated `Retry-After`, numeric upstream status and actual dispatch-attempt count. No raw provider bodies, headers, request IDs or credentials are exposed.
- Queued batch searches stop on `429`; throttling is not represented as product unavailability.
- There is no automatic retry on `429`.
- Post-mutation readback `429` remains unknown because mutation replay is not performed.
- The gate fails closed.
- A short local burst wait of at most 10 seconds is allowed once before dispatch, followed by fresh admission. Longer waits return 429. No sleeps occur inside Firestore transactions. Android displays a rounded-up wait message without automatic retries; mutation 429 is not marked retryable.

Verification and build status:

- Runtime commit: `7889015`
- Latest test commit: `c9442e8`
- New 0% canary: `beta-backend-staging-rate-guard-20260905`
- Build: `55fad137-fc64-432c-9565-b3c8235a4340`
- Digest: `sha256:f6cb6e75cbcc03ad585c8f10c37d4f71af3904a49abd174e45aa617b9a2f4ad8`
- Main 100% unchanged: `beta-backend-staging-f0fa9e1-approved` (rechecked 13:46 IST)
- Firestore TTL expiresAt: active

Verification summary:

- Backend complete unit suite: 418 passed
- Android complete unit suite: 160 passed
- Debug/test APK, release bundle, and manifest gate: pass
- Lint: 0 errors, 92 warnings

Observed read-only results:

- Physical recent-order tests: 6 recent orders, 36 historical lines, longest 11; all 6 previews passed. Combined cart-schema/history instrumentation took 344.410s including five 60s pauses.
- Six historical lines across the orders had no suitable current candidate; the longest 11-item order produced all 11 candidates. This is read-only planning/transport evidence, not exact-product acceptance for every historical line or cart-addition proof.
- Cloud read-only run: 85 upstream `200` responses, no `429` or `5xx`.
- Shared latest remembered-address basket:
  - regular 5/5 in 3687 ms
  - mixed 6/6 defined products in 5296 ms, with a vague medicine phrase safely unresolved
  - unusual 9/10 in 5947 ms, with tahini failing
- Full basket test took 137.691s with two 60s pauses.
- Counterfactual read-only schema-only run passed in 9.311s.
- Counterfactual candidate counts: tahini 0, sesame paste 0, keenwaa 8 quinoa, edamame 0, soybeans 0, parchment 2, baking paper 1, butter paper 4.
- Edamame appeared in the basket but not in the later read-only search; that is not a stable claim.
- Native Swiggy read-only check: tahini search showed `500g/250g Urban Platter Tahini` as sold out; native current Home address is not proven ID-equivalent.
- At 13:42 IST, native Swiggy also visibly marked Big Sam's Japanese Edamame 300g, Pluckk Freshly Frozen Edamame 500g and Gadre Frozen Edamame 500g as SOLD OUT. No Add button was pressed. These are direct availability observations for the visible products at the unchanged native address, not proof of saved/cart identifier equivalence or the cause of every earlier search variation.
- Keep the unusual-basket assertion as FAIL for the missing tahini expectation. Independently observed native stockouts explain an availability limitation; they do not retroactively turn the always-available fixture into PASS. The safe missing-product result and absence of unrelated paste remain separate successes.
- Final timestamped cart GET at 13:43 IST: HTTP 200, recognized empty items array, `cartAbsent=true`; read-only schema test passed in 0.818s. Phone returned to Beta. No address, cart, location or microphone setting was changed in this continuation.

Address contract status:

- Fresh address-contract evidence shows 45 saved records: 39 have five keys (`id`, `addressLine`, `phoneNumber`, `addressCategory`, `addressTag`); six omit `addressTag`.
- There is no alias ID field in the saved-address schema.
- The selected id is 33 characters.
- The cart was empty, so there is no fresh mapping proof from this run.
- The earlier numeric 9-character cart id was not present in the 45 saved ids and remains a blocker.

Decision:

- Not release-ready yet.
- The remaining blockers are address mapping and the acoustic/cart 5/7/10 gates.
- Do not mark all tests as PASS.
- No cart/address mutations, no order placement, no GitHub push, and no Play change were performed.

Notes:

- The per-connection limit model is retained; this is not a global-user limit.
- Keys are one-way OAuth-connection fingerprints; provider quotas can also be shared by independently issued connections/native clients. Provider 429 therefore remains authoritative.
- Claude Opus approved the core limiter with cautions. Root enabled and verified TTL, retained fail-closed Firestore dependency behavior, and rejected shortening a documented provider cooldown because that could retry too early. Android review and compilation/unit evidence are root verification, not a claim that Claude reviewed the Android changes.
- Provider address-contract report is local and unsent, pending explicit approval to reply. Do not infer saved/cart address identity from street, GPS, category or PIN.
