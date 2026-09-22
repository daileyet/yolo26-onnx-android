# 道闸检测 Demo（Android + camera2 + ONNX Runtime）

手机摄像头实时目标检测 Demo：camera2 原生 API 采集 YUV 帧 → ONNX Runtime 跑 YOLO26 道闸检测模型 →
在预览画面上叠加检测框。支持前/后摄像头切换与「开启/关闭检测」开关。

## 1. 模型契约（不要随意改）

模型：`app/src/main/assets/yolo26_barrier.onnx`（源文件 `model/yolo26_barrier.onnx`，Ultralytics YOLO26n，opset 12，9.3MB）

1. 输入 `images`：`[1, 3, 640, 640]`，`float32`，**RGB + NCHW**，并且**必须 /255.0 归一化到 0~1**。
   实测：直接送 0~255 原值会输出上百个 score=1.0 的垃圾框（框位全部错误）。
2. 输出 `output0`：`[1, 7, 8400]` = `4 + 3`：
   - 通道 0..3：`cx, cy, w, h`，已是解码后的中心式坐标，位于 640x640 letterbox 空间；
   - 通道 4..6：三个类别分数，图中已含 Sigmoid（**没有 objectness，也没有 DFL，无需额外解码**）。
3. 类别：`0 barrier_closed`、`1 barrier_open`、`2 barrier_raising`。
4. 后处理：`conf >= 0.5` 过滤 + 按类 NMS(`IoU 0.45`)，最多 20 个框，并丢弃“大框 + 低置信度”的整图猜测
   （`Detector` 中的常量，取值依据见第 8 节）。
5. 非方形输入必须 letterbox（等比缩放 + 居中填充），框需按 `scale / padX / padY` 反算回原帧，
   否则框会整体偏移。

## 2. 目录结构

```
.
├── app/
│   ├── build.gradle                     # AGP 8.9.1 / compileSdk 34 / abiFilters arm64-v8a+x86_64 / ORT 1.26.0
│   └── src/
│       ├── main/
│       │   ├── AndroidManifest.xml      # CAMERA 权限、竖屏锁定
│       │   ├── assets/yolo26_barrier.onnx
│       │   ├── res/layout/activity_main.xml
│       │   └── java/com/openthinks/onnx/example/
│       │       ├── MainActivity.java          # 权限、开关、摄像头切换、帧分发
│       │       ├── CameraController.java      # camera2 封装（ImageReader + 会话 + 旋转角）
│       │       ├── CameraImageConverter.java  # YUV_420_888 -> 摆正后的 ARGB 整帧
│       │       ├── RotationMapping.java       # 旋转坐标映射（纯 Java，可单测）
│       │       ├── Letterboxer.java           # ARGB -> 640x640 NCHW float 张量（纯 Java，可单测）
│       │       ├── OnnxInferenceEngine.java   # ORT 会话与推理（assets 加载 + 复用输入缓冲）
│       │       ├── Detector.java              # 解码 + 按类 NMS（纯 Java，可单测）
│       │       ├── Detection.java             # 检测结果（归一化中心式坐标）
│       │       └── CameraFrameView.java       # 预览位图 + 检测框叠绘 + fps/耗时角标
│       └── test/                              # JVM 单元测试（含真实模型 + 真实标注图）
│           ├── java/.../DetectorPipelineTest.java
│           ├── java/.../RotationMappingTest.java
│           └── resources/val/*.raw|*.txt
├── model/yolo26_barrier.onnx            # 模型原始文件（assets 里的副本来自它）
├── doc/research.md                      # ONNX Runtime Android 用法参考
├── tools/verify-on-device.sh            # 真机/模拟器一键验证脚本
└── .agent/plans/task1-*.md              # 可行性分析 + 实施计划
```

## 3. 构建

```bash
cd /export02/dad2szh/onnx_yolo26
./gradlew :app:assembleDebug          # 产出 app/build/outputs/apk/debug/app-debug.apk（约 66MB）
./gradlew :app:testDebugUnitTest      # JVM 单元测试（含真实模型推理）
```

环境（本机已验证）：

