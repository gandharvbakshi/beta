# Owner pilot and release status — 20 November 2026

## What is in this GitHub checkpoint

Android feedback no longer treats any successful HTTP response as proof of delivery.
The service must explicitly acknowledge the report and return a nonblank string receipt.
The client does not follow redirects or automatically retry connection failures. It does
not write the submitted note, response, receipt ID, or network exception text to logs.
This checkpoint does not change grocery matching, the cart, checkout, the backend, or Play.

## Verification and limits

- On an isolated Android emulator, an offline send kept the editable draft; an exact
  database query returned zero matching records. After reconnecting, one manual retry
  dismissed the form and the private service contained exactly one matching synthetic note.
  This was not a shopping transaction. This test used the **candidate** owner-example form.
- The Android platform receipt test accepts the service response and rejects ten invalid
  cases, including missing fields, false, wrong types and a blank receipt ID. The app
  and instrumentation APKs built. The positive hosted send preceded the final isolated
  receipt-reader hardening; the platform test ran on the final reader.
- In the working candidate, four focused feedback tests passed. Two offline mocked
  checkout tests distinguish acknowledged placement from an unsubmitted attempt;
  no live order was placed. Candidate-only features are not implied by this GitHub update.
- Full Android candidate run: **2,786 passed, 27 failed, 42 skipped** (2,855 total).
  The 27 failures affect seller quantity/contents, product identity, exclusion and dietary
  handling. A high unit-test pass rate is not an estimate of shopping success.
- November 19 fresh controlled basket panel: OpenAI 5/16 ordinary baskets, DeepSeek 2/16;
  neither completed the four mixed-safety baskets with all valid siblings. DeepSeek
  proposed eight four-packs for eight individual pieces in a preview. No purchase.
  The frozen panel is now spent; no percentage is claimed for live user orders.

## What the owner can use, and how to learn

Play Open Testing last verified at **0.3.4 (21)**; not a new pilot release.
The signed Play 21 release came from `87585b1`. The November 20 GitHub shopping
preview is **not** that Play source plus a feedback-only fix: 88 Android runtime
and resource files differ between that release commit and `e34aae6`. The version
name alone cannot identify a side-loaded build. Do not upload an old bundle or
relabel the preview as a maintenance release.
The working candidate's opt-in Analytics counts a committed list edit, phrase edit,
product change, or removal, not individual keystrokes, text or product names. An
optional prompt follows acknowledged order placement. Raw tester examples are only
sent on an explicit action to the private feedback service, never to Analytics or Ads.
Corrections made in native Swiggy are not visible to Beta. These candidate changes
have not been published to Play.

Triage explicit examples by requested list, suggested list, intended correction and
result. Count **whole baskets correct before editing** separately from corrected
baskets, safe holds, unavailable stock, and unsafe proposals. Review quantity/diet
errors first. Turn each distinct failure mechanism into a de-identified test with a
similar negative control; never train automatically on a correction or count replay
of that example as new evidence. Treat a lack of feedback as unknown, not success.

## Latest shopping check

On November 20's genuinely new simulated stock panel, Gemini and DeepSeek each
completed **0 of 3 ordinary baskets and 0 of 1 mixed basket**. Neither made an
incorrect selection on that panel; both left out available items. This is a small
diagnostic sample, not a success rate for the app on Play.

A subsequent private change taught the request reader that an ordinary product
noun can be what the user is counting. On two **previously seen** five-item
mixed baskets, DeepSeek selected 2/4 and 3/3 suitable items, holding all
unsuitable items; Gemini selected none. Both read the three simple quantities
in the second basket. Gemini's later seller check treated the listing’s “1 piece”
as a size the shopper had asked for. The host safely held the items. Offline,
changing only those three size readings recovered the exact three products,
quantities and prices while preserving both dietary holds. This diagnoses
the issue; it is not a passing shopping run. Keep testing
that distinction before changing the shopping release or advertising improved
matching. No orders, payments or cart changes were involved.

## Release gates

1. Bind what is being counted (a piece or a pack) separately from the seller's pack
   contents. Preserve identity and dietary evidence without special-casing individual SKUs.
2. Qualify genuinely unseen whole baskets, including mixed-safety cases and long lists.
   Require at least 90% usable whole baskets with no known silent quantity or diet errors.
3. Verify the exact release configuration on the hosted service and physical phone.
   Check signing, manifest, privacy, listing and the Play track after submission.
   A GitHub commit, an uploaded bundle and Google review approval are different events.
