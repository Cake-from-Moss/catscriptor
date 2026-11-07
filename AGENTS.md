# AGENTS.md — catscriptor (Neuro-stenographer)

Reply to the team in Russian. Code and identifiers: English. All code comments, including KDoc: Russian.

Nuclear IT Hack (November 2026): Android app that turns audio recordings of conversations into a transcript with speakers and timestamps, **fully on-device**.

Judging: organizers install our APK, import 3 Russian recordings (cat dialogues), export the JSON and compare it with references (WER, RTF = processing time / audio duration). Points: transcription 40, diarization 25, timestamps 10, speed 10, functionality/UX 10, defense 5.

## Hard constraints

1. No network during processing; audio and text never leave the device.
2. The evaluation APK has all models bundled and works right after install, because automated tests run it (confirmed by the mentor). Downloading models is only an optional extra (see Scope), never a requirement.
3. No pre-made transcripts, mocks or hardcoded output in the main path; stubs only in unit tests.
4. A local LLM must be used in recognition: it corrects the ASR text (recognition errors, punctuation, casing); timestamps stay from the ASR. Team decision; do not remove it.
5. No dependencies that need the internet at runtime (analytics, cloud SDKs, Firebase). Pre-trained models and existing libraries are allowed.

## JSON export (strict)

```json
{"transcript": [{"speaker_id": "speaker_1", "start_ms": 1250, "end_ms": 4380, "text": "Добрый день!"}]}
```

`speaker_id` is `speaker_N`; `start_ms` and `end_ms` are integers from the recording start, `start_ms < end_ms`; utterances are sorted by `start_ms`. No renamed fields or extra wrappers. Any export change needs a schema test.

## Scope

MVP: import via SAF (`ActivityResultContracts.OpenDocument`), speech-to-text, diarization, utterance timestamps, transcript screen, export to text and JSON.

Only after the MVP is stable: real-time mode, microphone or system audio capture, tap an utterance to jump in a player, downloading models after install (UX extra; the bundled models stay mandatory).

Priority: 1) end-to-end pipeline on rough models, 2) ASR and diarization quality, 3) speed, 4) UX, 5) optional features.

## Stack

Kotlin, Compose, Gradle Kotlin DSL, version catalog; NDK 28.2.13676358, CMake, C++17, JNI in `app/src/main/cpp`; `arm64-v8a` only.

The Compose plugin version (`kotlin` in the catalog) must equal the Kotlin bundled with AGP (2.2.10 with AGP 9.5.1).

Models:

- ASR (team choice): sherpa-onnx + GigaAM. Pick the v3 variant (CTC or transducer, with or without punctuation) by WER and RTF.
- Not final: VAD Silero; diarization pyannote segmentation + 3D-Speaker/NeMo embeddings + clustering (sherpa-onnx); LLM small GGUF q4 (e.g. Qwen2.5 0.5B–1.5B) via llama.cpp or MediaPipe.

## Pipeline

Each stage is a separate module behind an interface:

1. Decode any format to 16 kHz mono PCM (`MediaExtractor` + `MediaCodec`)
2. VAD
3. ASR per segment: text + timestamps
4. Diarization
5. Merge: assign speakers by interval overlap, join adjacent utterances of one speaker
6. LLM correction of the ASR text
7. Result model and export

- ASR sits behind a `TranscriptionBackend` interface with fallback (reference, do not copy blindly: https://github.com/grigorevmp/VoiceScribe).
- Heavy work runs in a Foreground Service; progress reaches the UI through `StateFlow`.
- Load one model at a time and release it after its stage, unless holding several is proven safe. Process long recordings in chunks.
- Layers: `ui` -> `viewmodel` -> `domain/pipeline` -> `native/models`; no business logic in the UI.

## Models and git

- Model files go in `app/src/main/assets/models/` and are never committed (gitignored). `noCompress` already covers `bin`, `gguf`, `onnx`.
- README keeps a table: model, download link, folder, checksum.
- Never commit `local.properties`, `.idea/`, build output, `*.jks`, `*.keystore`.

## Build

```bash
./gradlew assembleDebug assembleRelease test
~/Library/Android/sdk/platform-tools/adb install -r app/build/outputs/apk/debug/app-debug.apk
```

## Measuring

- WER: `jiwer` against references, with the same normalization on both sides (case, punctuation, ё/е).
- Measure speed on a real phone only. Record model, quantization, device and thread count.

## Submission

Submission goes through a separate repository following https://github.com/grigorevmp/hacathon_template; this repo need not match it. Required: APK, source, install and usage instructions, architecture and models, limitations, test results, video demo. Keep `docs/` and the README current so they can be carried over.

## Agent rules

- Before changing the architecture, model choice or export format, describe the plan briefly and ask for confirmation.
- Work in small steps; run the build and tests before saying something works.
- Check library versions and APIs in docs or source; if unsure, say so.
- The team defends this code: explain non-obvious decisions (JNI calls, parameter choices, bottlenecks).
- Do not delete files or rewrite git history without an explicit request.

## Open questions for the organizers

- Evaluation device (chipset, RAM)?
- Maximum APK size with models?
