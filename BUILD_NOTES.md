# NM Stream TV v0.2.0 — build notes

## Verified project targets

- Android Gradle Plugin: 9.4.0
- Gradle: 9.6.0
- Kotlin: 2.4.20
- compileSdk / targetSdk: 37
- JDK: 17
- Compose BOM: 2026.09.00
- Media3: 1.11.1

## GitHub Actions

Workflow: `.github/workflows/android-apk.yml`

It installs Android platform 37 and Build Tools 36.0.0, runs `gradle assembleDebug`, renames the resulting APK, and uploads it as a workflow artifact.

## Configuration deliberately not committed

- TMDB API Read Access Token
- Trakt Client ID / OAuth tokens
- Real-Debrid OAuth tokens
- Add-on manifest URLs

These are entered/configured at runtime and stored on-device.

## Source-provider boundary

This app implements a generic protocol client. It contains no bundled third-party piracy scraper, no hard-coded Torrentio/MediaFusion/PenguPlay endpoint, and no raw torrent-to-debrid resolver. User-configured add-ons may return ordinary playable HTTP(S) URLs or external links.
