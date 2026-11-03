#!/usr/bin/env bash
set -euo pipefail
adb install dynamic-output/NekoBox-Dynamic-x86_64.apk
adb shell pm list instrumentation
PKG=$(adb shell pm list instrumentation | sed -n 's|instrumentation:\([^/]*\)/moe.matsuri.nb4a.core.CoreSmokeInstrumentation .*|\1|p' | tr -d '\r')
test -n "$PKG"
probe() {
  result=$(adb shell am instrument -w -e action "$1" -e expected "${2:-内置}" "$PKG/moe.matsuri.nb4a.core.CoreSmokeInstrumentation" 2>&1) || {
    printf '%s\n' "$result"
    return 1
  }
  printf '%s\n' "$result"
  [[ "$result" == *"result=PASS"* ]]
}
probe probe 内置
probe ui
adb push dynamic-output/nekobox-core-x86_64.zip /data/local/tmp/nekobox-core-test.zip
adb shell "run-as $PKG sh -c 'mkdir -p files; cat /data/local/tmp/nekobox-core-test.zip > files/core-test.zip'"
probe stage
probe interrupt
probe probe 内置
probe stage
probe probe 动态
probe ui
probe builtin
probe probe 内置
