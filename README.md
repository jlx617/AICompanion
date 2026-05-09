# AI Companion

An Android app that monitors conversations via earphone microphone and provides AI-powered suggestions in real-time.

## Features

- **Audio Monitoring**: Records audio through earphone microphone using VOICE_COMMUNICATION source
- **AI Suggestions**: Generates contextual suggestions using multiple AI providers (SiliconFlow, Google Gemini, DeepSeek, Groq)
- **Scene Detection**: Automatically detects conversation context (daily chat, meeting, class, shopping, museum)
- **Intervention Engine**: Intelligently scores when to intervene based on questions, hesitation, and confusion
- **Floating Window**: Displays suggestions as a draggable overlay
- **TTS Support**: Optional text-to-speech for spoken suggestions
- **Foreground Service**: Runs as a foreground service with microphone type

## Requirements

- Android SDK 34
- Min SDK 26 (Android 8.0)
- Kotlin 1.9.20
- Gradle 8.2

## Setup

1. Clone the repository
2. Open in Android Studio
3. Configure your API key in Settings
4. Grant required permissions (microphone, overlay, etc.)
5. Connect earphones and tap Start

## Permissions

- `INTERNET` - API calls
- `RECORD_AUDIO` - Microphone access
- `FOREGROUND_SERVICE` - Background service
- `FOREGROUND_SERVICE_MICROPHONE` - Microphone foreground service type
- `WAKE_LOCK` - Keep service alive
- `SYSTEM_ALERT_WINDOW` - Floating window
- `ACCESS_FINE_LOCATION` - Location awareness

## Build

```bash
./gradlew assembleDebug
```

## License

MIT
