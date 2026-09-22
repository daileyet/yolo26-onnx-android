# Task1 实施计划与验证记录 — Android 摄像头实时道闸检测 Demo

对应 `.agent/plans/task1-overview.md` 的落地实施。确认项（见 `.agent/todo.md` 的「确认」小节）：
包名 `com.openthinks.onnx.example`、App 名「道闸检测 Demo」、自行验证、`arm64-v8a + x86_64`、
检测参数不做 UI 可调、不用 CameraX、无额外功能（截图/日志等）。

## 1. 交付物清单

1. `<repo>/settings.gradle`、`build.gradle`、`gradle.properties`、`local.properties`、`gradlew`、`gradle/wrapper/*`
2. `<repo>/app/build.gradle`、`app/proguard-rules.pro`
3. `<repo>/app/src/main/AndroidManifest.xml`
4. `<repo>/app/src/main/res/layout/activity_main.xml`、`res/values/strings.xml`
5. `<repo>/app/src/main/assets/yolo26_barrier.onnx`（9.3 MB，Ultralytics YOLO26n，opset 12）
6. `app/src/main/java/com/openthinks/onnx/example/` 下 9 个类：
   `MainActivity`、`CameraController`、`CameraImageConverter`、`RotationMapping`、`Letterboxer`、
   `OnnxInferenceEngine`、`Detector`、`Detection`、`CameraFrameView`
7. `app/src/test/java/com/openthinks/onnx/example/DetectorPipelineTest.java`、`RotationMappingTest.java`、`app/src/test/resources/val/*`
8. `<repo>/README.md`、`tools/verify-on-device.sh`
9. 本文档

## 2. 关键实现决策（含理由）

1. **只用一路 YUV ImageReader，不加 TextureView/SurfaceTexture 预览流。**
   预览与检测共用同一份「摆正后的 ARGB 整帧」，坐标系唯一，检测框与预览天然对齐；
   同时避开 Camera2Basic 那套 `configureTransform` 矩阵与检测坐标互相换算的错位风险。
   代价：预览由 CPU 渲染（`Bitmap.setPixels` + `Canvas.drawBitmap` 缩放），720p 每帧约 10~20ms。
2. **输入张量缓冲复用 + 忙时丢帧。** `Letterboxer.fill()` 只在 `inferenceBusy`
   成功 CAS 时写入，保证推理线程读取输入缓冲期间缓冲不被改写；忙时直接丢帧，不做排队。
3. **归一化与 letterbox 固定为硬约束。** `/255.0`、pad 值 0、按 `scale/padX/padY` 反算回原帧。
4. **旋转映射独立成纯 Java 类 `RotationMapping`**，用双射（bijection）+ 方向语义单测覆盖，
   因为 90/270 写反会导致整帧横竖颠倒（最容易错且最难肉眼定位的一环）。
5. **不引入 `onnxruntime-extensions-android`。** 该库最新仅 0.13.0（2024-10），与 ORT 1.26 版本跨度大，
   而本模型 368 个节点全是标准算子，无自定义算子可用，注册 custom op library 只会增加崩溃面。
6. **ABI 裁剪为 `arm64-v8a + x86_64`。** 不裁剪时 debug APK 为 108.4MB；裁剪后 66.3MB。
7. **单元测试绕开 JPG/ImageIO。** Android 单元测试的引导类路径是 `android.jar`，不含 `java.awt`/`javax.imageio`，
   故测试图以 `[int w][int h][ARGB...]` 的 raw 形式存放；同时排除 `onnxruntime-android`，测试改用同版本主机版 `onnxruntime` jar。
8. **无 Android 依赖的核心逻辑（`Letterboxer`/`Detector`/`RotationMapping`/`Detection`）单独成类，可在 JVM 上直接验证。**

## 3. 构建与验证记录（本机实测）

构建环境：JDK 21.0.12、Gradle 9.0.0（本机 wrapper 缓存）、AGP 8.9.1、compileSdk 34、build-tools 34.0.0、
SDK `local.properties → sdk.dir=<Android SDK 路径>`。

1. `./gradlew :app:assembleDebug` → BUILD SUCCESSFUL，产出
   `app/build/outputs/apk/debug/app-debug.apk`（66.3MB），APK 内含
   `lib/arm64-v8a/libonnxruntime.so`(27.4MB)、`lib/x86_64/libonnxruntime.so`(33.2MB)、
   `assets/yolo26_barrier.onnx`(9.79MB)。
2. `./gradlew :app:testDebugUnitTest` → 6 个测试全部 PASSED：
   - `valImagesDetectionsMatchGroundTruth`：真实模型 + 真实标注图，
     `img_0001 barrier_closed score=0.96 IoU=0.92`、`img_0004 barrier_raising score=0.91 IoU=0.92`、
     `img_0008 barrier_open score=0.98 IoU=0.98`（阈值：IoU>0.7、score>0.5）；
   - `letterboxHandlesNonSquareFrame`：16:9 裁剪后仍然检出，IoU=0.77（验证 scale/pad 反算）；
   - `decodesBoxesAndSuppressesDuplicates`：合成张量验证阈值过滤与同类 NMS；
   - `letterboxGeometryAndNchwLayout`：1280x720 源验证 `scale=0.5 / padX=0 / padY=140`、NCHW 布局、
     RGB 通道顺序、pad 区域为 0；
   - `RotationMappingTest` 两项：双射性与 90/180/270 方向语义。