1. JDK 21（`/usr/lib/jvm/java-21-openjdk-amd64`，默认 `java` 即 21）。
2. Gradle 9.0.0（`gradle/wrapper` 指向 `gradle-9.0.0-bin.zip`，本机 `~/.gradle/wrapper/dists` 已缓存，可离线）。
3. Android SDK：`local.properties` 里的 `sdk.dir`；需要 `platforms/android-34`、`build-tools/34.0.0`。
4. 依赖仓库：`maven.aliyun.com` 的 public/google/gradle-plugin 镜像（见 `build.gradle`）。
   注意 `public` 不代理 `com.android.tools.build`（会 404），所以 `google` 镜像必须保留。

## 4. 架构与线程模型

```
camera2 ImageReader(YUV_420_888)
        │ 相机线程(onImageAvailable)
        ▼
CameraImageConverter ──► ARGB 整帧(摆正)  ──► CameraFrameView.updateFrame() ──► 预览
        │
        └─(检测开启且推理空闲时)─► Letterboxer ──► FloatBuffer(1x3x640x640)
                                                      │
                                            推理线程(单线程) ──► OnnxInferenceEngine.run()
                                                      │                  │
                                                      ▼                  ▼
                                            Detector.detect() ──► CameraFrameView.setDetections()
```

1. 只用一路输出流（`ImageReader`），预览与检测共用同一份 ARGB 整帧：
   坐标系唯一，检测框天然与预览对齐（避免 TextureView transform 与检测坐标不一致的经典错位）。
2. 丢帧策略：`inferenceBusy` 标志 + 单线程池，推理忙时直接丢弃当前帧，不做排队，避免延迟累积。
3. 输入缓冲复用：`Letterboxer.fill()` 只在抢到 `inferenceBusy` 时写入，保证推理线程读取期间缓冲不被改写。
4. 旋转角：`sensorOrientation` + 屏幕旋转计算（后摄 `sensor - device`，前摄 `sensor + device`），
   映射逻辑在 `RotationMapping`，有双向（bijection）与方向语义单测。
5. 生命周期：`onResume` 开相机、`onPause` 关相机并释放 `ImageReader`，`onDestroy` 关闭 ORT 会话与线程池。

## 5. 在设备上验证

```bash
# 需要 adb 能连到设备（真机，或启用了 KVM 的模拟器）
tools/verify-on-device.sh
```

脚本会：安装 APK → 授予 CAMERA 权限 → 启动 App → 截图（预览）→ 点击「开启检测」→ 再截图 →
抓 logcat 中的 `FATAL/AndroidRuntime` → 输出截图路径。

手工验证要点：

1. 预览是否正常出图（不是全黑、不是花屏、方向正确）；
2. 点「切换摄像头」后画面应变成另一路摄像头，且不黑屏；
3. 点「开启检测」后画面上出现检测框 + 类别名 + 置信度，右上角显示 fps 与推理耗时；
4. 点「关闭检测」后框消失，帧率应回升。

模拟器（`Pixel_Tablet`，`hw.camera.back=virtualscene`）需要 KVM：

```bash
sudo gpasswd -a $USER kvm     # 之后需要重新登录，或临时用 sudo setfacl -m u:$USER:rw /dev/kvm
$ANDROID_SDK_ROOT/emulator/emulator -avd Pixel_Tablet \
    -camera-back virtualscene -camera-front emulated -no-snapshot
```

## 6. 单元测试说明

`./gradlew :app:testDebugUnitTest` 覆盖：

1. `DetectorPipelineTest.valImagesDetectionsMatchGroundTruth`：真实模型 + 3 张真实标注图（三个类别各一张），
   逐图校验类别与 IoU（实测 0.92 / 0.92 / 0.98，置信度 0.91~0.98）。
2. `DetectorPipelineTest.letterboxHandlesNonSquareFrame`：把图裁成 16:9 再跑，验证 letterbox 的 scale/pad 反算
   （实测 IoU 0.77）。
3. `DetectorPipelineTest.decodesBoxesAndSuppressesDuplicates`：构造张量验证置信度过滤与同类 NMS。
4. `DetectorPipelineTest.letterboxGeometryAndNchwLayout`：用红/绿像素标定 letterbox 几何、NCHW 布局、
   RGB 通道顺序与 pad 区域取值。
5. `RotationMappingTest`：旋转映射的双射性与 90/180/270 的方向语义。

测试图放在 `app/src/test/resources/val/*.raw`，格式为 `[int width][int height][width*height 个 int ARGB]`（大端）。
不用 JPG 是因为 Android 单元测试的引导类路径是 `android.jar`，其中没有 `java.awt` / `javax.imageio`。
导出脚本（在项目根目录执行）：

