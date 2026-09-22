#!/usr/bin/env bash
# 真机 / 模拟器一键验证脚本：
#   安装 APK -> 授予 CAMERA 权限 -> 启动 -> 截图(预览) -> 点击「开启检测」-> 截图 -> 点击「切换摄像头」-> 截图
#   -> 检查 logcat 是否有 FATAL EXCEPTION
#
# 用法: tools/verify-on-device.sh [apk路径]
# 依赖: adb（ANDROID_SDK_ROOT 或 ANDROID_HOME 下的 platform-tools/adb）、python3
#
# 注意：本脚本在无设备的环境下无法执行；请确保 adb devices 能看到设备（真机，
#       或已启用 KVM 的模拟器：sudo gpasswd -a $USER kvm 后重新登录）。

set -uo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
SDK="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-/export02/dad2szh/android/sdk}}"
ADB="${ADB:-$SDK/platform-tools/adb}"
PKG="com.openthinks.onnx.example"
APK="${1:-$ROOT/app/build/outputs/apk/debug/app-debug.apk}"
OUT="${OUT_DIR:-$ROOT/build/verify}"
mkdir -p "$OUT"

fail() { echo "[FAIL] $*"; exit 1; }
step() { echo; echo "== $* =="; }

[ -x "$ADB" ] || fail "找不到 adb: $ADB（请设置 ANDROID_SDK_ROOT）"
[ -f "$APK" ] || fail "APK 不存在: $APK（先执行 ./gradlew :app:assembleDebug）"

step "等待设备"
"$ADB" wait-for-device || fail "没有可用设备（adb devices 为空）"
"$ADB" devices

step "安装 APK"
"$ADB" install -r -t "$APK" || fail "安装失败"

step "授予相机权限并启动"
"$ADB" shell pm grant "$PKG" android.permission.CAMERA || true
"$ADB" logcat -c || true
"$ADB" shell am start -W -n "$PKG/.MainActivity" | tail -3
sleep 8

step "截图: 预览"
"$ADB" shell screencap -p /sdcard/verify_preview.png \
  && "$ADB" pull /sdcard/verify_preview.png "$OUT/preview.png" >/dev/null

# 通过 uiautomator dump 找到按钮坐标再点击，避免写死屏幕分辨率
tap_text() {
  local text="$1"
  "$ADB" shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1 || return 1
  "$ADB" pull /sdcard/ui.xml "$OUT/ui.xml" >/dev/null 2>&1 || return 1
  python3 - "$OUT/ui.xml" "$text" <<'PY'
import re, sys
xml = open(sys.argv[1], encoding='utf-8', errors='ignore').read()
target = sys.argv[2]
for m in re.finditer(r'<node[^>]*>', xml):
    node = m.group(0)
    if 'text="%s"' % target in node:
        b = re.search(r'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', node)
        if b:
            x1, y1, x2, y2 = map(int, b.groups())
            print((x1 + x2) // 2, (y1 + y2) // 2)
            break
PY
}

step "点击「开启检测」"
COORDS="$(tap_text '开启检测')"
if [ -z "$COORDS" ]; then
  fail "未找到「开启检测」按钮（App 未启动或相机权限被拒）"
fi
"$ADB" shell input tap $COORDS
sleep 5
"$ADB" shell screencap -p /sdcard/verify_detect.png \
  && "$ADB" pull /sdcard/verify_detect.png "$OUT/detect.png" >/dev/null

step "点击「切换摄像头」"
COORDS="$(tap_text '切换摄像头')"
if [ -n "$COORDS" ]; then
  "$ADB" shell input tap $COORDS
  sleep 5
  "$ADB" shell screencap -p /sdcard/verify_switch.png \
    && "$ADB" pull /sdcard/verify_switch.png "$OUT/switch.png" >/dev/null
fi

step "检查崩溃日志"
"$ADB" logcat -d -v brief | grep -E "FATAL EXCEPTION|beginning of crash|AndroidRuntime" > "$OUT/crash.log" || true
if [ -s "$OUT/crash.log" ]; then
  cat "$OUT/crash.log"
  fail "日志中发现崩溃"
fi

echo
echo "[OK] 截图已输出到 $OUT: preview.png / detect.png / switch.png"
echo "     请人工确认：预览正常出图、开启检测后画面出现检测框、切换摄像头画面正确切换。"
