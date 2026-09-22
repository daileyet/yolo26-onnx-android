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
4. 后处理：`conf >= 0.25` 过滤 + 按类 NMS(`IoU 0.45`)，最多 20 个框（`Detector` 中的常量）。
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
2. 检测参数（conf 0.25 / NMS 0.45 / 最多 20 个框）为代码常量，不提供 UI 调节。
3. Activity 锁定竖屏（`android:screenOrientation="portrait"`），旋转逻辑仍按通用公式实现。
4. 未启用 NNAPI，走 CPU（`setIntraOpNumThreads(2)`）；如需加速可加 `options.addNnapi()`，
   但要留意部分算子在 NNAPI 上回退反而更慢。
5. APK 只含 `arm64-v8a` + `x86_64` 两个 ABI（ORT 的 `libonnxruntime.so` 很大，全 ABI 会到 108MB）。
