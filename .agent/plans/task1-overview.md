# Task1 可行性分析与实现方案 — Android 手机摄像头实时目标检测

本文档为 `.agent/todo.md` Task 1 的 plan-first 产出：先给可行性结论（含本机实测证据），再给实现方案，最后是 `[待确认]` 项。实施阶段另出 `task1-impl-plan.md`。

## 1. 目标与范围

1. 在仓库根目录（`<repo>`）下创建 Android Java 项目（非 Python 版本）。
2. 主界面实时预览手机摄像头画面，支持前/后摄像头切换。
3. 提供「检测」按钮，开启后在实时画面上叠加检测框（类别 + 置信度）。
4. 推理基于已训练模型 `app/src/main/assets/yolo26_barrier.onnx`，运行时用 ONNX Runtime Android，相机用 camera2 原生 API。
5. 不在范围内：训练/量化、模型下载、云端推理、Python 侧脚本。

## 2. 可行性分析（含本机实测证据）

### 2.1 模型侧 [已验证]

用本机 `onnxruntime 1.26.0` + `onnx` 直接读取 `app/src/main/assets/yolo26_barrier.onnx` 得到的事实：

1. 输入：`images`，形状 `[1, 3, 640, 640]`，`tensor(float)`。
2. 输出：`output0`，形状 `[1, 7, 8400]`，`tensor(float)`；`4 (xywh) + 3 (类别分数)`。
3. 元数据：`task=detect`、`head=Detect`、`imgsz=[640,640]`、`opset=12`、`stride=32`、`end2end=False`、`nms=None`、`batch=1`、Ultralytics 8.4.157。
4. 类别：`0 barrier_closed`、`1 barrier_open`、`2 barrier_raising`。
5. 图结构：输出前 `Sigmoid` 已作用于类别分数 → **无 objectness、类别分数已是概率**；框已解码为 `xywh(中心式, 640 像素空间)`，**没有 DFL 需要处理**。
6. 图中输入侧第一个算子直接是 `Conv(images)`，**没有内建归一化** → 输入张量必须是 `0~255` 的原值再做 `/255`？实测结论见下一条。

用 `<数据集根>/datasets/barrier/images/val` 的 5 张真实验证图做端到端实测（脚本 `/tmp/t1/probe.py`，CPU 推理）：

| 预处理组合 | 结果 |
|---|---|
| 原值 0~255 直接送入（resize 或 letterbox） | 输出 771~1292 个置信度 1.00 的垃圾框，与 GT IoU ≈ 0.00~0.14，**完全不可用** |
| 像素 `/255.0` 归一化（resize 或 letterbox） | 每图 1 个正确检测，置信度 0.91~0.98，与 GT IoU 0.88~0.98，**正确** |

结论（写进代码的硬约束）：

1. 预处理 = `RGB` + `NCHW` + `float32` + **`/255.0` 归一化** + letterbox 到 `640x640`。
2. 后处理 = `conf >= 0.5` 过滤 + 按类 NMS(`iou 0.45`) + 大框低分过滤；输出索引布局为 `out[c * 8400 + i]`（`c = 0..3` 为 `cx,cy,w,h`，`c = 4..6` 为三类分数）。
3. letterbox 与直接拉伸 resize 在 val 图（方形）上结果完全一致，但相机帧是 16:9 非方形，**必须 letterbox**，否则框会纵向偏移。
4. 框回映射：先去 letterbox（减 pad、除比例），再按预览/显示方向做旋转与镜像。

### 2.2 运行时侧 [已验证]

1. `com.microsoft.onnxruntime:onnxruntime-android:1.26.0` 已成功解析并打包（见 2.3 实测构建），aar 内含 ABI：`arm64-v8a`、`armeabi-v7a`、`x86`、`x86_64`。
2. 未做 ABI 裁剪时 debug APK 为 **108.4 MB**（四个 ABI 的 `libonnxruntime.so` 共约 100 MB）→ 方案里用 `abiFilters` 或 `splits` 裁剪（arm64-v8a + x86_64 ≈ 60 MB，只留 arm64-v8a ≈ 28 MB）。
3. `onnxruntime-extensions-android` 最新仅 `0.13.0`（2024-10，明显落后于 ORT 1.26），且本模型 368 个算子节点全是标准算子（`Conv/Mul/Sigmoid/Concat/Add/Reshape/Split/Transpose/MatMul/MaxPool/Softmax/Resize/Slice/Sub/Div`）→ **不引入 extensions**，避免 custom op library 版本不匹配导致 `registerCustomOpLibrary` 崩溃。

