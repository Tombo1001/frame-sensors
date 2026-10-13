#!/usr/bin/env bash
# Downloads the one runtime dependency (Eclipse Paho MQTT v3 client) and, optionally,
# a user-local JDK 17 and the Android build tools. Nothing needs root.
#   ./fetch-deps.sh          # Paho jar only
#   ./fetch-deps.sh --all    # also JDK 17 and Android SDK into ~/.local/opt
set -euo pipefail
cd "$(dirname "$0")"

PAHO_URL=https://repo1.maven.org/maven2/org/eclipse/paho/org.eclipse.paho.client.mqttv3/1.2.5/org.eclipse.paho.client.mqttv3-1.2.5.jar
PAHO_SHA1=1546cfc794449c39ad569853843a930104fdc297
mkdir -p lib
if [ ! -f lib/paho-mqttv3-1.2.5.jar ]; then
  curl -sSLo lib/paho-mqttv3-1.2.5.jar "$PAHO_URL"
fi
echo "$PAHO_SHA1  lib/paho-mqttv3-1.2.5.jar" | sha1sum -c -

if [ "${1:-}" = "--all" ]; then
  OPT="$HOME/.local/opt"; mkdir -p "$OPT"
  if [ ! -x "$OPT/jdk17/bin/javac" ]; then
    echo "Fetching JDK 17 (Temurin) to $OPT/jdk17"
    curl -sSLo "$OPT/jdk17.tgz" 'https://api.adoptium.net/v3/binary/latest/17/ga/linux/x64/jdk/hotspot/normal/eclipse'
    mkdir -p "$OPT/jdk17" && tar xzf "$OPT/jdk17.tgz" -C "$OPT/jdk17" --strip-components=1 && rm "$OPT/jdk17.tgz"
  fi
  SDK="$OPT/android-sdk"
  if [ ! -f "$SDK/build-tools/34.0.0/d8" ]; then
    echo "Fetching Android command-line tools, build-tools 34 and platform 34 to $SDK"
    mkdir -p "$SDK/cmdline-tools"
    curl -sSLo "$SDK/clt.zip" https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip
    unzip -qo "$SDK/clt.zip" -d "$SDK/cmdline-tools" && rm "$SDK/clt.zip"
    [ -d "$SDK/cmdline-tools/latest" ] || mv "$SDK/cmdline-tools/cmdline-tools" "$SDK/cmdline-tools/latest"
    export JAVA_HOME="$OPT/jdk17"
    yes | "$SDK/cmdline-tools/latest/bin/sdkmanager" --sdk_root="$SDK" --licenses >/dev/null
    "$SDK/cmdline-tools/latest/bin/sdkmanager" --sdk_root="$SDK" "build-tools;34.0.0" "platforms;android-34" >/dev/null
  fi
  echo "Toolchain ready: JAVA_HOME=$OPT/jdk17 ANDROID_HOME=$SDK"
fi
