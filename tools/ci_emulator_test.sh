#!/bin/bash
# エミュレーター上で自動テストを実行し、ログを残す
adb logcat -c
./gradlew --no-daemon connectedDebugAndroidTest -Pandroid.injected.androidTest.leaveApksInstalledAfterRun=true
status=$?
adb logcat -d > logcat.txt
echo "===== exit-info ====="
adb shell dumpsys activity exit-info com.animestemsplitter | head -80
echo "===== crash buffer ====="
adb logcat -d -b crash | head -150
pid=$(grep -oP 'Process com\.animestemsplitter \(pid \K[0-9]+' logcat.txt | head -1)
echo "===== app process log (pid=$pid) ====="
if [ -n "$pid" ]; then grep -E "^\S+ \S+ +$pid " logcat.txt | grep -v -E "progress|Access denied" | tail -100; fi
echo "===== around death ====="
grep -n "has died" logcat.txt | head -3
ln=$(grep -n "Process com.animestemsplitter (pid" logcat.txt | head -1 | cut -d: -f1)
if [ -n "$ln" ]; then sed -n "$((ln-40)),$((ln+5))p" logcat.txt | grep -v -E "Access denied|achh|nativeloader"; fi
echo "===== screenshots ====="
for n in 1_main 2_trim 3_trim_zoom_playing 3b_trim_bgm 4_saved; do
  adb exec-out run-as com.animestemsplitter cat files/shots/$n.png > shot_$n.png 2>/dev/null || true
  if [ -s shot_$n.png ]; then echo "SHOT-BEGIN $n"; base64 -w 3000 shot_$n.png; echo "SHOT-END $n"; fi
done
exit $status