### 2.3 构建侧 [已验证]

本机实际跑通的链路（实测命令：Gradle 9.0.0 直接二进制 + `:app:assembleDebug`）：

1. JDK：默认 `java 21.0.12`；`/usr/lib/jvm/` 另有 `java-8/17/21-openjdk-amd64` 可切换。AGP 8.9.1 在 JDK 21 下构建通过。
2. Gradle：系统无 `gradle` 命令，但 `~/.gradle/wrapper/dists/gradle-9.0.0-bin/` 已缓存 Gradle **9.0.0** 发行包（无需联网下载发行包）。
3. AGP 8.9.1 本机缓存已存在；`https://maven.aliyun.com/repository/google/com/android/tools/build/gradle/8.9.1/gradle-8.9.1.pom` 返回 200（`public` 仓库不代理 AGP，返回 404，所以 `google` 镜像必须保留）。
4. Android SDK：`sdk.dir=<Android SDK 路径>`，`platforms/android-34`、`platforms/android-36`、`build-tools/34.0.0`、`platform-tools`、`cmdline-tools/latest` 齐全 → `compileSdk 34` + `targetSdk 34` + `minSdk 24` 可用。
5. 实测结果：AGP 8.9.1 + Gradle 9.0.0 + `compileSdk 34` + `onnxruntime-android:1.26.0` → `BUILD SUCCESSFUL in 23s`，产出 108.4 MB debug APK（`/tmp/t1/agrtest/app/build/outputs/apk/debug/app-debug.apk`）。
6. `build.gradle` 沿用 `.agent/todo.md` 给定的 aliyun 镜像 + `google()/mavenCentral()` 备份源配置即可，实测不报仓库解析错误。

### 2.4 运行侧

1. camera2 原生 API 在 API 24+ 无需额外依赖，`TextureView` + `ImageReader(YUV_420_888)` 组合成熟可用。
2. 当前 `adb devices` 为空（无真机连接）；SDK 内已有 `emulator` 与 AVD `Pixel_Tablet`（`android-34/35` 的 `x86_64` 镜像）→ 可先在模拟器上跑通「预览 + 虚拟场景相机 + 检测框叠加」端到端流程（VirtualScene 相机可出图，检测效果本身取决于场景，不能替代真机效果验证）。

## 3. 实现方案

### 3.1 工程结构

```
<repo>/
├── settings.gradle                     # pluginManagement 仓库 + include ':app'
├── build.gradle                        # todo.md 给定的 buildscript/allprojects 镜像配置 + AGP 8.9.1
├── gradle.properties                   # jvmargs / android.useAndroidX=true
├── local.properties                    # sdk.dir=<Android SDK 路径>（不入库）
├── gradlew / gradlew.bat / gradle/wrapper/*   # 由缓存中的 Gradle 9.0.0 生成，distributionUrl=gradle-9.0.0-bin.zip
└── app/
    ├── build.gradle                    # namespace/compileSdk 34/abiFilters/依赖 ORT
    └── src/main/
        ├── AndroidManifest.xml         # CAMERA 权限、feature.camera、screenOrientation
        ├── assets/yolo26_barrier.onnx  # 9.3 MB
        ├── res/layout/activity_main.xml# TextureView + OverlayView + 控件栏
        └── java/com/example/barrierdet/
            ├── MainActivity.java        # 权限申请、相机启停、检测开关、摄像头切换
            ├── CameraController.java    # camera2 封装：openCamera/会话/重复请求/切换
            ├── YuvToRgbConverter.java   # YUV_420_888 → RGB，直接双线性采样到 640x640 letterbox 缓冲
            ├── OnnxInferenceEngine.java # ORT 会话 + 输入张量 + 输出解析
            ├── Detector.java            # 阈值过滤 + 按类 NMS + letterbox/旋转/镜像回映射
            ├── Detection.java           # 结果模型（classId/score/cx/cy/w/h）
            └── DetectionOverlayView.java# 在预览之上画框 + 标签 + FPS/耗时
```

### 3.2 相机管线（camera2）

