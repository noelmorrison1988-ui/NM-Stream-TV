# Kodi Core in NM Stream TV

NM Stream TV v0.15.0 can package the Kodi media-center runtime as an internal Android component.

- Upstream project: Kodi
- Upstream source: https://github.com/xbmc/xbmc
- Source branch used by the build: `Omega`
- Kodi version targeted: 21.3
- License: GPL-2.0-or-later
- Build script: `.github/scripts/build-kodi-aar.sh`
- Generated runtime: `app/libs/kodi-runtime.aar` (generated in CI; not committed)

The corresponding source for the Kodi component is the upstream Kodi Omega source together with the build/packaging modifications in this repository. The full GPL-2.0-or-later text is bundled in the application assets and in the source tree.

NM Stream TV does not preinstall third-party Kodi repositories or media add-ons.
