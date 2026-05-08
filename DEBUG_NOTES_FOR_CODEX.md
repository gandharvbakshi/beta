# Debug notes for Codex: "order pepsi" stalls and end-to-end add-to-cart bugs

This document is hand-off notes for an LLM coding agent (Codex). It describes
why the user-typed instruction "order pepsi" currently stalls in the Android
app, plus a list of robustness issues that will block the end-to-end flow of
typing "order pepsi" in the overlay and having the agent navigate Blinkit,
locate Pepsi (one unit, the most common single SKU), and add it to cart.

Repos involved:
- Android app: `D:/Projects/beta`
- Backend (FastAPI + Vision API + workflow controller): `D:/Projects/beta backend`

Key files referenced below:
- Android: `app/src/main/java/com/example/beta/ScreenCaptureService.kt`
- Android: `app/src/main/java/com/example/beta/BackendProcessing.kt`
- Android: `app/src/main/java/com/example/beta/ActionExecutor.kt`
- Android: `app/src/main/java/com/example/beta/MyAccessibilityService.kt`
- Backend: `app.py`
- Backend: `Screenshot_experiments.py` (uncommitted edits to `locate_search_bar`)

---

## TL;DR — why "order pepsi" stalls right now

The single most likely cause is two recent changes (one in backend, one in
Android) colliding:

1. The latest uncommitted edit in
   `Screenshot_experiments.py::locate_search_bar` rewrote the step-1 response
   so its `action_target` literally contains the substring `"and type 'pepsi'"`,
   and now also ships a `text_to_type: "pepsi"` plus
   `coordinates: {x: img_w // 2, y: <ocr_y>}` (i.e. x is forced to image-center,
   no longer the OCR center).
2. A new method `typeAfterSearchClickIfRequested` was added to
   `ActionExecutor.kt` and is called after every search-bar click.

Result: on step 1 the app
- clicks at image-center x (which can miss a search bar that is not full-width),
- types `pepsi` once via `typeAfterSearchClickIfRequested`
  (`ActionExecutor.kt`, around line 150),
- types `pepsi` AGAIN via the "suggestType" branch in `BackendProcessing.kt`
  (lines ~700-714) because `actionTarget.contains("type", ignoreCase = true)`
  is true,
- and only then schedules `triggerNextAction()`.

If either click misses or one of the two typings clobbers the focused field,
the search activity is left in an inconsistent state and step 2 never gets a
useful screen, so the user sees: "it waits and then nothing happens."

Concrete evidence:
- `runs/20260507_03534*` (working today around 09:23 IST) → step 1 returned
  `coordinates: {x: 204, y: 387}`, `action_target: "Tap the search bar"`, no
  `text_to_type`. Sequence progressed steps 1 → 2 → 3 → 4 → 5 → 6.
- `runs/20260507_07*` (broken since around 12:12 IST) → step 1 returned
  `coordinates: {x: 540, y: 387}`,
  `action_target: "Tap the search bar and type 'pepsi'"`,
  `text_to_type: "pepsi"`. Every test stays at step 1 (`history=0`).
- The diff matches exactly the uncommitted edit in
  `git diff HEAD Screenshot_experiments.py`.

Backend log excerpt (`errors.txt`) confirms the request reached the server,
the model returned a search-bar match (confidence 0.7745), the response was
`200 OK`, and the Android side never came back with a step-2 request.

Backend log also shows `Tree data length: 0` for "order pepsi", which is a
separate Android-side race (see S4 below).

---

## Group 1 — Why step 1 stalls and step 2 never fires

### S1. One source of truth for typing after a search-bar click

- File: `app/src/main/java/com/example/beta/BackendProcessing.kt`,
  lines ~700-714.
- Today the secondary "suggestType" type attempt fires whenever
  `action_type == "type"` OR `actionTarget` contains the word `"type"`. With
  the new backend response that substring is always present, so we type twice.
- Suggested check: invoke the secondary `typeTextIntoFocusedField(...)` only
  when `recommendedAction.optString("action_type")` equals `"type"`
  (case-insensitive). Drop the substring check on `action_target`.

### S2. Backend should not bundle "click + embedded type hint" in step 1

- File: `Screenshot_experiments.py::locate_search_bar` (uncommitted edits).
- Either revert step 1 to the previous shape
  (`action_target: "Tap the search bar"`, no `text_to_type`,
  `coordinates` = OCR center), or keep `text_to_type` ONLY when the explicit
  search-focused heuristic decides to switch `action_type` to `"type"`.
  Don't have step 1 say "click here AND type 'pepsi'".

### S3. Don't override OCR-detected x with `img_w // 2`

- Same file/function. Use the OCR bounding box center clamped inside the
  matched element. Many apps' search bars are not centered or do not span the
  full width.

### S4. Tree data is empty (`Tree data length: 0`) — race in `submitInstruction`

- File: `app/src/main/java/com/example/beta/ScreenCaptureService.kt::submitInstruction`,
  lines 1525-1610.
- Two concurrent `postDelayed` blocks: tree capture at +800ms, screenshot at
  +500ms. The screenshot block fires first and reads `currentTreeData` while
  it is still null.
