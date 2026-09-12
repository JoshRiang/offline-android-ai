# SLM Chat — on-device small-language-model chat for Android

Chat with free, openly-licensed small language models **fully on-device** using
[MediaPipe LLM Inference](https://ai.google.dev/edge/mediapipe/solutions/genai/llm_inference)
(`.task` files, GPU/CPU). Models download once (WorkManager + OkHttp), then the
app works completely offline. Conversations persist in Room; settings in DataStore.

## Features

- Chat screen with streaming tokens, conversation drawer, model download banner
- Settings screen: model catalog picker, download / cancel / delete with progress,
  temperature / top-K / top-P / max-tokens sliders, system prompt, GPU toggle,
  custom `.task` URL override, clear-all-chats
- Navigation Compose graph: `chat` ↔ `settings`
- Process-wide `SlmChatApp` composition root sharing one `LlmManager` / engine
  (the native LLM backend is single-session)

## Project layout

```
app/src/main/java/com/example/slmchat/
  SlmChatApp.kt            # Application: db, prefs, repo, engine, LlmManager
  MainActivity.kt          # setContent { SlmChatTheme { SlmNavGraph() } }
  data/local/              # Room: Conversation, Message, DAOs, SlmDatabase
  data/prefs/              # DataStore: AppSettings, SettingsPreferences
  data/repo/               # ChatRepository
  llm/                     # LlmEngine, MediaPipeLlmEngine, LlmManager,
                           # ModelCatalog, ModelDownloadWorker
  ui/chat/                 # ChatScreen, ChatViewModel, components/
  ui/settings/             # SettingsScreen, SettingsViewModel
  ui/navigation/           # NavGraph (SlmRoutes, SlmNavGraph)
  ui/theme/                # Color, Type, Theme (Material3)
app/src/main/res/values/   # strings.xml, colors.xml, themes.xml
```

## Requirements

- Android Studio Hedgehog+ (AGP 8.5.2, Kotlin 2.0.20)
- JDK 17
- Android SDK: `compileSdk 34`, `minSdk 26`, `targetSdk 34`
- A **physical arm64 device** (`arm64-v8a`) with 4+ GB RAM recommended.
  The emulator works for UI only — LLM inference needs arm64 + largeHeap.
- Internet **once** to download a `.task` model (~0.7–2.3 GB).

## Default model catalog

| Model | Size | License |
|---|---|---|
| Qwen2 1.5B Instruct (default) | ~1450 MB | Apache-2.0 |
| SmolLM2 1.7B Instruct | ~1650 MB | Apache-2.0 |
| Gemma 2 2B IT (gated) | ~1600 MB | Gemma — accept license, may need manual copy to `files/models/gemma2-2b-it.task` |
| Phi-3 Mini 4K | ~2300 MB | MIT |
| StableLM Zephyr 3B | ~1900 MB | StabilityAI NC |
| TinyLlama 1.1B Chat (low-end fallback) | ~750 MB | Apache-2.0 |

URLs point at `litert-community` Hugging Face `.task` conversions and can change;
paste a direct link in **Settings → Custom model URL** if a catalog URL 404s.

## Build & run

```bash
cd slm-chat-app

# Debug APK on a connected arm64 device
./gradlew :app:installDebug

# Or build APKs directly
./gradlew :app:assembleDebug        # app/build/outputs/apk/debug/app-debug.apk
./gradlew :app:assembleRelease      # minified; needs signing for install
```

No `local.properties` / API keys needed. First launch: open the app, tap
**Download** for the selected model (or pick another in Settings), wait for
100%, then chat offline.

## How it works

1. `ModelDownloadWorker` streams the `.task` file to `filesDir/models/<fileName>`
   with progress (0–100) surfaced through `LlmManager.downloadStates`.
2. `LlmManager.loadActiveModel(settings)` loads the file into `MediaPipeLlmEngine`
   with the current sampling config (temperature/topK/topP/maxTokens/GPU).
3. `ChatViewModel.send()` persists the user message + an empty assistant placeholder,
   calls `LlmManager.generateReply()` with the last ~20 messages as context, and
   streams deltas into the UI (persisted progressively, finalized on completion).
4. Changing the model or sampling settings in `SettingsViewModel` triggers an
   engine reload when the model file is present.

## Troubleshooting

- **Model URL 404**: verify the file in the linked HF repo, or use Custom model URL.
- **Gemma gated 401/403**: accept the Gemma license on Hugging Face/Kaggle, download
  on a desktop, then `adb push model.task /data/data/com.example.slmchat/files/models/gemma2-2b-it.task`.
- **Load error / OOM**: try TinyLlama 1.1B, disable GPU in Settings, or close background apps
  (`android:largeHeap` is already set).
- **Download stuck**: Clear app storage or delete the model file in Settings and retry on Wi-Fi.
- **x86 emulator crash**: expected — MediaPipe LLM ships arm64 native libs only
  (`ndk.abiFilters += arm64-v8a`); use a physical device for inference.

## Tech stack

Kotlin, Jetpack Compose (Material3), Navigation Compose, Room, DataStore,
WorkManager, OkHttp, Coroutines/Flow, MediaPipe `tasks-genai:0.10.27`.