```bash
python3 - <<'EOF'
import numpy as np, struct
from PIL import Image
src='/export02/dad2szh/yolo26_vision/datasets/barrier/images/val/'
dst='app/src/test/resources/val/'
for name in ['img_0001','img_0004','img_0008']:
    im=Image.open(src+name+'.jpg').convert('RGB'); w,h=im.size
    a=np.asarray(im).astype(np.uint32)
    argb=(np.uint32(0xFF000000)|(a[:,:,0]<<16)|(a[:,:,1]<<8)|a[:,:,2]).astype('>u4')
    with open(dst+name+'.raw','wb') as f:
        f.write(struct.pack('>ii', w, h)); f.write(argb.tobytes())
EOF
```

## 7. 已知限制

1. 前摄不做镜像（预览与检测输入都是同一份未镜像的正立画面）——Demo 取舍，如需自拍式镜像
   需同步镜像预览与检测框，或对模型输入做水平翻转。
2. 检测参数（conf 0.5 / NMS 0.45 / 最多 20 个框 / 大框面积与最低分）为 `Detector` 中的代码常量，不提供 UI 调节。
3. Activity 锁定竖屏（`android:screenOrientation="portrait"`），旋转逻辑仍按通用公式实现。
4. 未启用 NNAPI，走 CPU（`setIntraOpNumThreads(2)`）；如需加速可加 `options.addNnapi()`，
   但要留意部分算子在 NNAPI 上回退反而更慢。
5. APK 只含 `arm64-v8a` + `x86_64` 两个 ABI（ORT 的 `libonnxruntime.so` 很大，全 ABI 会到 108MB）。

## 8. 误报抑制规则（阈值取值依据）

模型在陌生场景（非道闸）会把大片区域猜成道闸，实测数据如下（12 张通用图片作负样本、5 张 val 图作正样本，
取证程序为 `/tmp/t2/Probe2.java`，不属于仓库）：

1. 正样本（5 张 val 图，全部正确检出，IoU 0.88~0.98）：命中分数 **0.911~0.981**，命中框面积 **6.3%~49.1%**。
2. 负样本（12 张通用图：巴士/人/猫/狗/街景/室内/食物/洗衣机/自行车/建筑/山景/道路）：
   最高分数 **0.410**（`bus.jpg`，整图框，面积 61.7%）、**0.354**（室内图，面积 13.1%），其余 10 张 ≤ 0.091。

据此确定两条规则（常量都在 `Detector.java`）：

1. `DEFAULT_CONF_THRESHOLD = 0.5`：滤掉上述两个误报，同时相对正样本最低分 0.911 保留 0.41 余量。
2. 大框低分过滤：面积占比 `>= 0.5` 且 `score < 0.7` 的框丢弃（正样本最大命中框 49.1%、分数均 ≥ 0.911，不受影响）。

修复效果（同一批 17 张图复测）：

1. 修复前：12 张负样本中 2 张出现误报框（`bus.jpg` 61.7%、室内图 13.1%）。
2. 修复后：12 张负样本全部 0 个框；5 张正样本的类别、分数、IoU 与修复前完全一致。
3. 单元测试：`DetectorPipelineTest.filtersLowConfidenceLargeBoxGuess` 覆盖该规则（大框低分丢弃 / 大框高分保留 /
   小框中分保留 / 阈值边界 0.49 与 0.51），`./gradlew :app:testDebugUnitTest` 共 7 项全部通过。

## 9. 崩溃修复记录（SIGSEGV in camera-capture）

### 9.1 现象

用户设备实测日志（含 `EGL_emulation`，运行在模拟器上）：

```
Fatal signal 11 (SIGSEGV), code 1 (SEGV_MAPERR), fault addr 0x7c0541faf034 in tid 2911 (camera-capture), pid 2889
... 2 秒后 ... CameraController: 采集尺寸 1280x720      <- 相机被重新拉起
...          HWUI: Davey! duration=8500ms               <- 主线程被卡 8.5 秒
crash_dump64  pid: 2889, tid: 2911, name: camera-capture
```

### 9.2 根因

