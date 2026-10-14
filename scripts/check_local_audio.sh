#!/usr/bin/env bash
# Usage: bash scripts/check_local_audio.sh <adb-serial> <arm64-v8a|x86_64|...>
# Runs packet conversion and asynchronous seek/EOS tests without installing or opening the app.
set -euo pipefail
root="$(cd "$(dirname "$0")/.." && pwd)"
serial="${1:?adb serial required}"; abi="${2:?ABI required}"
sdk="${ANDROID_HOME:-$HOME/Android/Sdk}"
work="$(mktemp -d)"
device_dir="/data/local/tmp/ambient-audio-check-$$"
trap 'rm -rf "$work"; adb -s "$serial" shell rm -rf "$device_dir" >/dev/null 2>&1 || true' EXIT
bash "$root/scripts/build_local_audio.sh" "$sdk/ndk/30.0.16248370" "$work/native" "$abi"
mkdir -p "$work/classes" "$work/androidx/annotation"
cat > "$work/androidx/annotation/Keep.java" <<'JAVA'
package androidx.annotation; public @interface Keep {}
JAVA
media3="$(sed -n 's/^media3 = "\([^"]*\)"/\1/p' "$root/gradle/libs.versions.toml")"
for module in common decoder; do
  aar="$(find "$HOME/.gradle/caches/modules-2/files-2.1/androidx.media3/media3-$module/$media3" -name '*.aar' -print -quit)"
  unzip -p "$aar" classes.jar > "$work/$module.jar"
done
guava="$(find "$HOME/.gradle/caches/modules-2/files-2.1/com.google.guava/guava" -name '*.jar' | sort -V | tail -1)"
android="$sdk/platforms/android-36/android.jar"
javac -cp "$work/common.jar:$work/decoder.jar:$guava:$android" -d "$work/classes" \
  "$work/androidx/annotation/Keep.java" \
  "$root/app/src/main/java/com/ambient/tv/ui/player/audio/LocalAudioBridge.java" \
  "$root/app/src/main/java/com/ambient/tv/ui/player/audio/LocalAudioDecoder.java" \
  "$root/native/ambientaudio/tests/LocalAudioNativeCheck.java" \
  "$root/native/ambientaudio/tests/LocalAudioWorkerCheck.java"
jar cf "$work/input.jar" -C "$work/classes" .
"$sdk/build-tools/36.0.0/d8" --min-api 29 --lib "$android" --output "$work/check.jar" \
  "$work/input.jar" "$work/common.jar" "$work/decoder.jar" "$guava" 2>"$work/d8.log" || { cat "$work/d8.log"; exit 1; }
ffmpeg -hide_banner -loglevel error -f lavfi -i 'aevalsrc=0.05*sin(2*PI*440*t):s=48000:c=5.1' -t 0.32 -c:a eac3 -b:a 640k "$work/test.eac3"
ffmpeg -hide_banner -loglevel error -f lavfi -i 'aevalsrc=0.05*sin(2*PI*440*t):s=48000:c=7.1' -t 0.32 -c:a truehd -strict -2 "$work/test.thd"
ffprobe -v error -show_entries packet=size -of csv=p=0 "$work/test.thd" > "$work/test.sizes"
adb -s "$serial" shell mkdir -p "$device_dir"
adb -s "$serial" push "$work/check.jar" "$work/test.eac3" "$work/test.thd" "$work/test.sizes" \
  "$work/native/$abi/libambientaudio.so" "$root/app/src/main/jniLibs/$abi/libavcodec.so" \
  "$root/app/src/main/jniLibs/$abi/libavutil.so" "$root/app/src/main/jniLibs/$abi/libswresample.so" "$device_dir/" >/dev/null
runner="LD_LIBRARY_PATH=$device_dir CLASSPATH=$device_dir/check.jar app_process -Djava.library.path=$device_dir /system/bin"
adb -s "$serial" shell "$runner LocalAudioNativeCheck $device_dir/test.eac3"
adb -s "$serial" shell "$runner LocalAudioNativeCheck $device_dir/test.thd $device_dir/test.sizes"
adb -s "$serial" shell "$runner LocalAudioWorkerCheck $device_dir/test.eac3"
