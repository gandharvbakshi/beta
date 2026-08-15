# Swiggy MCP Claude Design QA

## Comparison target

- Source visual truth: Claude Design project `90ed260c-da30-407a-8110-091976a30d42`, file `Beta Instamart Cart Run.dc.html`, plus captures in `logs/claude_design_reference_20260813/`.
- Implementation: native Android `SwiggyOrderStepDialog`, captured through the debug-only `SwiggyDesignPreviewActivity` on the connected CPH2487 phone.
- States compared: address reassurance and whole-cart review. Searching, applying, verified, and address-mismatch states were also captured and inspected.

## Viewport and normalization

- Source capture: 1536 x 639 px Chrome presentation viewport. The Claude Design source is a responsive wide presentation containing the app state and design rationale.
- Implementation capture: 1240 x 2772 px physical Android portrait viewport at 560 dpi and font scale 1.0.
- CSS size / device scale factor: not applicable to the native Android implementation. The source presentation and physical phone do not share an aspect ratio, so the comparison was normalized by matching state, copy hierarchy, semantic surfaces, token colors, tap-target scale, and content order rather than claiming pixel-level coordinate equivalence.
- Final implementation evidence:
  - `logs/swiggy_design_implementation_20260813_pass4/address_reassurance.png`
  - `logs/swiggy_design_implementation_20260813_pass3/whole_cart_review_top.png`
  - `logs/swiggy_design_implementation_20260813_pass3/whole_cart_review_bottom.png`

## Full-view comparison evidence

- Address reassurance preserves the source hierarchy: step eyebrow, serif question, saved-address reassurance card, explicit accept/change/cancel controls, and spoken/live caption. The native portrait layout wraps more aggressively but retains the source grouping and emphasis.
- Whole-cart review preserves the source hierarchy and all eight requested lines. The first viewport introduces the cart and begins the list; the scrolled viewport shows the final lines, safety note, explicit cart mutation action, address change, cancel-without-change, and caption.
- Searching exposes two amber in-flight lanes and six queued rows in list order. It never labels an item found before a backend response.
- Applying uses one compact, non-cancellable update/readback state. Verified uses green requested-line cards and a separate amber provider-added gift card.

## Focused region comparison evidence

- Address card and actions: cream background, terracotta primary action, serif display type, green confirmation treatment, and 56dp-or-larger controls match the reference intent.
- Review rows and actions: quantity deltas remain visible per row; the primary action appears only after the complete scrollable list and safety note, matching the source's deliberate whole-cart review behavior.
- No source imagery or product photography is used in this flow. The existing Beta brand mark and native text controls are consistent with the shipped app's design system.

## Comparison history

### Pass 1

- [P2] The live caption extended beneath Android's navigation bar on the physical phone.
- Fix: applied system-bar insets to the full-screen root using `WindowInsetsCompat`, preserving minimum design padding while keeping content above system chrome.
- Post-fix evidence: `logs/swiggy_design_implementation_20260813_pass2/address_reassurance.png` and `logs/swiggy_design_implementation_20260813_pass2/whole_cart_review.png`.

### Pass 2

- [P1] The primary cart action was pinned below the scroll area and could be reached before the user reviewed all eight lines; the Claude Design places approval after the complete list.
- Fix: moved primary, secondary, tertiary, and live-caption controls into the scrollable content after the rows and safety note.
- Post-fix evidence: `logs/swiggy_design_implementation_20260813_pass3/whole_cart_review_top.png` and `logs/swiggy_design_implementation_20260813_pass3/whole_cart_review_bottom.png`.

### Pass 3

- No actionable P0, P1, or P2 differences remain.

## Required fidelity surfaces

- Fonts and typography: Beta's existing serif display and sans-serif body hierarchy are retained; wrapping is clean at the physical portrait width and no title or action truncates.
- Spacing and layout rhythm: 24dp page margins, rounded cards, consistent vertical gaps, and 56dp-or-larger action targets match the source intent. Long lists scroll without hiding their final controls.
- Colors and visual tokens: cream `#FAF7F2`, terracotta `#C2410C`, green success, and amber provider/in-progress treatments map directly to the source.
- Image quality and asset fidelity: the source contains no product imagery in these states, so no image substitution or quality drift is present.
- Copy and content: address TTL reassurance, two-at-a-time search, sequential ambiguity questions, whole-cart review, single apply, exact readback, provider-added gift separation, address mismatch, and no-checkout language are represented.
- Accessibility: the screen has one accessibility heading, a polite live region, explicit button labels, large targets, and system-inset safety. A manual TalkBack traversal remains a P3 follow-up test gap, not a visual or functional blocker.

## Findings

- No remaining P0, P1, or P2 findings.
- P3 follow-up: perform a manual TalkBack traversal with an accessibility service enabled and repeat at a larger system font scale.

## Implementation checklist

- [x] Replace stacked Swiggy `AlertDialog` screens with one full-screen step surface.
- [x] Keep Swiggy as the first session default while retaining Blinkit as an explicitly labelled beta choice.
- [x] Show address reassurance and fail closed on cart-address mismatch.
- [x] Make bounded two-search concurrency visible without inventing completed results.
- [x] Ask one product ambiguity at a time.
- [x] Put approval after the full cart review.
- [x] Apply at most once and show a non-cancellable readback state.
- [x] Separate provider-added free gifts/samples from Beta-requested lines.
- [x] Stop before checkout and payment.

final result: passed