1. `CameraManager.getCameraIdList()` 遍历 `CameraCharacteristics.LENS_FACING`，分别取 `LENS_FACING_BACK` 与 `LENS_FACING_FRONT` 的 cameraId，实现前后摄切换（切换时先 `closeCamera()` 再 `openCamera(id)`）。
2. 预览：`TextureView` 的 `SurfaceTexture` 作为重复请求目标（`TEMPLATE_PREVIEW`）。
3. 检测帧：另开 `ImageReader`，`YUV_420_888`，尺寸取支持的输出尺寸中接近 `1280x720` 的一档，`setMaxImages(2)`；与预览 Surface 一起放进 `createCaptureSession` 的 output 列表，用 `TEMPLATE_PREVIEW` 重复请求同时喂两路。
4. 帧回调在 `HandlerThread`（`cameraThread`）上执行，回调里只做「拷贝/标记最新帧」，不在相机线程做推理。
5. 旋转角：`(sensorOrientation - displayRotation + 360) % 360`（后摄）/ 前摄再加 180° 处理，前摄预览需水平镜像（`TextureView` 侧用 transform 矩阵，检测框回映射侧同步镜像）。

### 3.3 推理管线（ORT Android）

```java
OrtEnvironment env = OrtEnvironment.getEnvironment();          // 必须先初始化
OrtSession.SessionOptions opts = new OrtSession.SessionOptions();
opts.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT);
opts.setIntraOpNumThreads(2);                                  // 手机端 2~4 合适
byte[] model = readAsset(context, "yolo26_barrier.onnx");      // assets 读取
OrtSession session = env.createSession(model, opts);
```

1. 输入：预分配 `FloatBuffer`（`1*3*640*640`，直接 `allocateDirect` 复用），布局 `[c][y][x]`，值 = `pixel / 255.0f`；
   letterbox pad 值统一填 `0`（val 图为方形，letterbox 不产生 pad，因此原始实验无法对比 pad 取值；
   实施中通过单元测试标定 pad 区域取值与几何关系，见 `task1-impl-plan.md` 第 3 节）。
2. 推理：`session.run(Collections.singletonMap("images", tensor))`，输入名从 `session.getInputNames()` 取，不硬编码。
3. 输出解析：`(float[][][]) results.get(0).getValue()` → `[1][7][8400]`；遍历 `i in 0..8399`，取 `cls = argmax(score[4..6])`，`conf = max`，`conf < 0.5` 直接跳过（阈值取值依据见 `task2-model-capability-analysis.md` 第 8 节）。
4. NMS：按类别分组做 IoU 抑制（且置信度降序），IoU 阈值 0.45，`MAX_DETECTIONS` 限制 20 个，避免 UI 线程被大量框拖慢。
5. 资源：`OnnxTensor`、`OrtSession.Result` 用 try-with-resources 关闭；`OrtSession` 在 Activity `onDestroy` 关闭。

### 3.4 线程模型与生命周期

1. 三个执行体：相机 `HandlerThread`（camera2 回调）、单线程 `ExecutorService`（推理）、UI 线程（绘制）。
2. 丢帧策略：`AtomicReference<Frame>` 只保留最新一帧；推理线程忙时新帧直接覆盖旧帧（检测只看最新画面，不排队），避免延迟累积。
3. 检测开关：关闭时相机回调不再投递帧给推理线程，并清空 `OverlayView` 与最新帧引用。
4. 生命周期：`onResume` 申请权限并开相机；`onPause` 关闭 session/ImageReader（避免占机）；`onDestroy` 关闭 ORT session 与线程池。
5. 帧时间测量：推理耗时 + `CameraCaptureSession.CaptureCallback` 时间戳算端到端延迟，显示在 `OverlayView` 角标（便于验收帧率）。

### 3.5 渲染坐标映射

1. 预览用 `TextureView`，`OverlayView` 叠在同一 `FrameLayout` 上，尺寸一致。
2. 用 `TextureView.getTransform(Matrix)` 拿到预览缩放矩阵，与其逆矩阵把「检测框的预览坐标系坐标」换算成 View 坐标；前摄时 x 轴镜像（`(1 - x)` 或矩阵取负）。
3. letterbox 后的框坐标先减 pad、除 `scale` 得到「旋转后帧坐标」，再按旋转角反算回预览方向，最后乘变换矩阵。

## 4. 风险与规避

