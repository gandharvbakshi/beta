# Beta: a voice-first personal commerce assistant

Beta is an early Android prototype exploring what a voice-first assistant for personal commerce could feel like. The focus is on helping users complete ordering tasks faster and more confidently through voice and guided interactions.

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

## Planned Swiggy MCP Integration

Swiggy MCP is not integrated yet.

The plan is to integrate Swiggy MCP to replace brittle screen-based automation with reliable, explicit APIs for commerce actions. Once integrated, MCP will be used for:

- Search and discovery
- Cart creation and updates
- Checkout validation (address, timing, fees, availability)
- Order placement only after explicit user confirmation

Until MCP is integrated, any ordering assistance is best-effort and should be treated as prototype behavior.

## Architecture

### 1) Current prototype architecture

- **Android app UI**: captures voice/text input and presents guided steps
- **Perception layer**: combines screenshot-based OCR and accessibility tree inspection
- **Intent and flow logic**: maps user intent to a sequence of assisted steps
- **Action layer (prototype)**: interacts with on-screen UI elements when needed, with fallbacks and human-in-the-loop prompts

### 2) Target MCP architecture

- **Android app UI**: voice-first experience, confirmations, and review screens
- **Intent and flow logic**: determines the next best action and required confirmations
- **MCP client**: performs commerce actions via Swiggy MCP tools
- **Validation and guardrails**: checks totals, address, delivery slot, and constraints before showing a final confirmation
- **Explicit confirmation gate**: order placement happens only after the user approves the final summary

### 3) User flow

1. User speaks or types an intent, for example "Order a spicy paneer bowl under 250".
2. The assistant clarifies constraints if needed, for example location, budget, dietary preferences.
3. The assistant gathers context from the current screen (prototype) or via MCP (target).
4. The assistant proposes a short list or a recommended choice.
5. The assistant builds the cart and validates checkout details.
6. The assistant presents a final review with total cost and key details.
7. User explicitly confirms, then the order is placed.

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
- Integrate Swiggy MCP for search, cart, checkout validation, and order placement
- Add privacy controls and clear consent UX for any captured screen context

