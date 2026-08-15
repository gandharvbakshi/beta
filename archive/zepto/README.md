# Zepto support archive

Zepto is intentionally absent from the active Beta product as of 2026-08-15.
This archive preserves the last pre-removal implementation so it can be assessed
and deliberately reintroduced later without leaving dormant Zepto behavior in
the shipping app.

## Canonical source snapshot

- Git commit: `ed662ccc104da7bb361b70338750f79bc921ed32`
- Archive branch: `codex/archive-zepto-support-2026-08-15`
- Historical engineering notes retained in this repository:
  - `ORDERING_FLOW_PLAN.md`
  - `ONBOARDING_A_NEW_COMMERCE_APP.md`

The snapshot contains the complete former implementation, including:

- provider model and routing in `CommerceProviderRouter.kt`
- package visibility in `AndroidManifest.xml`
- accessibility allowlists and foreground policy
- Zepto popup/search/cart safety logic in `ActionExecutor.kt`
- backend app identification and cart-surface checks
- provider UI and tests
- `scripts/run_zepto_flow_test.ps1`
- `scripts/zepto_address_preflight.ps1`

## Reintroduction rule

Do not restore the shared Kotlin files wholesale because Swiggy and Blinkit may
have changed since this snapshot. Diff each path against the archive branch and
port only the Zepto-specific hunks. Restore the two Zepto scripts directly,
then repeat onboarding, cart-only safety tests, Play disclosures, and review
asset generation before exposing Zepto in the provider selector.

All testing must stop before checkout and payment.