1. **纯 Java YUV→RGB 性能**：若先转整帧 RGB 再缩放，720p 每帧约 90 万像素的转换在低端机上会到 20~40 ms。规避：按「输出像素驱动」采样——只对 640x640 每个目标像素做一次双线性采样并直接写进 NCHW `FloatBuffer`，并把 `/255` 合并进采样权重，总工作量降为 `640*640*3` 次乘加。
2. **YUV_420_888 的 rowStride/pixelStride**：Y/U/V 三个 `Plane` 的 `rowStride` 通常不等于宽度，必须按 `rowStride` 逐行取、按 `pixelStride` 取 U/V（NV21/NV12 交错时 `pixelStride=2`），否则画面断裂。
3. **旋转/镜像错位**：框方向与预览不一致是最常见 bug，先实现一个「画满屏十字 + 角标记」的调试模式，确认旋转正确后再开检测。
4. **NNAPI**：默认走 CPU；`addNnapi()` 作为可选开关并捕获异常回退（部分设备 NNAPI 对 `MaxPool/Resize` 回退慢反而更慢）。
5. **APK 体积**：`abiFilters` 至少留 `arm64-v8a`（真机）+ `x86_64`（模拟器）；如只需真机可只留 `arm64-v8a`。
6. **权限**：`CAMERA` 为运行时权限，API 33+ 无需再单独申请存储权限（不落盘）。
7. **模型资产**：`app/src/main/assets/yolo26_barrier.onnx`，9.3 MB，aapt 会压缩它，assets 无 1 MB 限制；若想减少启动解析开销可选 `noCompress 'onnx'`。

## 5. 验收标准

1. `./gradlew :app:assembleDebug` 在本机构建成功。
2. 安装后可实时预览；切换按钮能在前后摄之间切换且画面不黑。
3. 打开「检测」后画面上出现检测框 + 类别名 + 置信度，框随物体移动；关闭后框消失。
4. 检测开启时推理耗时（在 `OverlayView` 角标显示）可读且稳定，不出现持续增长的延迟。
5. 至少完成一次真机或模拟器的安装运行验证（当前无真机，见 `[待确认]`）。

## 6. [待确认]

1. `[待确认]` 应用包名与 App 名称（默认拟用 `com.example.barrierdet` / 「道闸检测 Demo」）。
2. `[待确认]` 验证环境：当前 `adb devices` 无设备。是否接受先用模拟器 `Pixel_Tablet`（VirtualScene 相机）跑通，或由你连真机后我再验证？
3. `[待确认]` 目标 ABI：只出 `arm64-v8a`（体积极小）还是 `arm64-v8a + x86_64`（模拟器也要能装）？
4. `[待确认]` 检测参数是否需要 UI 可调（置信度阈值 / NMS 阈值 / 检测开关默认状态）？
5. `[待确认]` 是否严格不用 CameraX（`todo.md` 写的是 camera2 原生 API，默认按 camera2 实现，不做 CameraX 版本）。
6. `[待确认]` 是否需要额外功能：截图保存带框结果、检测结果日志（JSON/CSV）、语音或震动提示。

## 7. 实施结果

已按本文件完成实施，详见 `.agent/plans/task1-impl-plan.md`（交付物清单、关键决策、验证记录）。

1. 构建：`./gradlew :app:assembleDebug` 通过，产出 `app/build/outputs/apk/debug/app-debug.apk`（66.3MB，
   含 `arm64-v8a` + `x86_64`）。
2. 单元测试：6 项全部通过，真实模型 + 真实标注图端到端 IoU 0.92 / 0.92 / 0.98（三个类别），
   16:9 letterbox 反算 IoU 0.77。
3. 运行验证（camera2 预览、切换摄像头、实时检测叠加）**受阻于环境**：本机 `/dev/kvm` 不可用
   （`root:kvm 660`，当前用户不在 `kvm` 组且 `sudo` 需密码），x86_64 模拟器拒绝启动，且无真机连接。
   验证脚本 `tools/verify-on-device.sh` 已备好，待设备可用后执行。

## 8. 剩余事项

1. 运行验证（camera2 预览 / 切换摄像头 / 实时检测叠加）需要设备：可选「把当前用户加入 `kvm` 组后用 AVD 跑」
   或「连真机执行 `tools/verify-on-device.sh`」，验证通过后再把 `.agent/todo.md` 的 Task 1 标注 `[完成]`。
2. 本文件第 6 节的 `[待确认]` 项已由 `.agent/todo.md` 的「确认」小节答复（包名 / 自动验证 / ABI / 参数不做 UI 可调 / 不用 CameraX / 无额外功能）。