原 `CameraController.stop()` 在**调用线程（UI 线程）**直接 `close()` 掉 session / device / ImageReader，
而相机线程此时可能正停在 `onImageAvailable` 里读 `Image` 的 plane（YUV 像素是 HAL 的原生内存）
或正在 `Bitmap.setPixels` 写预览位图。释放原生缓冲与读取并发 → use-after-free → 原生 SIGSEGV。
日志时间线也吻合：崩溃后 2 秒出现新的 `采集尺寸`，说明紧接着又重新开了相机（切换摄像头/重启预览）。

### 9.3 修复（4 处）

1. `CameraController`：新增 `frameLock` + `tearingDown` 标志。
   - `onImageAvailable` 全程持锁（acquireLatestImage 捕获 `IllegalStateException`，处理完在锁内 `image.close()`）；
   - `stop()` 先 `stopRepeating()` + `abortCaptures()` 让 HAL 停止出帧，再在 `frameLock` 内等在处理的帧结束，
     最后才 `close()` 三个对象，并先摘掉 ImageReader 的监听器。
2. `CameraFrameView.updateFrame`：不再 `recycle()` 旧位图（硬件加速下 DisplayList 可能仍持有它，
   由 RenderThread 异步使用），改为替换引用交给 GC；同时校验 `setPixels` 入参尺寸。
3. `CameraImageConverter.copyPlane`：所有读取先用 `buffer.limit()` 做上界校验，越界位置填 0 并告警；
   避免个别 HAL 的 plane 布局比理论尺寸小时读到未映射的原生内存。
4. `MainActivity.onDestroy`：先 `shutdown()` 推理线程并 `awaitTermination(3s)`，再 `release()` 相机，
   最后 `close()` ORT 会话 —— 防止“会话已关闭、`run()` 仍在执行”。

### 9.4 复测

1. `./gradlew :app:assembleDebug :app:testDebugUnitTest` → BUILD SUCCESSFUL，7 项单测 0 失败。
2. APK 已确认包含新代码（`classes3.dex` 中可检索到新日志字符串 `rowStride=`，APK 时间戳晚于源码修改时间）。
3. 模拟器实测（`emulator-5554`，用户实例）：复现动作 = 连续切换摄像头 8 次 + 开启检测 + 检测态下再切换 4 次 +
   关闭检测，结果：
   - `logcat -b crash` 中**不含本 App**（0 条），原始的 `camera-capture` SIGSEGV 未再出现；
   - 新增的两处竞态日志计数均为 0（`丢弃失效帧` 0 次、`处理帧失败` 0 次）；
   - 截图亮度交替（预览区平均亮度 67.x ↔ 129.x）证明前后摄切换生效、预览为实时画面。
4. 迟到回调导致的 `IllegalStateException: Image is already closed`（用户实测栈）已定位为
   「停相机后已排队的回调再次 `acquireLatestImage()`，拿到 `ImageReader.close()` 时被关闭的 `Image`」，
   处理方式：回调入口判断 `tearingDown || reader != imageReader` 直接丢弃；锁内用 `image.getWidth()` 探测有效性，
   这类竞态降级为一行 W 日志。
5. 顺带修掉的两个性能/稳定性问题：
   - `getRotationDegrees()` 原来每帧调用 binder 的 `getCameraCharacteristics()`（日志可见
     `Long monitor contention ... for 544ms`，全量 118 次）→ 改为启动时缓存 `[sensorOrientation, LENS_FACING]`；
   - 移除 `abortCaptures()`，只保留 `stopRepeating()`（`close()` 自身会 flush）。

### 9.5 遗留问题（非本 App 缺陷）

1. 模拟器自带的 camera HAL `android.hardware.camera.provider.ranchu` 在快速反复切换时**自身** SIGABRT
   （栈：`#03 ... CameraDeviceSession::waitFlushingDone`，实测 2 次）；HAL 崩溃后 App 后续开相机失败
   （一轮 13 次点击只成功启动 6 次）。属模拟器 HAL 缺陷，App 侧只能靠 `onDisconnected/onError` 报错兜住。
2. 软件渲染模拟器上长跑后 App 被系统 ANR 强杀（`Waited 5185ms for FocusEvent`，CPU 70%）：
   原因是预览路径 CPU 开销大（YUV→RGB 720p + 2560x1600 位图缩放绘制），且 `onDraw` 与相机线程共用 `frameLock`。
   **该优化已明确不做**（2026-09 用户决定），如需提升流畅度可考虑：预览转换降到 640x360、改为 TextureView 双流、
   或为预览单独加锁。
