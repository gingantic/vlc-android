#!/bin/sh
# Sign an unsigned release APK for installation (e.g. adb install).
# Uses the standard Android debug keystore; for production use your own keystore.
set -e

APK="$1"

if [ ! -f "$APK" ]; then
    echo "Usage: $0 [path/to/unsigned.apk]"
    echo "APK not found: $APK"
    exit 1
fi

# Ensure debug keystore exists (same as Android SDK uses)
KEYSTORE="$HOME/.android/debug.keystore"
if [ ! -f "$KEYSTORE" ]; then
    echo "Creating debug keystore at $KEYSTORE"
    mkdir -p "$HOME/.android"
    keytool -genkey -v -keystore "$KEYSTORE" -storepass android -alias androiddebugkey \
        -keypass android -keyalg RSA -keysize 2048 -validity 10000 \
        -dname "CN=Android Debug,O=Android,C=US"
fi

# Prefer apksigner (SDK build-tools) for v2/v3 signature; fallback to jarsigner (JDK)
if [ -n "$ANDROID_SDK" ] && [ -d "$ANDROID_SDK/build-tools" ]; then
    APKSIGNER=$(find "$ANDROID_SDK/build-tools" -name apksigner -type f 2>/dev/null | head -1)
fi

if [ -n "$APKSIGNER" ] && [ -x "$APKSIGNER" ]; then
    echo "Signing with apksigner (v2)..."
    "$APKSIGNER" sign --ks "$KEYSTORE" --ks-pass pass:android --ks-key-alias androiddebugkey \
        --key-pass pass:android --out "${APK%.apk}-signed.apk" "$APK"
    echo "Signed: ${APK%.apk}-signed.apk"
else
    echo "Signing with jarsigner..."
    jarsigner -verbose -sigalg SHA256withRSA -digestalg SHA-256 \
        -keystore "$KEYSTORE" -storepass android -keypass android \
        "$APK" androiddebugkey
    echo "Signed: $APK (in place)"
fi
