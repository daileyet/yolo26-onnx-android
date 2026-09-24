#!/usr/bin/env bash
# 真机 / 模拟器一键验证脚本：
#   安装 APK -> 授相机权限 -> 启动 -> 截图(预览) -> 点击「开启检测」-> 截图
#   -> 点击「切换摄像头」-> 截图 -> 切换模型（下拉框选另一个模型）-> 截图 -> 检查 logcat 崩溃
#
# 用法: tools/verify-on-device.sh [apk路径]
# 依赖: adb（ANDROID_SDK_ROOT / ANDROID_HOME 下的 platform-tools/adb，或仓库 local.properties 的 sdk.dir）、python3
#
# 注意：需要 adb 能连到设备（真机，或已启用 KVM 的模拟器：sudo gpasswd -a $USER kvm 后重新登录）。

set -uo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
# SDK 路径不写死本机路径：优先环境变量，其次仓库里的 local.properties（sdk.dir=...）
SDK="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-}}"
if [ -z "$SDK" ] && [ -f "$ROOT/local.properties" ]; then
  SDK="$(sed -n 's/^sdk\.dir=//p' "$ROOT/local.properties" | head -n 1)"
fi
if [ -z "$SDK" ]; then
  echo "[FAIL] 未找到 Android SDK：请设置 ANDROID_SDK_ROOT / ANDROID_HOME，或在 local.properties 里写 sdk.dir" >&2
  exit 1
fi
ADB="${ADB:-$SDK/platform-tools/adb}"
PKG="com.openthinks.onnx.example"
APK="${1:-$ROOT/app/build/outputs/apk/debug/yolo26-onnx-example.apk}"
OUT="${OUT_DIR:-$ROOT/build/verify}"
mkdir -p "$OUT"

fail() { echo "[FAIL] $*"; exit 1; }
step() { echo; echo "== $* =="; }
snap() {
  "$ADB" shell screencap -p "/sdcard/$1.png" >/dev/null 2>&1
  "$ADB" pull "/sdcard/$1.png" "$OUT/$1.png" >/dev/null 2>&1
}
dump_ui() {
  "$ADB" shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1 || return 1
  "$ADB" pull /sdcard/ui.xml "$OUT/ui.xml" >/dev/null 2>&1 || return 1
}

# 找出「文本(text)匹配」或「资源 id 后缀匹配」控件中心点，输出 "x y"
find_center() {
  local mode="$1" needle="$2"
  dump_ui || return 1
  python3 - "$OUT/ui.xml" "$mode" "$needle" <<'PY'
import re, sys
xml = open(sys.argv[1], encoding='utf-8', errors='ignore').read()
mode, needle = sys.argv[2], sys.argv[3]
for m in re.finditer(r'<node[^>]*>', xml):
    node = m.group(0)
    text = re.search(r'text="([^"]*)"', node)
    rid = re.search(r'resource-id="([^"]*)"', node)
    hit = (mode == 'id' and rid and rid.group(1).endswith(needle)) or \
          (mode == 'text' and text and text.group(1) == needle)
    if hit:
        b = re.search(r'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', node)
        if b:
            x1, y1, x2, y2 = map(int, b.groups())
            print((x1 + x2) // 2, (y1 + y2) // 2)
            break
PY
}

# 打印状态栏文字（status_text）
status_text() {
  dump_ui || return 0
  python3 - "$OUT/ui.xml" <<'PY'
import re, sys
xml = open(sys.argv[1], encoding='utf-8', errors='ignore').read()
for m in re.finditer(r'<node[^>]*>', xml):
    node = m.group(0)
    rid = re.search(r'resource-id="([^"]*)"', node)
    if rid and rid.group(1).endswith('status_text'):
        text = re.search(r'text="([^"]*)"', node)
        print(text.group(1) if text else '')
        break
PY
}

[ -x "$ADB" ] || fail "找不到 adb: $ADB（请设置 ANDROID_SDK_ROOT）"
[ -f "$APK" ] || fail "APK 不存在: $APK（先执行 ./gradlew :app:assembleDebug）"

step "等待设备"
"$ADB" wait-for-device || fail "没有可用设备（adb devices 为空）"
"$ADB" devices

step "安装 APK"
"$ADB" install -r -t "$APK" || fail "安装失败"

step "授予相机权限并启动"
"$ADB" shell pm grant "$PKG" android.permission.CAMERA || true
"$ADB" shell am force-stop "$PKG" || true
"$ADB" logcat -c || true
"$ADB" shell am start -W -n "$PKG/.MainActivity" | tail -3
sleep 12
echo "当前状态: $(status_text)"

step "截图: 预览"
snap "preview"

step "点击「开启检测」"
COORDS="$(find_center text '开启检测')"
if [ -z "$COORDS" ]; then
  fail "未找到「开启检测」按钮（App 未启动或相机权限被拒）"
fi
"$ADB" shell input tap $COORDS
sleep 5
snap "detect_on"
echo "当前状态: $(status_text)"

step "点击「切换摄像头」"
COORDS="$(find_center text '切换摄像头')"
if [ -n "$COORDS" ]; then
  "$ADB" shell input tap $COORDS
  sleep 5
  snap "camera_switched"
  echo "当前状态: $(status_text)"
fi

step "切换模型（下拉框选另一个模型）"
COORDS="$(find_center id 'model_spinner')"
if [ -n "$COORDS" ]; then
  "$ADB" shell input tap $COORDS
  sleep 2
  snap "spinner_open"
  # 优先切到 COCO 通用模型；找不到就试道闸模型
  TARGET="通用模型(COCO 80类)"
  COORDS="$(find_center text "$TARGET")"
  if [ -z "$COORDS" ]; then
    TARGET="道闸模型(3类)"
    COORDS="$(find_center text "$TARGET")"
  fi
  if [ -n "$COORDS" ]; then
    "$ADB" shell input tap $COORDS
    sleep 12
    snap "model_switched"
    echo "选中模型: $TARGET"
    echo "当前状态: $(status_text)"
  else
    echo "[WARN] 下拉列表里没找到模型项（可查看 $OUT/spinner_open.png）"
  fi
else
  echo "[WARN] 未找到模型下拉框（model_spinner）"
fi

step "检查崩溃日志"
CRASH_HITS="$("$ADB" logcat -b crash -d 2>/dev/null | grep -c "$PKG" || true)"
echo "crash buffer 中本包名命中行数: $CRASH_HITS"
"$ADB" logcat -b crash -d 2>/dev/null | head -20
"$ADB" logcat -d -v brief 2>/dev/null | grep -aE "Fatal signal|beginning of crash" > "$OUT/fatal.log" || true
if [ -s "$OUT/fatal.log" ]; then
  echo "-- 全量日志里的 Fatal 记录（看 Cmdline 判断是谁崩了）--"
  head -5 "$OUT/fatal.log"
fi
if [ "$CRASH_HITS" != "0" ]; then
  fail "本 App 出现在 crash buffer 中"
fi

echo
echo "[OK] 产物输出到 $OUT: preview.png / detect_on.png / camera_switched.png / model_switched.png"
echo "     请核对：预览正常、开启检测后有检测框且标签属于当前模型、切模型后状态文案与标签类别随之改变。"
