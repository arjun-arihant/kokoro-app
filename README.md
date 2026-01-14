# Kokoro TTS - Android Text-to-Speech Service

A complete Android TTS service that integrates the Kokoro-82M model with Android's system TTS framework, allowing apps like Moon+ Reader to use Kokoro as a speech synthesis engine.

## Features

- **System TTS Integration**: Works with any Android app that uses the system TTS API
- **Multiple Voices**: 11 voice styles (American & British English, male & female)
- **On-Device Processing**: No internet required after model download
- **Adjustable Speed**: 0.5x to 2.0x speech rate
- **Smart Sentence Processing**: Handles long text naturally by splitting into sentences
- **High Quality Audio**: Native 24,000 Hz sample rate output
- **Natural Pacing**: Intelligent punctuation handling for realistic pauses

## Supported Voices

| Voice ID | Description |
|----------|-------------|
| af_sky | Sky (American Female) |
| af_bella | Bella (American Female) |
| af_nicole | Nicole (American Female) |
| af_sarah | Sarah (American Female) |
| af | Default (American Female) |
| am_adam | Adam (American Male) |
| am_michael | Michael (American Male) |
| bf_emma | Emma (British Female) |
| bf_isabella | Isabella (British Female) |
| bm_george | George (British Male) |
| bm_lewis | Lewis (British Male) |

## Setup Instructions

### 1. Download Model Files

Before building, you need to download the Kokoro model files:

```bash
# Create assets directory
mkdir -p app/src/main/assets

# Download the ONNX model (~82MB)
curl -L "https://github.com/thewh1teagle/kokoro-onnx/releases/download/model-files-v1.0/kokoro-v1.0.onnx" \
  -o app/src/main/assets/kokoro.onnx
```

### 2. Download Voice Style Files

Download NPY voice embedding files:

```bash
# Create raw resources directory
mkdir -p app/src/main/res/raw

# Download voice files from Kokoro-82M-Android releases
# Each voice is a separate .npy file
```

You can find the voice files at: https://github.com/puff-dayo/Kokoro-82M-Android/releases

### 3. CMU Dictionary (Recommended)

For accurate pronunciation of English words, download the CMU dictionary:

```bash
# Download CMU dictionary with IPA
curl -L "https://raw.githubusercontent.com/Kyubyong/g2p/master/cmudict_ipa.txt" \
  -o app/src/main/res/raw/cmudict_ipa.txt
```

### 4. Build the APK

Open the project in Android Studio or build from command line:

```bash
# Generate Gradle wrapper
gradle wrapper

# Build debug APK
./gradlew assembleDebug

# The APK will be at: app/build/outputs/apk/debug/app-debug.apk
```

### 5. Install and Configure

1. Install the APK: `adb install app/build/outputs/apk/debug/app-debug.apk`
2. Open Android Settings → Accessibility → Text-to-Speech
3. Select "Kokoro Text-to-Speech" as the preferred engine
4. Test with the "Play" button

## Usage with Moon+ Reader

1. Open Moon+ Reader
2. Go to Settings → Read Aloud → Speech Engine
3. Select "Kokoro Text-to-Speech"
4. Start reading any book!

## Project Structure

```
KokoroTTS/
├── app/
│   ├── src/main/
│   │   ├── java/com/kokorotts/android/
│   │   │   ├── KokoroTTSService.kt    # Main TTS service
│   │   │   ├── KokoroEngine.kt        # ONNX inference
│   │   │   ├── PhonemeConverter.kt    # Text to phonemes
│   │   │   ├── Tokenizer.kt           # Phoneme tokenization
│   │   │   ├── VoiceManager.kt        # Voice embeddings
│   │   │   ├── AudioGenerator.kt      # Audio utilities
│   │   │   └── SettingsActivity.kt    # Settings UI
│   │   ├── assets/
│   │   │   └── kokoro.onnx            # Model file
│   │   ├── res/
│   │   │   ├── raw/                   # Voice NPY files
│   │   │   ├── xml/tts_config.xml     # TTS configuration
│   │   │   └── layout/                # UI layouts
│   │   └── AndroidManifest.xml
│   └── build.gradle.kts
├── build.gradle.kts
└── settings.gradle.kts
```

## Technical Details
- **Model**: Kokoro-82M ONNX format (v1.0)
- **Sample Rate**: 24,000 Hz (Native)
- **Audio Format**: 16-bit PCM mono
- **Max Input Length**: Unlimited (processed sentence-by-sentence)
- **Phoneme System**: IPA (International Phonetic Alphabet) with Kokoro-specific tokenization
- **Processing**: Intelligent sentence splitting for natural pacing

## Dependencies

- ONNX Runtime for Android 1.20.0
- NPY file parser (org.jetbrains.bio:npy)
- IPA Transcribers (fallback G2P)
- Kotlin Coroutines

## Credits

- [Kokoro-82M](https://huggingface.co/hexgrad/Kokoro-82M) - Original model
- [kokoro-onnx](https://github.com/thewh1teagle/kokoro-onnx) - ONNX implementation
- [Kokoro-82M-Android](https://github.com/puff-dayo/Kokoro-82M-Android) - Android reference
- [Supertonic](https://github.com/DevGitPit/supertonic) - TTS service patterns

## License

This project uses components under Apache 2.0 and MIT licenses.
