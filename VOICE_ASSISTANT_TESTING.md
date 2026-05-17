# Voice Assistant Testing

Beta now has a first-pass voice order entry point in the Android app. It uses
Android speech recognition with `en-IN` preference and speaks short prompts with
Android TextToSpeech using Indian English locale.

## Manual App Test

1. Start screen capture.
2. Tap `Voice order`.
3. Say a grocery instruction, for example `order pencil`.
4. Confirm the recognized text is logged as `VOICE_INSTRUCTION_RECOGNIZED`.
5. Confirm the normal cart-only automation flow starts.

## Synthetic Audio Harness

The backend repo includes `voice_instruction_harness.py`.

Local text-only check:

```powershell
python voice_instruction_harness.py --evaluate-only "order pencil" "order pencil"
```

OpenAI audio roundtrip, when `OPENAI_API_KEY` is available:

```powershell
python voice_instruction_harness.py --accent Indian --instruction "order pencil"
```

This generates synthetic speech and transcribes it back to measure word error
rate. It does not replace real-device testing with Android/Google speech
recognition, because the Android recognizer runs on-device/Google services.

## Pass Criteria

- Common instructions should transcribe with word error rate <= 0.15.
- Product words and quantities must be preserved.
- If recognition is uncertain, the app should prefer confirmation before
  running the order flow in older-user mode.
