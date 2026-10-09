# Open Media

Lightweight Android (10+) video player with subtitle-to-speech audio.

- Video tab: folders (name, path, date, count) -> expand -> tap video to play
- Music tab: TTS tracks generated from subtitles (Music/Subtitle TTS), with metadata and playback
- Player: seek bar, +/-10s, subtitle picker (.srt/.vtt/.ass), progress popup, cached generation,
  separate volume sliders for video audio and TTS audio
- CI: `.github/workflows/android.yml` builds a release APK on every push and uploads it as the `OpenMedia-APK` artifact

Build locally: open in Android Studio (JDK 17), or run `gradle assembleRelease` (Gradle 8.5).
