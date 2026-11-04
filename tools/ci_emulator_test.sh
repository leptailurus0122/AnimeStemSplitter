#!/bin/bash
# エミュレーター上で自動テストを実行し、ログを残す
adb logcat -c
adb logcat -b crash -c
./gradlew --no-daemon connectedDebugAndroidTest
status=$?
adb logcat -d > logcat.txt
echo "===== crash buffer ====="
adb logcat -d -b crash | head -200
echo "===== app log (filtered) ====="
grep -E "com.animestemsplitter|OnDeviceTest|TestRunner|DEBUG|libc|AndroidRuntime|CCodec|MediaCodec|onnx" logcat.txt \
  | grep -v -E "Access denied|achh" | tail -120
echo "===== app diag ====="
adb shell run-as com.animestemsplitter cat files/diag/crash.txt 2>/dev/null || true
adb shell run-as com.animestemsplitter cat files/diag/running.txt 2>/dev/null || true
exit $status
