# AGENTS.md — catscriptor (Neuro-stenographer)

Instructions for AI agents working in this repository. Team communication language: Russian (reply to the team in Russian unless asked otherwise). Code, identifiers and code comments: English.

## What this project is

Nuclear IT Hack hackathon (November 2026): an Android app that converts audio recordings of conversations into a structured transcript **fully on-device**, with automatic speaker separation and timestamps.

How it is judged: the organizers install our APK, import 3 test recordings (cat dialogues, Russian speech), run processing, export the JSON, and compare it with reference transcripts. They compute WER (text accuracy) and RTF (processing time / audio duration).

Scoring (100 points): transcription accuracy 40, diarization accuracy 25, timestamp accuracy 10, processing speed 10, functionality and UX 10, solution defense 5.

## Hard constraints (never violate)

1. **No network during processing.** Never send audio or text to external services, cloud APIs, remote servers or remote computers. The app must work fully offline.
2. **All models must be on the device** by the time of evaluation (bundled in the APK or in app data). No downloading models from the internet at first launch.
3. **Never replace model work with a pre-made transcript**, mocks or hardcoded output in the main path. Stubs are allowed only in unit tests.
4. **A local LLM must be used** (planned role: text post-processing, primarily punctuation and capitalization). Do not remove it from the pipeline.
5. Pre-trained models and existing libraries are allowed.
6. Do not add dependencies that need the internet at runtime (analytics, cloud SDKs, Firebase, etc.).

## Output format (strict)

The JSON export must match this schema. Do not rename fields, change types, or add wrappers that break the structure:

```json
{
  "transcript": [
    {
      "speaker_id": "speaker_1",
      "start_ms": 1250,
      "end_ms": 4380,
      "text": "Добрый день!"
    }
  ]
}
```

- `transcript`: array of utterances
- `speaker_id`: string (`speaker_1`, `speaker_2`, ...)
- `start_ms`, `end_ms`: integer, milliseconds from the start of the recording; `start_ms < end_ms`
- `text`: string

Utterances are sorted by `start_ms`. Any change to the export must come with a test that validates the schema.

## Required features (MVP)

- import audio from device storage (Storage Access Framework, `ActivityResultContracts.OpenDocument`)
- speech-to-text
- speaker separation (diarization)
- speaker timestamps and utterance timestamps
- display of the transcript in the app
- export to a text file and to JSON

## Optional features (only after the MVP is stable)

Real-time transcription (as an option), recording from the microphone inside the app, system audio capture, filtering of pauses and non-speech fragments (VAD), punctuation restoration, processing progress display, jumping to a phrase in the audio player by tapping an utterance.

## Tech stack

- Platform: Android, Kotlin, Jetpack Compose, Gradle Kotlin DSL, version catalog (`gradle/libs.versions.toml`)
- Native part: NDK + CMake, C++17, JNI bridge (folder `app/src/main/cpp`)
- ABI: `arm64-v8a` only
- Project name: `catscriptor`

Model choice is **not final yet**. Candidates, to be compared by WER and RTF on the same Russian recordings:

- ASR: whisper.cpp (GGML models, small / medium / large-v3-turbo, quantized) **or** sherpa-onnx with GigaAM
- VAD: Silero VAD
- Diarization: pyannote segmentation + speaker embeddings (3D-Speaker / NeMo) + clustering (sherpa-onnx)
- LLM: a small model (e.g. Qwen2.5 0.5B–1.5B, GGUF q4) via llama.cpp or MediaPipe

The team's reference (someone else's project: use as a guide, do not copy blindly): https://github.com/grigorevmp/VoiceScribe. It uses whisper.cpp as the main backend and LiteRT/QNN as an accelerated backend for Snapdragon devices, with automatic fallback to whisper.cpp.

> TODO (fill in once decided): final models, their sizes and paths, NDK version.

## Target architecture

The processing pipeline, with each stage as a separate module behind a clear interface:

