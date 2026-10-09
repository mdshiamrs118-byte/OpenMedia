# Open Media

Lightweight Android (10+) video player with subtitle-to-speech audio.

- Video tab: folders (name, path, date, count) -> expand -> tap video to play
- Music tab: generated TTS sets (video title, subtitle file, date, size, duration), playable in-app
- Player: seek bar, +/-10s, subtitle picker (.srt/.vtt/.ass), progress popup, cached generation,
  separate volume sliders for video audio and TTS audio
- TTS: every subtitle line is synthesized to its own small WAV, in parallel (3 engines, pipelined).
  Nothing is merged. Clips are played at their timestamps while the video plays.
- Settings > Overlapping subtitles: Overlap / Skip overlaps / Hybrid (speeds up overlapping lines together)
- CI: `.github/workflows/android.yml` builds a release APK on every push (artifact `OpenMedia-APK`)

Build locally: open in Android Studio (JDK 17), or run `gradle assembleRelease` (Gradle 8.5).
