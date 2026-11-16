#!/bin/sh
# Builds the engine (github.com/luisten/ferry-messenger/core at the commit pinned in go.mod, or the local checkout a
# gitignored go.work names) as app/build/engine/jniLibs/arm64-v8a/libigcore.so, with its JNI bridge
# (core/cmd/igcore/jni_android.c). Gradle runs this before every build; Go's cache makes an unchanged engine quick.
# Needs Go 1.27.3 on PATH and the Android NDK r30 (30.0.16248370) in ANDROID_NDK_HOME or the default SDK folder.
set -eu
cd "$(dirname "$0")"

want=go1.27.3
have=$(go env GOVERSION)
[ "$have" = "$want" ] || { echo "build-engine.sh: need $want on PATH, found $have" >&2; exit 1; }
ndk=${ANDROID_NDK_HOME:-$HOME/Library/Android/sdk/ndk/30.0.16248370}
api=26 # minSdk, Android 8.0

out=app/build/engine/jniLibs/arm64-v8a
mkdir -p "$out"
# The SONAME is what the NDK's own toolchain would set (Go's c-shared mode sets none). NDK r30 aligns LOAD segments
# to 16 KB, which Android 15+ phones with 16 KB pages require.
CGO_ENABLED=1 GOOS=android GOARCH=arm64 GOTOOLCHAIN=local \
  CC="$ndk/toolchains/llvm/prebuilt/darwin-x86_64/bin/aarch64-linux-android$api-clang" \
  go build -trimpath -ldflags="-s -w -extldflags=-Wl,-soname,libigcore.so" -buildmode=c-shared \
  -o "$out/libigcore.so" github.com/luisten/ferry-messenger/core/cmd/igcore
rm "$out/libigcore.h" # for C callers; Kotlin goes through the JNI bridge
grep -q Java_fi_ferry_core_IGCore_login "$out/libigcore.so" ||
  { echo "build-engine.sh: this engine has no JNI bridge (core/cmd/igcore/jni_android.c): pin a commit with it, or use a go.work" >&2; exit 1; }