3. 模拟器运行验证：**受阻**。
   `emulator -accel-check` 报 `This user doesn't have permissions to use KVM (/dev/kvm)`，
   启动报 `ERROR | x86_64 emulation currently requires hardware acceleration!` 后退出（`/dev/kvm` 为 `root:kvm 660`，
   当前用户不在 `kvm` 组且 `sudo` 需要密码）。`adb devices` 亦无真机连接。

## 4. 尚未验证的部分（需要设备）

1. camera2 采集链路：会话配置、`ImageReader` 出帧尺寸选择（`1280x720` 优先）、`onPause/onResume` 释放与重开。
2. `CameraImageConverter` 的 `YUV_420_888 → ARGB` 在真实 `rowStride/pixelStride` 下的正确性
   （U/V 平面的 pixelStride=2 情形已被代码覆盖，但未在设备上跑过）。
3. 实测帧率、单帧推理耗时、预览与检测框的对齐效果。
4. 前后摄切换、检测开关的 UI 行为。

设备侧一键验证：`tools/verify-on-device.sh`（安装 → 授权 → 启动 → 截图 → 点「开启检测」→ 截图 →
点「切换摄像头」→ 截图 → 检查 logcat 崩溃）。该脚本在无设备环境下无法执行，未经本机实测。

模拟器路线（任选其一）：

1. `sudo gpasswd -a $USER kvm` 后重新登录，再用
   `$ANDROID_SDK_ROOT/emulator/emulator -avd Pixel_Tablet -camera-back virtualscene -camera-front emulated -no-snapshot`；
2. 临时放开权限：`sudo setfacl -m u:$USER:rw /dev/kvm`（重启后失效）。

## 5. 风险与后续

1. 预览渲染占用 CPU：若真机上帧率偏低，可切换为「TextureView 预览 + ImageReader 检测」双流方案，
   但必须同步处理 transform 矩阵与检测坐标映射（本次刻意规避该复杂度）。
2. `CONTROL_AF_MODE_CONTINUOUS_PICTURE` 在部分设备不可用时，可退回 `AUTO` 或关闭 AF。
3. 前摄未做镜像（见 `README.md` 第 7 节）。
4. 若后续需要更高帧率：可把采集尺寸降到 `960x540`、启用 NNAPI 或改用 FP16 量化模型。

## 6. 决策记录（原 [待确认] 项）

1. 设备验证方式：用户选择「接受当前验证水平」（构建通过 + 6 项单测含真实模型端到端 IoU 0.92/0.92/0.98），
   Task 1 已标注 `[完成]`；`tools/verify-on-device.sh` 保留，待有设备时可直接执行补做运行验证。
2. 预览展示方式：保持等比缩放居中（两侧留黑边、不裁切），检测框坐标映射绝对准确。

## 7. 修复记录（SIGSEGV in camera-capture，用户设备实测发现）

1. 现象：`Fatal signal 11 (SIGSEGV), code 1 (SEGV_MAPERR)`，崩溃线程 `camera-capture`（相机 HandlerThread），
   崩溃后 2 秒出现新的 `采集尺寸 1280x720`（相机被重新拉起），主线程出现 `Davey! duration=8500ms`。
2. 根因：原 `CameraController.stop()` 在 UI 线程直接 `close(session/device/ImageReader)`，
   与相机线程正在读取 `Image` 的 plane（HAL 原生内存）/ 写预览位图并发 → use-after-free。
3. 修复：
   - `CameraController` 增加 `frameLock` + `tearingDown`：帧处理全程持锁；`stop()` 先 `stopRepeating()/abortCaptures()`，
     再在锁内等待在处理的帧结束，最后 `close()`（详见 `README.md` 第 9 节）。
   - `CameraFrameView` 不再 `recycle()` 旧位图（避免 RenderThread 踩已释放像素内存），并校验 `setPixels` 入参。
   - `CameraImageConverter.copyPlane` 用 `buffer.limit()` 做上界校验，越界填 0 并告警。
   - `MainActivity.onDestroy` 先停并等待推理线程结束，再释放相机、最后关闭 ORT 会话。
4. 复测：`./gradlew :app:assembleDebug :app:testDebugUnitTest` 通过（7 项单测 0 失败），APK 已含新代码。
5. 模拟器运行验证（`emulator-5554`）：连续切换摄像头 8 次 + 检测开关 + 检测态切换 4 次 →
   App 崩溃缓冲 0 条、无 SIGSEGV、`丢弃失效帧`/`处理帧失败` 均 0 次，预览两路画面亮度交替（切换确实生效）。
6. 第二轮补充修复（来自用户实测栈与模拟器日志）：
   - `onImageAvailable` 增加 `tearingDown || reader != imageReader` 判断，并在锁内用 `image.getWidth()` 探测
     `Image` 有效性 —— 解决迟到回调拿到已被关闭 `Image` 的 `IllegalStateException`；
   - `getRotationDegrees()` 改为使用缓存的 `[sensorOrientation, LENS_FACING]`（原来每帧 binder 调用
     `getCameraCharacteristics()`，实测出现 544ms monitor contention）；
   - 移除 `stop()` 中的 `abortCaptures()`（模拟器 ranchu HAL 崩溃栈位于 `waitFlushingDone`，与这记重锤相关）。
7. 遗留（非本 App 缺陷，见 `README.md` 第 9.5 节）：模拟器 ranchu camera HAL 反复切换时自身 SIGABRT；
   软件渲染模拟器上长跑后 App 被 ANR 强杀（预览路径 CPU 开销大，**预览优化已决定不做**）。
