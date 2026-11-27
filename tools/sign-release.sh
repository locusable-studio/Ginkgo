#!/bin/sh
# Sign locally with interactive password prompts. No password is written to disk.
set -eu
umask 077

project=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
apk_dir="$project/app/build/outputs/apk/release"
unsigned="$apk_dir/app-release-unsigned.apk"
output="$apk_dir/app-release.apk"
keystore=${GINKGO_KEYSTORE:-}

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

if [ ! -f "$keystore" ]; then
    printf '%s\n' 'Set GINKGO_KEYSTORE to your existing Ginkgo release keystore.' >&2
    exit 1
fi

staging=$(mktemp -d "$apk_dir/.sign.XXXXXX")
trap 'rm -f "$staging/app-release.apk"; rmdir "$staging"' 0
"$build_tools/apksigner" sign --ks "$keystore" --ks-type PKCS12 --ks-key-alias ginkgo \
    --v4-signing-enabled false --out "$staging/app-release.apk" "$unsigned"
certificate=$("$build_tools/apksigner" verify --print-certs "$staging/app-release.apk")
if ! printf '%s\n' "$certificate" | grep -Fxq 'Signer #1 certificate SHA-256 digest: 86edb3c85b94e869a90336cd683dcd7ddd38f3e2efcb39d9be6f0cfcf87a5bb3'; then
    printf '%s\n' 'The APK was not signed with the existing Ginkgo release certificate.' >&2
    exit 1
fi
mv "$staging/app-release.apk" "$output"
printf 'Signed release: %s\n' "$output"