- Suggested fix: trigger
  `enableScreenshots(); pendingScreenshot = true; triggerScreenshot()` INSIDE
  the +800ms tree-capture continuation, after `currentTreeData` and
  `currentAppName` are set. Remove the separate +500ms block.

### S5. `nextActionReceiver` registration vs broadcast manager mismatch

- Files: `ScreenCaptureService.kt`, lines 295-317;
  `BackendProcessing.kt`, lines 178-192.
- `BackendProcessing.triggerNextAction()` fires both `sendBroadcast` (global)
  and `LocalBroadcastManager.sendBroadcast` (local). The receiver is
  registered on `LocalBroadcastManager` for non-emulator and via
  `registerEmulatorReceiver()` for emulator.
- Add a 3500ms watchdog: if `nextActionReceiver` did not fire after
  `triggerNextAction()`, call `triggerNextActionInSequence()` directly. Also
  log which manager actually delivered the broadcast.

### S6. SCS's own `isActionSequenceActive` / `originalInputText` are not set in `submitInstruction`

- File: `ScreenCaptureService.kt`, fields at lines 74-75; setter only inside
  `triggerNextActionInSequence` (lines 1622-1623).
- The first-pass screenshot processor at lines 1071-1095 checks SCS's
  `isActionSequenceActive` and clears
  `currentTreeData / currentInputText / currentAppName` because that flag is
  still false on step 1. Subsequent steps lose context.
- Suggested fix: set `isActionSequenceActive = true` and
  `originalInputText = inputText` at the top of `submitInstruction` (or move
  the clearing logic to read `BackendProcessing.isSequenceActive()` instead).

### S7. After-click typing window is too short

- File: `ActionExecutor.kt::typeAfterSearchClickIfRequested`. Today it does a
  flat `Thread.sleep(350)`.
- Replace with a poll: up to 1500ms, every 100ms, check
  `findFocusedEditable(rootInActiveWindow)`. Only call `ACTION_SET_TEXT` when
  a focused EditText is found. If still none, log clearly and let step 2
  handle it.

### S8. Add `KEYCODE_ENTER` / IME action after `SET_TEXT`

- Some Blinkit search EditTexts do not fire live filtering on programmatic
  `SET_TEXT` alone. Dispatch `ACTION_IME_ENTER` (API 30+) or a synthesized
  ENTER key; otherwise the results list never opens and the next screenshot
  looks identical to the previous.

### S9. Coordinate scaling double-checks

- File: `ActionExecutor.kt::performClickByCoordinates`, lines 1137-1318.
- Confirm `getLastScreenshotDimensions()` matches the EXACT JPEG that was
  uploaded (compress quality, padding crop on `rowPadding > 0`). On the
  emulator path the bitmap config flipped between `RGB_565` and `ARGB_8888`
  in the last commit; verify the JPEG resolution being uploaded matches the
  dimensions stored. If they do not match, `scaleX` / `scaleY` are wrong and
  the tap lands off the search bar.

### S10. Confidence gate is brittle for OCR

- File: `app.py`, lines 887-957. A 0.70 hard floor with HTTP 422 means a 0.69
  OCR confidence dumps a generic toast `"Something went wrong"` with no Toast
  hint to the user.
- Lower the floor for `force_first_step` to about 0.55 OR return a usable
  response with `warnings: ["low_confidence"]` instead of 422.

### S11. Double action-history payload

