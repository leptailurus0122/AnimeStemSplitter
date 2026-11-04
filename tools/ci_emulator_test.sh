#!/bin/bash
# エミュレーター上で自動テストを実行し、ログを残す
adb logcat -c
./gradlew --no-daemon connectedDebugAndroidTest
status=$?
adb logcat -d > logcat.txt
adb shell run-as com.animestemsplitter cat files/diag/crash.txt > device_crash.txt 2>/dev/null || true
adb shell run-as com.animestemsplitter cat files/diag/running.txt > device_running.txt 2>/dev/null || true
exit $status
