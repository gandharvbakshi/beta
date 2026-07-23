# Beta grocery demo narration

Target length: about 2 minutes.

## Script

[Beta home screen]

This is Beta, a voice-first Android grocery assistant built to make online ordering easier for older people and anyone who finds shopping apps difficult to navigate.

[Show the large provider choices and voice action]

The interaction is intentionally simple: the user speaks or types what they need, chooses a grocery provider for the session, and Beta builds the cart. The user remains in control and decides what happens next.

[Blinkit: butter flow]

On the phone, Beta handles the large, readable interface, voice and text input, provider choice, session memory, and clear progress updates. It uses Android screen capture to understand the visible pixels and AccessibilityService to understand the structured controls and text on screen.

[Blinkit: Vicks and coffee flows]

The hosted staging backend receives the screenshot, accessibility tree, and order intent. It combines Google Cloud Vision OCR with deterministic state and safety rules. Limited model reasoning is available only as a fallback. The backend returns one constrained action at a time, and Beta verifies that the intended product and quantity actually reached the cart.

Here, Beta adds three exact products on Blinkit: Amul salted butter, 100 grams; Vicks VapoRub, 25 millilitres; and Nescafe Classic instant coffee, 200 grams.

[Show each exact product and quantity, then its cart increment]

The safety boundary is deliberate: Beta only helps build and verify the cart. It never proceeds to checkout or payment.

[Return to Beta, select Swiggy Instamart, then open it]

For this demonstration, Swiggy Instamart is screen-assisted and open-only because official Swiggy staging credentials have not yet arrived.

Once the Swiggy MCP integration is enabled, the sensitive authentication and MCP credentials will stay on the backend. Beta will use delegated OAuth with secure per-user tokens, show simple Connect, Ready, and Reconnect states, and use purchase history or saved preferences where Swiggy exposes them. For every change, it will read the current cart, calculate the exact difference, show and speak that difference, update once, and read the cart back to verify it.

[Closing card]

Beta is already approved and available on Google Play. Broader device testing and the full Swiggy MCP integration are still ahead, but the goal remains the same: make grocery ordering calm, understandable, and safer for older users.

## Recording notes

- Stop at verified cart state; never enter checkout or payment.
- Keep each exact quantity visible long enough to read.
- End immediately after Swiggy Instamart opens.
- Do not show delivery addresses, notifications, credentials, or private account details.
