# NM Stream TV

**NM Stream TV** is an Android TV / Android-box media front end branded as an **NM Digital** product. It provides a cinematic, remote-friendly interface for user-configured media sources without bundling third-party scraping providers.

## v0.9.0 features

- Cinematic Netflix-inspired (but original) Android TV interface
- **NM Stream TV startup splash:** supplied Morrison Entertainment family artwork is bundled offline, shown full-screen with no cropping, and fades into the app after startup
- Home hero banner, horizontal media rails and D-pad focus animations
- Movies, series, search, episodes and source-selection screens
- Generic **Stremio add-on protocol** support via user-supplied HTTPS `manifest.json` URLs
- Standard `catalog`, `meta`, `stream` and `subtitles` endpoints
- Multiple add-ons installed at once; results are aggregated
- **Real-Debrid device authentication** and access to the user's existing download library
- **TMDB artwork enrichment** using a user-entered API Read Access Token
- **Native Trakt device authentication using Client ID only** with automatic token refresh; no Client Secret required for new Trakt apps
- **Noel** and **Sarah** Home rows backed by Trakt personal lists with those names
- **Add to Noel** and **Add to Sarah** buttons directly on movie/series detail screens
- **Trakt cloud Continue Watching** from `/sync/playback`, including cross-device percentage resume
- **Trakt Up Next**: recently watched shows are checked for the next aired unwatched episode and merged into Continue Watching
- Native Trakt watchlist row and playback scrobbling for media with IMDb IDs
- **Live TV / IPTV** with user-supplied M3U/M3U8 playlists, optional XMLTV URL and Xtream Codes credentials
- Sports-first live TV discovery with dedicated **Rugby**, **F1 & Motorsport**, **Soccer & Football**, **Cricket** and **Other Sports** rows
- Native XMLTV EPG parsing with a **Now & Next TV guide**; Xtream setups automatically try the standard XMLTV endpoint when no custom EPG URL is supplied
- Added **Motorsport Hub** and **M3U/EPG TV Addon** presets
- Curated Add-on Manager containing the requested core integrations plus **Sports Streams (SportStream)**, **StremVerse** and IPTV setup options
- Manifest installer accepts both `https://` and copied `stremio://` links, shows install status/errors, and preserves configured query parameters
- **Smart stream ranking:** 720p is the preferred default quality, direct/debrid HTTP is favoured over raw P2P, and the top result is marked DEFAULT
- **One-press auto-play:** a normal OK/Play press resolves sources and immediately starts the best ranked playable stream
- **Hold for manual sources:** holding OK/Play on a movie, episode or Continue Watching item opens the full source picker instead
- **720p-first trailers:** Stremio/TMDB trailer metadata is ranked for 720p first; YouTube playback receives an HD720 preference hint when fixed-quality playback is not exposed
- Local **Continue Watching** with resume position
- External subtitles attached to the Media3 player
- Media3 / ExoPlayer playback including HLS and DASH modules
- Encrypted local storage for add-on URLs and service credentials
- GitHub Actions workflow that builds an installable debug APK

## Branding

- Product name: **NM Stream TV**
- Package/application ID: `za.co.nm.streamtv`
- Version: `0.9.0`
- Product family label: **NM Digital**

## Build

The repository contains `.github/workflows/android-apk.yml`. Every push to `main`, pull request to `main`, or manual workflow dispatch builds:

`NM-Stream-TV-v0.9.0-debug.apk`

The workflow uses JDK 17, Gradle 9.6, Android SDK 37 and Android Build Tools 36.0.0.

The startup artwork is bundled into the APK and does not require a network request. It is displayed for at least about 2.2 seconds, waits for initial loading when practical, and releases to the app after about 4.5 seconds at most.

Local build, when the Android SDK is installed:

```bash
gradle assembleDebug
```

APK output:

```text
app/build/outputs/apk/debug/app-debug.apk
```

## Add-ons

Open **Add-ons** and paste an HTTPS Stremio-compatible manifest URL. NM Stream TV normalizes the URL to `/manifest.json`, validates the manifest, stores the URL locally, and discovers supported resources from the manifest.

The Add-on Manager includes curated entries for metadata, discovery, subtitles, tracking, stream aggregation and IPTV. Static official manifests can be installed with one click; configurable third-party services open their setup page and remain user-configured. The app plays ordinary HTTP(S) media URLs returned by installed add-ons and can open declared external URLs. It does not convert raw torrent hashes into debrid links.

Use NM Stream TV only with media and services you are authorized to access.

## TMDB artwork

1. Create/obtain a TMDB API Read Access Token from your TMDB account.
2. Open **Settings → TMDB artwork**.
3. Paste the token and choose **Save**.
4. Home/search/details artwork will be enriched where a title can be matched, with add-on artwork remaining the fallback.

The token is stored locally using Android Keystore-backed AES-GCM encryption and is not placed in the source tree or GitHub Actions configuration.

## Trakt

1. Register your own Trakt API application and copy its **Client ID**.
2. Open **Settings → Trakt** and save the Client ID. A Client Secret is not required.
3. Choose **Connect Trakt**.
4. Visit the displayed verification URL and enter the device code shown on the TV.

The app stores OAuth tokens locally, refreshes them automatically when needed, loads the user's Trakt watchlist, personal lists named **Noel** and **Sarah**, imports playback progress into Continue Watching, finds the next aired unwatched episode for recently watched shows, resumes cloud items by percentage, and scrobbles supported playback. Trakt integration is optional. Use it in accordance with Trakt's API terms and branding requirements.

## IPTV / Live TV

Open **Settings → Live TV / IPTV** and add either:

- your own M3U/M3U8 playlist (plus an optional XMLTV EPG URL), or
- Xtream Codes server URL, username and password.

Credentials are encrypted locally. NM Stream TV identifies likely sports channels and builds dedicated Rugby, F1 & Motorsport, Soccer & Football, Cricket and Other Sports categories. XMLTV guide data supplies Now/Next programme information. When Xtream is configured without a custom EPG URL, NM Stream TV tries the provider's standard XMLTV endpoint. The app does not include an IPTV subscription or channel package.

## Real-Debrid

Open **Settings → Real-Debrid → Connect Real-Debrid**. NM Stream TV uses Real-Debrid's device authorization flow. The app never asks for or stores the user's Real-Debrid password.

## Continue Watching

During playback the app records local progress every few seconds. Continue Watching merges local progress, Trakt cloud playback and **Up Next** episodes from the most recently watched shows. Trakt's watched-progress data is used to identify the next aired unwatched episode.

## Subtitles

If an installed add-on advertises the standard `subtitles` resource, NM Stream TV queries:

```text
/subtitles/{type}/{id}.json
```

Returned HTTP(S) subtitle tracks are attached to the Media3 `MediaItem`. SRT, WebVTT, TTML/XML and SSA/ASS MIME mappings are included.

## Project structure

```text
app/src/main/java/za/co/nm/streamtv/
├── AddonCatalog.kt
├── AddonRepository.kt
├── HttpClient.kt
├── IptvRepository.kt
├── MainActivity.kt
├── MainViewModel.kt
├── Models.kt
├── PlaybackStore.kt
├── RealDebridRepository.kt
├── SecretStore.kt
├── TmdbRepository.kt
├── TraktRepository.kt
└── TvUi.kt
```

## Production hardening still recommended

Before a public store release, add a signed release build, crash reporting, accessibility review, TV-device matrix testing, network/cache layer, service-specific rate-limit handling, privacy-policy hosting, app-store artwork and a formal QA pass on physical Android TV devices.

