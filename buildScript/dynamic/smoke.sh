#!/usr/bin/env bash
set -euo pipefail
# First prove that the APK already delivered to users can import and load this package.
adb install dynamic-output/baseline.apk
adb shell pm list instrumentation
PKG=$(adb shell pm list instrumentation | sed -n 's|instrumentation:\([^/]*\)/moe.matsuri.nb4a.core.CoreSmokeInstrumentation .*|\1|p' | tr -d '\r')
test -n "$PKG"
probe() {
  result=$(adb shell am instrument -w -e action "$1" -e expected "${2:-内置}" -e version "$CORE_VERSION" "$PKG/moe.matsuri.nb4a.core.CoreSmokeInstrumentation" 2>&1) || {
    printf '%s\n' "$result"
    return 1
  }
  printf '%s\n' "$result"
  [[ "$result" == *"result=PASS"* ]]
  if [[ "$1" != interrupt ]]; then
    # Finishing instrumentation can restart bound processes. Let native initialization
    # finish before the next am instrument kills them; otherwise we simulate a crash.
    sleep 3
    for attempt in $(seq 1 100); do
      state=$(adb shell "run-as $PKG cat no_backup/dynamic-core/state.json" | tr -d '\r')
      if jq -e '(.pending_main // "") == "" and (.pending_bg // "") == ""' <<< "$state" > /dev/null; then break; fi
      sleep 0.1
    done
    if ! jq -e '(.pending_main // "") == "" and (.pending_bg // "") == ""' <<< "$state" > /dev/null; then
      jq '{selected,previous,pending_main,pending_bg,notice}' <<< "$state"
      # Fresh synthetic CI device only; emit crash diagnostics with credential fields masked.
      adb logcat -d -s AndroidRuntime:E libc:F go:E | sed -E 's/(password|token|secret|authorization)([=: ]+)[^[:space:]]+/\1\2[redacted]/Ig'
      exit 1
    fi
  fi
  adb shell am force-stop "$PKG"
}
probe probe 内置
adb push dynamic-output/nekobox-core-x86_64.zip /data/local/tmp/nekobox-core-test.zip
adb shell "run-as $PKG sh -c 'mkdir -p files; cat /data/local/tmp/nekobox-core-test.zip > files/core-test.zip'"
probe stage
probe ui 动态
[[ "$result" == *"$CORE_VERSION（动态）"* ]]
probe builtin
# Update only the CI instrumentation to run data-plane and configuration checks for arbitrary versions.
adb install -r dynamic-output/NekoBox-Dynamic-x86_64.apk
probe probe 内置
probe config
probe ui
probe stage
probe interrupt
probe probe 内置
probe stage
probe probe 动态
probe config 动态
probe ui 动态
probe builtin
probe probe 内置
