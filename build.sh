#!/usr/bin/env bash
# Builds build/frame-sensors.apk without Gradle.
# Needs a JDK, Android build-tools 34 and platform 34, and lib/paho-mqttv3-1.2.5.jar (./fetch-deps.sh --all gets them all).
set -euo pipefail
cd "$(dirname "$0")"

export JAVA_HOME="${JAVA_HOME:-$HOME/.local/opt/jdk17}"; export PATH="$JAVA_HOME/bin:$PATH"
ANDROID_HOME="${ANDROID_HOME:-$HOME/.local/opt/android-sdk}"
BT="$ANDROID_HOME/build-tools/34.0.0"
JAR="$ANDROID_HOME/platforms/android-34/android.jar"
PAHO=lib/paho-mqttv3-1.2.5.jar
KS="${FRAME_SENSORS_KEYSTORE:-$HOME/.frame-sensors.keystore}"
VERSION_CODE="${VERSION_CODE:-1}"
VERSION_NAME="${VERSION_NAME:-1.0}"

rm -rf build && mkdir -p build/gen build/classes

"$BT/aapt2" compile --dir res -o build/res.zip
"$BT/aapt2" link -o build/base.apk -I "$JAR" --manifest AndroidManifest.xml build/res.zip \
  --java build/gen --min-sdk-version 23 --target-sdk-version 23 \
  --version-code "$VERSION_CODE" --version-name "$VERSION_NAME"

javac -nowarn --release 8 -classpath "$JAR:$PAHO" -d build/classes \
  src/net/exitcode0/framesensors/*.java build/gen/net/exitcode0/framesensors/R.java

"$BT/d8" --min-api 23 --lib "$JAR" --output build $(find build/classes -name '*.class') "$PAHO"
(cd build && zip -qj base.apk classes.dex)
# Paho loads its message bundles with ResourceBundle; d8 drops non-class files, so add them to the APK root.
mkdir -p build/res-paho && unzip -qo "$PAHO" 'org/eclipse/paho/client/mqttv3/internal/nls/*.properties' 'META-INF/services/*' -d build/res-paho
(cd build/res-paho && zip -qr ../base.apk org META-INF)
"$BT/zipalign" -f 4 build/base.apk build/aligned.apk

if [ ! -f "$KS" ]; then
  mkdir -p "$(dirname "$KS")"
  keytool -genkeypair -keystore "$KS" -storepass framesensors -keypass framesensors -alias framesensors \
    -keyalg RSA -keysize 2048 -validity 10000 -dname "CN=Frame Sensors,O=exitcode0,C=GB" >/dev/null 2>&1
  chmod 600 "$KS"
  echo "Created signing keystore $KS (keep it: updates must be signed with the same key)"
fi
"$BT/apksigner" sign --ks "$KS" --ks-pass pass:framesensors --key-pass pass:framesensors \
  --out build/frame-sensors.apk build/aligned.apk
ls -la build/frame-sensors.apk
