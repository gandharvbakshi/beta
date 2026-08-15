# Beta: a voice-first personal commerce assistant

Beta is an early Android prototype exploring what a voice-first assistant for personal commerce could feel like. The current cart-only grocery flow supports Swiggy Instamart and Blinkit, with the app stopping before checkout and payment so the user can review the cart.

## Status

This project is an early prototype. It is not production-ready and parts of the experience rely on experimentation and iteration.

## Website

`betaapp.live` is reserved for this project, but it is not live yet.

## What the current prototype explores

- Voice and text input for intent capture
- Screen understanding to interpret what is currently on the device
- OCR for extracting visible text from the UI
- Accessibility tree inspection for structured UI context
- Assisted ordering flows that guide the user step by step
- Cart-building support for Swiggy Instamart and Blinkit

## Swiggy MCP Integration (Staged)

The Android and backend support for Swiggy MCP is implemented but has not yet completed live verification. When Swiggy Instamart is selected, the app now uses the direct MCP experience by default and offers the existing screen-assisted path as an explicit, temporary session fallback. Blinkit keeps its existing screen-assisted behavior and is explicitly labelled as beta in the app.

Where a supported provider connection is available, MCP or provider APIs may be used for:

- Search and discovery
- Product recommendations
- Cart planning and confirmed updates
- Cart readback so the user can review the result

The app does not automate checkout, payment, or order placement. Live Swiggy MCP activation and store testing remain separate approval-gated steps.

## Architecture

### 1) Current prototype architecture

- **Android app UI**: captures voice/text input and presents guided steps
- **Perception layer**: combines screenshot-based OCR and accessibility tree inspection
- **Intent and flow logic**: maps user intent to a sequence of assisted steps
- **Action layer (prototype)**: interacts with on-screen UI elements when needed, with fallbacks and human-in-the-loop prompts

### 2) Target MCP architecture

- **Android app UI**: voice-first experience, confirmations, and review screens
- **Intent and flow logic**: determines the next best action and required confirmations
- **MCP/API client**: performs commerce actions through supported grocery-provider tools
- **Validation and guardrails**: checks the proposed cart and constraints before making cart changes
- **Review boundary**: stops after cart readback so the user remains in control of checkout and payment

### 3) User flow

1. User speaks or types an intent, for example "Order a spicy paneer bowl under 250".
2. The assistant clarifies constraints if needed, for example location, budget, dietary preferences.
3. The assistant gathers context from the current screen or, for Swiggy, via MCP.
4. The assistant proposes a short list or a recommended choice.
5. The assistant proposes cart changes for confirmation.
6. After confirmation, the assistant updates the cart and presents a readback.
7. The automated flow stops before checkout and payment.

### 4) Privacy and consent principles

- **User control first**: no order is placed without explicit confirmation.
- **Data minimization**: collect only what is needed to complete the task.
- **Transparency**: clearly indicate when screen data (OCR or accessibility tree) is being used.
- **Local-first where possible**: prefer on-device processing when feasible.
- **Sensitive data handling**: avoid storing screenshots, extracted text, or identifiers unless required for debugging, and make retention short and opt-in.

### 5) Setup instructions

This repository contains an early Android prototype.

- Install Android Studio (latest stable recommended).
- Open the project in Android Studio.
- Sync Gradle.
- Run the app on an emulator or a connected Android device.

If the project uses local keys or environment configuration, keep them out of git and follow any existing sample configuration files in the repo.

### 6) Roadmap

- Stabilize the voice and text input experience
- Improve screen understanding quality (OCR and accessibility parsing)
- Add robust guided flows with better error handling
- Introduce a review-and-confirmation summary screen for all ordering actions
- Complete approval-gated live verification of Swiggy MCP search and cart updates
- Add privacy controls and clear consent UX for any captured screen context