1. **Decoding**: any format (mp3/m4a/wav/ogg) to 16 kHz mono PCM float/short (`MediaExtractor` + `MediaCodec`)
2. **VAD**: speech boundaries, removal of pauses and noise
3. **ASR**: per-segment recognition, text + timestamps
4. **Diarization**: who is speaking in each interval
5. **Merge**: match ASR utterances to speakers by interval overlap; merge adjacent utterances of the same speaker where reasonable
6. **LLM post-processing**: punctuation, casing
7. **Result model**: list of utterances, export to JSON and text

Principles:

- Put ASR behind a `TranscriptionBackend` interface with multiple implementations and automatic fallback (as in the reference). This lets us swap models without rewriting the rest.
- Heavy processing runs in a **Foreground Service** (otherwise Android kills the process when the app is backgrounded), with progress exposed to the UI through `StateFlow`.
- **Memory:** load models one at a time and release them after each stage; do not keep ASR, diarization and LLM in RAM together unless proven safe. Process long recordings in chunks.
- The UI contains no business logic. Layers: `ui` -> `viewmodel` -> `domain/pipeline` -> `native/models`.

## Repository layout

```
app/src/main/java|kotlin/   Kotlin code (UI, service, pipeline)
app/src/main/cpp/           native code, CMakeLists.txt, JNI
app/src/main/assets/        models (NOT committed, see below)
app/src/main/res/           resources
gradle/libs.versions.toml   dependency versions
docs/                       report materials (architecture, limitations, tests)
```

The report repository must follow the organizers' template: https://github.com/grigorevmp/hacathon_template. Required materials: APK, source code, installation and usage instructions, description of the architecture and models used, description of the solution's limitations, results of our own tests, a video demo. Update `docs/` and the README together with code changes.

## Models and git

- Model files (`*.gguf`, `*.onnx`, `*.bin`, `app/src/main/assets/models/`) are **not committed** (GitHub rejects files over 100 MB). They are in `.gitignore`.
- The README must have a table: which model, where to download it, which folder to put it in, checksum.
- The final APK with models is published in GitHub Releases.
- `build.gradle.kts` disables compression for model files: `androidResources { noCompress += listOf("bin", "gguf", "onnx") }`.
- Never commit: `local.properties`, `.idea/`, `build/`, `.cxx/`, `.externalNativeBuild/`, `*.jks`, `*.keystore`.

## Build and run

Commands (from the project root):

```bash
./gradlew assembleDebug          # debug APK
./gradlew assembleRelease        # release APK
./gradlew test                   # unit tests
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Requires: Android Studio, SDK, NDK (side by side), CMake. Measure speed only on a **real phone**, never on an emulator.

## How we measure quality

- Compute WER with the `jiwer` Python library against reference transcripts; normalize text the same way before comparing (case, punctuation, ё/е).
- Compute RTF = processing time / audio duration. Record the model, quantization, device and thread count.
- Every change of model or parameters must be measured before and after. Record results in `docs/` (a table) so they go into the report.
- Do not claim an improvement without a measurement.

## Rules for the agent

- Before large changes, briefly describe the plan and ask for confirmation if the change touches the architecture, the model choice or the export format.
- Work in small, verifiable steps. Run the build and tests after changes before saying that something works.
- Do not invent APIs or library versions: check the documentation or the source. If unsure, say so.
- The team must **understand the code** (this is asked about at the defense). Explain non-obvious decisions: what a JNI call does, why a parameter was chosen, where the bottleneck is.
- Do not delete files or rewrite git history without an explicit request.
- Do not touch secrets or signing keys, and never commit them.
- Work priority: 1) an end-to-end pipeline on rough models, 2) ASR and diarization quality (65 points), 3) speed, 4) UX, 5) optional features.

## Open questions for the organizers

- Is an LLM for post-processing enough, or must the ASR itself be LLM-based?
- Which device (chipset, RAM) is used for evaluation? This decides whether QNN acceleration is worth it.
- Maximum allowed APK size with models bundled.
