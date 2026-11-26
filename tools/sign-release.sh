#!/bin/sh
# Sign locally with interactive password prompts. No password is written to disk.
set -eu
umask 077

project=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
apk_dir="$project/app/build/outputs/apk/release"
unsigned="$apk_dir/app-release-unsigned.apk"
output="$apk_dir/app-release.apk"
key_dir="$HOME/Documents/Ginkgo签名"
keystore="$key_dir/ginkgo-release.p12"

if [ ! -t 0 ] || [ ! -t 1 ]; then
    printf '%s\n' 'Run this script in a local Mac Terminal window for secure password prompts.' >&2
    exit 1
fi
if [ ! -f "$unsigned" ]; then
    printf '%s\n' 'Build :app:assembleRelease before signing.' >&2
    exit 1
fi

JAVA_HOME=${JAVA_HOME:-$(/usr/libexec/java_home -v 17)}
export JAVA_HOME
sdk=${ANDROID_HOME:-"$HOME/Library/Android/sdk"}
build_tools="$sdk/build-tools/${BUILD_TOOLS_VERSION:-36.0.0}"
test -x "$build_tools/apksigner"

if [ ! -e "$keystore" ]; then
    mkdir -p "$key_dir"
    "$JAVA_HOME/bin/keytool" -genkeypair -keystore "$keystore" -storetype PKCS12 \
        -alias ginkgo -keyalg RSA -keysize 3072 -sigalg SHA256withRSA \
        -validity 10950 -dname 'CN=Locusable Studio'
fi

staging=$(mktemp -d "$apk_dir/.sign.XXXXXX")
trap 'rm -f "$staging/app-release.apk"; rmdir "$staging"' 0
"$build_tools/apksigner" sign --ks "$keystore" --ks-key-alias ginkgo \
    --v4-signing-enabled false --out "$staging/app-release.apk" "$unsigned"
"$build_tools/apksigner" verify --print-certs "$staging/app-release.apk"
mv "$staging/app-release.apk" "$output"
printf 'Signed release: %s\n' "$output"