- File: `BackendProcessing.kt::processScreenshotWithInput`, lines 262-299.
- `action_history_json` (from `sessionContext.actionHistory`) AND
  `action_history` (from BackendProcessing's local list) are both sent.
  Backend may pick one and miss prior steps.
- Suggested fix: send only `action_history_json` (sessionContext is the
  source of truth).

### S12. `bounding_box` is missing in the new response

- File: `BackendProcessing.kt`, lines 514-518. Pure cosmetic (used for logging
  only), but `DebugLogger.logBackendResponse` is being called with
  `width = 0, height = 0` everywhere, masking real layout problems. Either
  always emit `bounding_box` from backend or stop logging zeros.

---

## Group 2 — Robustness bugs that will bite even after step 1 works

### R1. Sponsored / promoted result mistaken for "Pepsi"

- `Screenshot_experiments.py::target_visible_in_words` matches any occurrence
  of the token across the whole OCR. After typing `pepsi` Blinkit shows a
  "Sponsored" row at top with competitor SKUs (e.g. Sting, Mountain Dew)
  whose descriptions mention "vs Pepsi".
- Restrict matching to OCR words inside a product card bounding box AND
  require the card's leading text token to begin with the brand. Detect the
  word "Sponsored" near the card and skip it.

### R2. Multipack false negatives

- `has_multipack_risk_near_target` likely uses simple keywords. Blinkit shows
  variants like `Pepsi 250ml × 6`, `Pack of 6`, `Mega 6`, `Combo`,
  `Family Pack`, `Bumper`, `Multipack`.
- Expand regex:
  `\b(pack of|pack|case of|combo|multi-?pack|family pack|mega|x\s*\d+|×\s*\d+)\b`.
  Refuse to ADD unless a single-unit volume tag is present on the same card
  line (`\b(\d+\s?(ml|l|g|kg))\b`) AND no multiplier appears.

### R3. Wishlist / heart misclick on ADD

- `ActionExecutor.kt::performAddButtonClickWithValidation` retries with
  arbitrary pixel offsets (50/30 etc.). On dense screens these deltas are
  tiny and on tablets they are still small.
- Prefer accessibility-tree path: find the chosen product card's bounding box,
  then locate `android.widget.Button` / clickable `TextView` whose text equals
  `"ADD"` inside it. Tap the node, not coordinates. Verify post-tap by
  checking the cart count badge change; if unchanged, treat as a misclick and
  try the next strategy.

### R4. Quantity stepper after item already in cart

- After first add, "ADD" becomes "− 1 +". If the user re-runs "order pepsi",
  target detection still says ADD and we fail.
- Detect the stepper (any of `−`, `-`, `+`, `1` clustered in the same card
  region). If present in the chosen card, treat as success
  (`item_already_in_cart`) and short-circuit to "View cart" + verify.

### R5. Out of stock / "Notify Me"

- Skip cards where OCR shows `Out of stock`, `Notify Me`, `Sold out`. Do not
  tap the Notify button.

### R6. Variant modal disambiguation

- `decide_order_workflow` `ui_state == "modal"` opens a "single safe variant"
  search. With multiple flavours
  (`Pepsi`, `Pepsi Black`, `Pepsi Diet`, `Pepsi Zero`), prefer the one whose
  card title equals the query token exactly. If two cards tie, abort and ask
  the user.

### R7. Address gate / login wall

- No handler for "Confirm delivery address", "Add address", "Login",
  "Sign up", "Use current location".
- Maintain a small popup whitelist (auto-dismiss): `Skip`, `Not now`, `Close`,
  `Dismiss`, `Maybe later`, `×`, `Got it`, `OK`. And a blacklist that stops
  the sequence with a Toast: `Login`, `Sign up`, `Add address`, `Pay now`,
  `Verify`. Without this, the workflow will quietly keep retapping the wrong
  button.

### R8. Generic popup handler in Android is missing

- Backend has `blocking_popup_visible`. Android has no symmetric "please
  dismiss this overlay" path; it just relies on the Vision API to find the
  close button. Add a deterministic close-button finder (text match on the
  whitelist above + small dismiss-icon resource ID list) on every screenshot
  before letting the order workflow run.

### R9. Cart-content verification

- When `awaiting_confirmation = true` arrives, Android shows the
  `"Cart is ready"` toast and ends. There is no check that the cart line item
  actually contains the user's token (OCR could have matched a
  "Pepsi-flavoured" SKU we did not intend).
- Verify the query token literally appears inside a cart line-item region
  before declaring success.

### R10. Repeat-action loop guard is weak

- If two backend rounds in a row return the same
  `(action_type, action_target, coords ±50px)` and the OCR snapshot hash has
  not changed, do NOT re-dispatch. Force `scroll_attempt++` or stop with a
  "Got stuck" toast.

### R11. Per-action-type confidence policy

- The single 0.70 floor is wrong for "ADD" tiles (small bold word, low OCR
  score). Use a table: `ADD` and step buttons 0.55, `type/scroll` 0.70,
  `checkout finalize` 0.85.

### R12. Hardcoded package filter

- File: `MyAccessibilityService.onAccessibilityEvent`, line 90. Only
  `com.grofers.customerapp` events trigger tree capture. System dialogs
  (`com.android.systemui`, `com.google.android.permissioncontroller`) are
  ignored, so an OS-level confirm dialog freezes us. Allow tree capture for
  those packages too, OR fall back to `rootInActiveWindow` when no Blinkit
  window is found.

### R13. Two `ADD` candidates: same row vs same column

- If multiple product cards have ADD buttons visible (typical 2-column
  layout), the model often picks the right one but the click can land on the
  wrong card if the layout has uneven heights. Pin the click to the chosen
  card's bounds rather than absolute coordinates.

### R14. EditText `SET_TEXT` vs IME-driven autocomplete

- See S8. Without an explicit IME ENTER, the search results recycler may not
  refresh, causing the second backend round to receive the SAME home screen
  (same OCR hash) and the workflow stalls forever.

### R15. Status-bar offset / overlay rect

- File: `ActionExecutor.kt`, lines 1219-1244 — overlay deflection adds
  `overlayRect.height() + 24px` to y. If `getOverlayRect()` returns a rect
  that overlaps the search bar (e.g. the overlay positioned higher than
  expected), every search-bar tap is deflected below the search bar. Log the
  exact rect each click and assert it never overlaps
  `[0..displayHeight*0.18]`.

---

## Suggested fix order for the next debug pass

1. Revert the `Screenshot_experiments.py::locate_search_bar` step-1 changes
   (S2 / S3) and re-run "order pepsi".
2. Then enable S1 (single typing source) and S4 (tree-capture race) together.
3. Then layer R1, R3, R4 before tackling cart verification (R9).
4. Address popup / login walls (R7, R8) before exposing this to non-developer
   users.
