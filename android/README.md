# YBT Army for Android

The Android app is a separate native Kotlin/Jetpack Compose client. It keeps inference on the device using the CPU backend from [llama.cpp](https://github.com/ggml-org/llama.cpp). No remote AI API or paid inference server is used.

## Download model separately

The APK does not contain model weights. On first launch, tap **Download AI model**. The app finds the latest public `android-v*` GitHub Release, downloads `YBT-Army-135M-Q4_K_M.gguf`, verifies its SHA-256 sidecar, and stores it in app-private storage. Once the model is installed, chat generation is offline. Internet is only required for the initial model download and future model updates.

## Build

Requirements: Android Studio with Android SDK 36, NDK 27.2.12479018, CMake 3.22.1, JDK 17, and Gradle 8.13.

Open the `android/` directory in Android Studio and sync Gradle. The CMake configuration fetches a pinned llama.cpp source revision and builds the native runtime for `arm64-v8a`.

For command-line builds from this directory:

**Windows (PowerShell):**

```powershell
.\\gradlew.bat testDebugUnitTest assembleDebug
```

**macOS / Linux:**

```bash
sh ./gradlew testDebugUnitTest assembleDebug
```

The debug APK is at `app/build/outputs/apk/debug/app-debug.apk`.

## Release automation

Push a tag matching `android-v*` after the app and model conversion tests pass. The Android workflow builds the debug-signed APK, converts the Hugging Face checkpoint to GGUF, quantizes it to Q4_K_M, runs a local CLI smoke test, produces SHA-256, and attaches both the APK and model to one GitHub Release.

The downloaded debug APK is signed with the standard Android debug key. A Play Store / production release needs a private signing keystore and GitHub Actions secrets; never commit that keystore.

## Model compatibility

The source checkpoint declares `LlamaForCausalLM` but uses a GPT-2 tokenizer configuration. The GGUF conversion job is deliberately part of the release gate: a release is not published unless conversion and local llama.cpp inference both pass. The app and model artifacts are published separately.


## Known limitations

YBT-Army-135M has 134.5M parameters. It can generate fluent but generic or incorrect text and is not equivalent to a large instruction-tuned assistant. The Android client applies a concise instruction prompt and filters obvious leaked template placeholders, but prompt changes cannot replace model training. Verify important information independently.
