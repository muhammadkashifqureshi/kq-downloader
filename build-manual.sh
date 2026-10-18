#!/bin/bash
# Manual APK build for YT and TikTok Downloader (no Gradle): aapt2 + javac + d8 + apksigner
set -e
cd "$(dirname "$0")"

export JAVA_HOME=~/workspace/android-toolchain/jdk-17.0.20.1+1
export PATH="$JAVA_HOME/bin:$PATH"
SDK=~/workspace/android-sdk
BT=$SDK/build-tools/android-14
ANDROID_JAR=$SDK/platforms/android-34/android.jar
SRC=app/src/main
OUT=app/build/manual

rm -rf "$OUT"
mkdir -p "$OUT/compiled_res" "$OUT/gen" "$OUT/classes" "$OUT/dex" "$OUT/apk"

echo "== aapt2 compile =="
$BT/aapt2 compile --dir $SRC/res -o $OUT/compiled_res.zip

echo "== aapt2 link =="
$BT/aapt2 link -o $OUT/apk/base.apk \
  -I "$ANDROID_JAR" \
  --manifest $SRC/AndroidManifest.xml \
  --min-sdk-version 21 \
  --target-sdk-version 34 \
  --version-code 1 \
  --version-name 1.0 \
  --java $OUT/gen \
  $OUT/compiled_res.zip

echo "== javac =="
find $SRC/java $OUT/gen -name "*.java" > $OUT/sources.txt
javac -encoding UTF-8 --release 11 -cp "$ANDROID_JAR" -d $OUT/classes @${OUT}/sources.txt
echo "   compiled $(wc -l < $OUT/sources.txt) sources"

echo "== d8 =="
$BT/d8 --lib "$ANDROID_JAR" --min-api 21 --output $OUT/dex \
  $(find $OUT/classes -name "*.class")
ls -la $OUT/dex/classes.dex

echo "== add dex to apk (in place, no re-zip) =="
cd $OUT/dex
zip -q -g ../apk/base.apk classes.dex
cd ../apk
unzip -l base.apk | grep -c "classes.dex"

echo "== zipalign =="
$BT/zipalign -f 4 base.apk app-aligned.apk

echo "== sign (release key: Kashif Qureshi) =="
# Release keystore lives at the project root (never inside app/build, which is
# wiped on every build). Keep this file + alias/password safe. ALL future
# updates MUST be signed with the same key, or Android will refuse to install
# them over the old version. Password/alias are documented in README.md.
KS="$(cd "$(dirname "$0")" && pwd)/release.keystore"
if [ ! -f "$KS" ]; then
  keytool -genkeypair -keystore "$KS" -alias kashif-release \
    -storepass kashif-release-2026 -keypass kashif-release-2026 -keyalg RSA -keysize 2048 -validity 10950 \
    -dname "CN=Kashif Qureshi, O=KQ DevOps, C=PK" 2>/dev/null
fi
$BT/apksigner sign --v1-signing-enabled true --v2-signing-enabled true \
  --ks "$KS" --ks-pass pass:kashif-release-2026 --key-pass pass:kashif-release-2026 \
  --out ../yt-tiktok-downloader.apk app-aligned.apk

echo "== verify =="
$BT/apksigner verify --print-certs ../yt-tiktok-downloader.apk | head -4
$BT/aapt2 dump badging ../yt-tiktok-downloader.apk | head -3
echo "DONE: $(pwd)/../yt-tiktok-downloader.apk"
ls -la ../yt-tiktok-downloader.apk
