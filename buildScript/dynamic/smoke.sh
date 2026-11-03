#!/usr/bin/env bash
set -euo pipefail
PKG=io.github.yueziji.nekobox.dynamicdebug
adb install dynamic-output/NekoBox-Dynamic-x86_64.apk
probe() {
  result=$(adb shell am instrument -w -e action "$1" -e expected "${2:-内置}" "$PKG/moe.matsuri.nb4a.core.CoreSmokeInstrumentation")
  printf '%s\n' "$result"
  [[ "$result" == *"result=PASS"* ]]
}
probe probe 内置
adb push dynamic-output/nekobox-core-x86_64.zip /data/local/tmp/nekobox-core-test.zip
adb shell "run-as $PKG sh -c 'mkdir -p files; cat /data/local/tmp/nekobox-core-test.zip > files/core-test.zip'"
probe stage
probe probe 动态
probe builtin
probe probe 内置
