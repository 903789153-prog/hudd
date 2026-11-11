#!/usr/bin/env bash
# KingsHUD 本地一键构建（Linux / macOS / WSL）
# 前置：JDK 17 + Android SDK
set -e

if ! command -v java >/dev/null; then
  echo "[!] 缺 JDK 17"; exit 1
fi

if [ -z "${ANDROID_HOME:-}" ]; then
  for c in "$HOME/Android/Sdk" "$HOME/Library/Android/sdk" "/usr/lib/android-sdk"; do
    [ -d "$c" ] && export ANDROID_HOME="$c" && break
  done
fi
[ -z "${ANDROID_HOME:-}" ] && { echo "[!] 设 ANDROID_HOME"; exit 1; }
[ -f local.properties ] || echo "sdk.dir=$ANDROID_HOME" > local.properties

if [ ! -x ./gradlew ]; then
  echo "[*] 没有 wrapper，用系统 gradle"
  G=gradle
else
  G=./gradlew
fi

$G assembleRelease || $G assembleDebug

echo "[*] 产物："
find app/build/outputs/apk -name '*.apk' -print
echo "[*] 安装：adb install -r <上面任意一个>.apk"
