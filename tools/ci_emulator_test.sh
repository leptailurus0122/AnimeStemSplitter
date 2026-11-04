#!/bin/bash
# エミュレーター上で自動テストを実行し、ログを残す
adb logcat -c
./gradlew --no-daemon connectedDebugAndroidTest
status=$?
adb logcat -d > logcat.txt
echo "===== logcat: crash / test lines ====="
grep -E "FATAL|AndroidRuntime|DEBUG   :|libc    :|backtrace|#[0-9]{2} pc|OnDeviceTest|onnxruntime|MediaCodec.*(rror|xception)|Abort|SIGSEGV|SIGABRT|lowmemory|Process .* has died|Killing" logcat.txt | grep -v "chatty" | head -150
echo "===== app diag ====="
adb shell run-as com.animestemsplitter cat files/diag/crash.txt 2>/dev/null || true
adb shell run-as com.animestemsplitter cat files/diag/running.txt 2>/dev/null || true
exit $status
