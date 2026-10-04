#!/bin/sh
# 本地构建脚本。
#
# 为什么需要它：aapt2 覆盖路径是本机专用的（ARM64 宿主上 AGP 自带的 aapt2 是 x86_64
# 二进制，需要 qemu 包装），不能写进 gradle.properties，否则会污染开源仓库。
# 需要时用 OHSYNC_AAPT2 环境变量传入；不设就用系统默认 aapt2。
set -e
cd "$(dirname "$0")/.."

if [ -n "$OHSYNC_AAPT2" ]; then
  echo "using aapt2 override: $OHSYNC_AAPT2"
  exec ./gradlew "$@" -Pandroid.aapt2FromMavenOverride="$OHSYNC_AAPT2"
else
  exec ./gradlew "$@"
fi
