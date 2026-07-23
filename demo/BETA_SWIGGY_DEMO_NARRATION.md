# Beta grocery demo narration

Current voiced cut: about 104 seconds.

## Script

[Beta home screen]

This is Beta, an Android grocery assistant built to make online ordering easier for older people and anyone who finds shopping apps difficult to navigate.

[Show the Beta entry card and type “Butter, Vicks and coffee”]

Today, the interaction can start in either of two simple ways: the user types
what they need into Beta, as shown here, or says the same request by voice.
Beta then guides the cart-building flow. The user remains in control and
decides what happens next.

[Blinkit: butter flow]

On the phone, Beta handles the large, readable interface, voice and text input, provider choice, session memory, and clear progress updates. It uses Android screen capture to understand the visible pixels and AccessibilityService to understand the structured controls and text on screen.

[Blinkit: Vicks and coffee flows]

The hosted staging backend receives the screenshot, accessibility tree, and order intent. It combines Google Cloud Vision OCR with deterministic state and safety rules. Limited model reasoning is available only as a fallback. The backend returns one constrained action at a time, and Beta verifies that the intended product and quantity actually reached the cart.

In the recorded take, Beta clearly shows a live butter success at Amul salted butter, 100 grams. It also reaches the exact Vicks VapoRub 25 millilitre product and increments the cart, then stops safely while the keyboard remains open. The coffee attempt is shown as a safe stall, not as a completed cart action. Separate earlier live rehearsals did pass all three exact items.

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
- This full-header cut intentionally keeps the grocery-app header visible under
  the owner's explicit approval. Mask delivery/location text in any other
  external cut; never show credentials, private notifications, or payment data.
