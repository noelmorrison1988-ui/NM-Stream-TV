#!/usr/bin/env bash
set -euo pipefail

KODI_BRANCH="${KODI_BRANCH:-Omega}"
JOBS="${KODI_JOBS:-2}"
ROOT="${RUNNER_TEMP:-/tmp}/nm-kodi"
SRC="$ROOT/src"
PREFIX="$ROOT/depends"
TARBALLS="$ROOT/tarballs"
NDK_VERSION="21.4.7075529"
NDK_PATH="$ANDROID_HOME/ndk/$NDK_VERSION"
OUT="${GITHUB_WORKSPACE:-$(pwd)}/app/libs/kodi-runtime.aar"

echo "Building Kodi Core from branch: $KODI_BRANCH"
rm -rf "$SRC" "$ROOT/build"
mkdir -p "$ROOT" "$TARBALLS" "$(dirname "$OUT")"

sudo apt-get update
sudo apt-get install -y --no-install-recommends \
  autoconf automake bison build-essential ccache curl flex gawk git gperf \
  libcurl4-openssl-dev libtool nasm python3 unzip yasm zip zlib1g-dev

SDKMANAGER="$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager"
yes | "$SDKMANAGER" --licenses >/dev/null || true
"$SDKMANAGER" \
  "platform-tools" \
  "platforms;android-34" \
  "build-tools;33.0.1" \
  "ndk;$NDK_VERSION"

git clone --depth 1 --branch "$KODI_BRANCH" https://github.com/xbmc/xbmc.git "$SRC"

cd "$SRC/tools/depends"
./bootstrap
./configure \
  --with-tarballs="$TARBALLS" \
  --host=aarch64-linux-android \
  --with-sdk-path="$ANDROID_HOME" \
  --with-ndk-path="$NDK_PATH" \
  --prefix="$PREFIX" \
  --disable-debug
make -j"$JOBS"

cd "$SRC"
make -C tools/depends/target/cmakebuildsys

cd "$SRC/build"
make -j"$JOBS"

PACKAGING="$SRC/build/tools/android/packaging"
cd "$PACKAGING"
make sharedapk libs python java

python3 - <<'PY'
from pathlib import Path
import re

module = Path("xbmc")
gradle = module / "build.gradle"
manifest = module / "AndroidManifest.xml"

gradle.write_text("""apply plugin: 'com.android.library'

android {
    namespace 'org.xbmc.kodi'
    compileSdk 34

    defaultConfig {
        minSdk 21
        targetSdk 34
    }

    aaptOptions {
        ignoreAssetsPattern '!.svn:!.git:!.ds_store:!*.scc:.*:!CVS:!thumbs.db:!picasa.ini:!*~'
    }

    sourceSets {
        main {
            manifest.srcFile 'AndroidManifest.xml'
            java.srcDirs = ['java']
            res.srcDirs = ['res']
            assets.srcDirs = ['assets']
            jniLibs.srcDirs = ['lib']
        }
    }

    packagingOptions {
        doNotStrip '**.setup'
        jniLibs {
            useLegacyPackaging true
        }
    }

    compileOptions {
        sourceCompatibility JavaVersion.VERSION_17
        targetCompatibility JavaVersion.VERSION_17
    }
}

dependencies {
    implementation 'androidx.tvprovider:tvprovider:1.1.0-alpha01'
    implementation 'com.google.code.gson:gson:2.10.1'
}
""")

text = manifest.read_text()
# Kodi is internal to NM Stream TV: remove all external intent entry points.
text = re.sub(r'\s*<intent-filter>.*?</intent-filter>', '', text, flags=re.S)
text = text.replace('android:exported="true"', 'android:exported="false"')
manifest.write_text(text)
PY

ANDROID_HOME="$ANDROID_HOME" ./gradlew --no-daemon :xbmc:assembleDebug

AAR="$PACKAGING/xbmc/build/outputs/aar/xbmc-debug.aar"
test -s "$AAR"
cp "$AAR" "$OUT"

echo "Kodi Core AAR created: $OUT"
ls -lh "$OUT"
