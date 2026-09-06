# Analytics event contract

This app tracks analytics at the install/device level, not as a person identity. It does not invent an authenticated UID, and it does not enable Ads consent.

`first_open_at_ms` is the local install-start timestamp. Keep it as the original first-open reference and derive `days_since_first_open` from that value, bounded to a safe range before emission.

Activation is also local state. Store the first verified cart time and item count locally, but treat `activation_completed` as a verification event, not proof of a person-level identity.

Retention events are exact milestones only:

- `retention_d1`
- `retention_d5`
- `retention_d7`
- `retention_d28`

`retention_w1` is a separate umbrella event for any return in days 1 through 7. It may emit on the same day as an exact milestone.

Retention emission uses versioned storage flags so old flags do not block new milestone emissions.

`consent_delayed` is a local best-effort signal: an explicit previous analytics denial is persisted; first-open backfill also checks whether the original first open was on an earlier UTC day. Merely showing the initial consent choice does not imply delayed consent.

Cart success events are per-session / per-verification. Do not blur them into once-per-install cohort claims.
