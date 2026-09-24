# 道闸检测 Demo（Android + camera2 + ONNX Runtime）

手机端实时目标检测示例应用：**camera2 原生 API** 采集摄像头帧 → **ONNX Runtime** 跑 YOLO26 模型 → 在预览画面上叠加检测框。
包名 `com.openthinks.onnx.example`，应用名「道闸检测 Demo」，界面锁定竖屏。

## 1. 功能

1. 主界面实时显示摄像头画面，支持前/后摄像头切换。
2. 「开启检测 / 关闭检测」开关：开启后在画面上叠加检测框（类别名 + 置信度），并在右上角显示 fps 与推理耗时。
3. 顶部模型下拉框：运行时枚举 `app/src/main/assets` 下的所有 `.onnx`，选中即切换；类别名、类别数、后处理阈值随模型变化。
4. 检测日志框：滚动显示检测结果（每行两条：概要与按类别聚合的目标列表），行视图由 `ListView` 自动复用。

## 2. 模型契约（改动后处理前必读）

仓库内置两个模型（`app/src/main/assets/`）：

| 文件 | 模型 | 类别 | 输入 | 输出 |
|---|---|---|---|---|
| `yolo26_barrier.onnx` | 道闸检测（Ultralytics YOLO26n，opset 12，9.3MB） | 3（`barrier_closed/barrier_open/barrier_raising`） | `images` FLOAT `[1,3,640,640]` | `output0` FLOAT `[1,7,8400]` |
| `yolo26n.onnx` | 通用检测（COCO 80 类，9.5MB） | 80 | 同上 | `output0` FLOAT `[1,84,8400]` |

1. 输入为 **RGB + NCHW** 且必须 **`/255.0` 归一化到 0~1**：直接送 0~255 原值会输出上百个 `score=1.0` 的垃圾框。
2. 输出为 `4 + nc` 通道：通道 0..3 是**已解码的中心式 `cx,cy,w,h`**（640x640 letterbox 空间），
   其余是类别分数，**已含 Sigmoid（无 objectness、无 DFL，无需额外解码）**。
3. 类别名来自**模型自身 metadata**：`getMetadata().getCustomMetadata().get("names")`，
   形如 `{0: 'barrier_closed', 1: 'barrier_open', 2: 'barrier_raising'}`（COCO 模型为 80 项）。
4. 非方形输入必须 **letterbox**（等比缩放 + 居中补齐），并把框按 `scale / padX / padY` 反算回原帧，否则框整体偏移。
5. 后处理：`conf` 阈值过滤 → 按类 NMS → 上限个数 → 可选的「大框低分」抑制；阈值按模型配置（见第 7 节）。

## 3. 工程结构

```
.
├── app/
│   ├── build.gradle                     # AGP 8.9.1 / compileSdk 34 / ORT 1.26.0 / 产物重命名
│   └── src/
│       ├── main/
│       │   ├── AndroidManifest.xml      # CAMERA 权限、竖屏锁定
│       │   ├── assets/yolo26_barrier.onnx   # 道闸 3 类
│       │   ├── assets/yolo26n.onnx          # COCO 80 类
│       │   ├── res/layout/activity_main.xml        # 垂直三段：预览 / 检测日志 / 按钮栏
│       │   ├── res/layout/item_model_spinner.xml   # 模型下拉框行
│       │   ├── res/layout/item_detection_log.xml   # 日志行（等宽小字号，可换行）
│       │   ├── res/values/strings.xml
│       │   └── java/com/openthinks/onnx/example/
│       │       ├── MainActivity.java           # 权限、检测开关、摄像头/模型切换、帧分发、日志追加
│       │       ├── CameraController.java       # camera2 封装（ImageReader + 会话 + 旋转角缓存）
│       │       ├── CameraImageConverter.java   # YUV_420_888 -> 摆正后的 ARGB 整帧
│       │       ├── CameraFrameView.java        # 预览绘制（等比完整显示）+ 检测框叠绘 + fps/耗时角标
│       │       ├── RotationMapping.java        # 旋转坐标映射（纯 Java，可单测）
│       │       ├── Letterboxer.java            # ARGB -> NCHW float 张量（尺寸随模型，纯 Java，可单测）
│       │       ├── OnnxInferenceEngine.java    # ORT 会话与推理（assets 加载 + 复用输入缓冲）
│       │       ├── ModelProfile.java           # 模型配置：类名/类别数/阈值/输入尺寸（不可变）
│       │       ├── Detector.java               # 解码 + 按类 NMS + 几何过滤（纯 Java，可单测）
│       │       ├── Detection.java              # 检测结果（归一化中心式坐标 + 类名）
│       │       ├── LabelPalette.java           # 类别配色（黄金角色相，纯 Java，可单测）
│       │       └── DetectionLogFormatter.java  # 日志两行文本 + 类别聚合（纯 Java，可单测）
│       └── test/                              # JVM 单元测试（真实模型 + 真实标注图）
│           ├── java/.../DetectorPipelineTest.java
│           ├── java/.../ModelProfileTest.java
│           ├── java/.../RotationMappingTest.java
│           ├── java/.../LabelPaletteTest.java
│           ├── java/.../DetectionLogFormatterTest.java
│           └── resources/val/*.raw|*.txt
├── doc/                                 # 参考文档（ONNX Runtime 用法、模拟器虚拟摄像头等）
├── tools/verify-on-device.sh            # 真机/模拟器一键验证脚本
├── .github/workflows/android.yml        # CI：构建 release；打 tag 时发布 APK
└── .agent/plans/                        # 开发过程文档（方案推导与实测记录，不随发布）
```

## 4. 构建与安装

```bash
cd "$(git rev-parse --show-toplevel)"   # 或在克隆下来的仓库根目录执行
./gradlew :app:assembleDebug          # -> app/build/outputs/apk/debug/yolo26-onnx-example.apk
./gradlew :app:assembleRelease        # -> app/build/outputs/apk/release/yolo26-onnx-example.apk
./gradlew :app:testDebugUnitTest      # JVM 单元测试（含真实模型推理）
```

环境要求：

1. JDK 17+（本工程 `compileOptions` 用 17；本机用 JDK 21 构建，CI 用 17）。
2. Gradle 9.0.0（`gradle/wrapper` 指向 `gradle-9.0.0-bin.zip`，缓存后可离线）。
3. Android SDK：`local.properties` 里的 `sdk.dir`，需要 `platforms/android-34`、`build-tools/34.0.0`。
4. 依赖仓库：`maven.aliyun.com` 的 public / google / gradle-plugin 镜像（见 `build.gradle`）。
   `public` 不代理 `com.android.tools.build`（404），因此 **`google` 镜像必须保留**。

产物与 ABI：

1. 两个变体产物名统一为 **`yolo26-onnx-example.apk`**（在 `app/build.gradle` 用
   `android.applicationVariants.all { outputs.all { outputFileName = ... } }` 设置；
   AGP 8.9 的新变体 API 没有文件名入口，只能用该 legacy API，AGP 9 移除后需迁移）。
2. 当前 `abiFilters` 只打 **`arm64-v8a`**（APK 约 43MB）。ORT 的 `libonnxruntime.so` 很大：
   放开 `x86_64` 约 78MB，不裁 ABI 会到 108MB。
3. `release` **未配置签名**：直接用 `adb install` 会报 `INSTALL_PARSE_FAILED_NO_CERTIFICATES`，
   发布/安装前需自行 `apksigner` 签名，或改用 debug 包做功能验证。
4. `build.gradle` 里也**不用写死本机路径**；CI（`.github/workflows/android.yml`）跑 `assembleRelease`，
   打 `v*.*.*` tag 时把 `release/*.apk` 作为 Release 附件发布。

```bash
adb install -r -t app/build/outputs/apk/debug/yolo26-onnx-example.apk
```

## 5. 架构与线程模型

```
camera2 ImageReader(YUV_420_888)
        │ 相机线程(onImageAvailable)
        ▼
CameraImageConverter ──► ARGB 整帧(摆正)  ──► CameraFrameView.updateFrame() ──► 预览
        │
        └─(检测开启且推理空闲时)─► Letterboxer ──► FloatBuffer(1x3xHxW)
                                                      │
                                            推理线程(单线程) ──► OnnxInferenceEngine.run()
                                                      │                  │
                                                      ▼                  ▼
                                            Detector.detect() ──► CameraFrameView.setDetections()
                                                      │
                                                      └─(UI 线程 Handler.post)─► 检测日志框(DetectionLogFormatter)
```

1. **只有一路输出流**（`ImageReader`）：预览与检测共用同一份「摆正后的 ARGB 整帧」，坐标系唯一，
   检测框天然与预览对齐（避免 TextureView transform 与检测坐标不一致造成的错位）。
2. **丢帧策略**：`inferenceBusy` 标志 + 单线程池；推理忙时直接丢弃当前帧、不排队，避免延迟累积。
3. **输入缓冲复用**：`Letterboxer.fill()` 只在抢到 `inferenceBusy` 时写入，保证推理线程读取期间缓冲不被改写。
4. **相机拆除与帧处理互斥**：`CameraController` 用 `frameLock` + `tearingDown`：
   帧处理全程持锁（`acquireLatestImage` 捕获异常、处理完在锁内 `image.close()`）；
   `stop()` 先 `stopRepeating()` 再等在处理的帧结束，最后才 `close()` 会话/设备/Reader，并先摘掉监听器。
   回调入口判断 `tearingDown || reader != imageReader`，丢弃停相机后的迟到回调（避免 `Image is already closed`）。
5. **位图不 `recycle()`**：旧预览位图直接替换引用交给 GC —— 硬件加速下 DisplayList 可能仍被 RenderThread 持有。
6. **旋转角缓存**：`sensorOrientation` 与 `LENS_FACING` 在启动时缓存（每帧调 `getCameraCharacteristics()` 会触发
   binder 长耗时）；映射逻辑在 `RotationMapping`，有双射与方向语义单测。
7. **生命周期**：`onResume` 开相机 → `onPause` 关相机并释放 `ImageReader` → `onDestroy` 先 `shutdown()` 推理池
   并等待结束，再释放相机，最后 `close()` ORT 会话。

## 6. 界面说明

### 6.1 布局（垂直三段）

1. **预览区** `FrameLayout`（`layout_weight=3`，约占屏高 60%）：`CameraFrameView` + 状态文字 + 模型下拉框。
   预览按**等比完整显示**绘制：`s = min(max(W/cw, H/ch), min(W/cw, H/ch) × MAX_CROP_FACTOR)`，
   `MAX_CROP_FACTOR = 1.0` 表示**不裁切**（画面不足的一边留黑边）；调大该值才会裁切换铺满。
   画面与检测框共用同一 `contentRect` 映射，**框与画面始终对齐**。
   例：1080x2340 竖屏、预览区 1080x1404、帧 720x1280 → 缩放 1.097，绘制 790x1404，左右黑边各 145px，画面完整。
2. **检测日志区** `ListView`（`layout_weight=2`）：可上下滚动，行视图自动复用。
3. **底部按钮栏**：切换摄像头 / 检测开关（验证脚本按文本查找控件，改文案需同步脚本）。

### 6.2 检测日志规则

1. 行格式（两行，短屏也能显示完整）：

   ```
   HH:mm:ss.SSS | <模型名> | 推理 Xms | N 个目标
   person 0.89×3, car 0.71, truck 0.58, backpack 0.67  (共 4 类/7 个)
   ```

   第二行按类别聚合：同类只保留最高分 + 数量 `×N`，按最高分降序，最多列 6 类（超出显示 `…`），
   行视图等宽小字号、`maxLines=3` 允许换行。
2. 写入条件：**有目标** + **内容与上一条不同** + 距上一条 **≥200ms**；无目标不写、静止画面不重复刷屏。
3. 最多保留 **300 行**（超出删除最旧），新增后自动滚到底；日志头右侧显示「当前 N 个目标」。
4. 追加在 UI 线程完成（复用推理结果已有的 `Handler.post` 通道），不新增线程。

### 6.3 类别配色

```
hue = (classId × 137.508°) mod 360°        // 黄金角，LabelPalette.hueFor(classId)
color = Color.HSVToColor({hue, 0.90, 0.95})
```

同一 `classId` 的颜色跨帧恒定；80 类时两两最小色相间隔约 2.94°。框线用生成色，标签底色同色 + 白字 + 黑阴影。

## 7. 模型切换与配置（`ModelProfile`）

顶部下拉框列出 `assets` 下全部 `.onnx`（默认优先 `yolo26_barrier.onnx`，**启动时即加载**，不依赖下拉框回调）。
选中即切换：暂停投帧 → 加载新模型 → 成功后再关闭旧会话 → 一次性替换 `engine/letterboxer/detector` → 恢复检测开关；
失败时保留旧模型并提示。状态栏文案：`模型已就绪：<显示名>（N 类, conf X）`。

配置表（`ModelProfile.TABLE`，随模型变化的一切后处理参数）：

| 模型 | 显示名 | 类别数 | conf | IoU | 最多框 | 大框低分规则 |
|---|---|---|---|---|---|---|
| `yolo26_barrier.onnx` | 道闸模型(3类) | 3 | 0.50 | 0.45 | 20 | 开 |
| `yolo26n.onnx` | 通用模型(COCO 80类) | 80 | 0.25 | 0.45 | 30 | 关 |

1. 类别数 / 候选数 / 输入尺寸都从模型形状推导（`nc = 输出通道-4`，`anchors = 输出最后一维`，输入边长为 640）。
2. 类名解析失败时回退 `class_N`；配置对象不可变，切换时整体替换（避免「新类名 + 旧阈值」的中间态）。
3. 表外模型走默认规则：类别数 ≤10 → `conf 0.5`，否则 `conf 0.25`，且**不启用**大框低分规则
   （该启发式只对实测过的模型开启）。
4. 新增模型：把 `.onnx` 放进 `app/src/main/assets/` 重新构建即可；需要特殊阈值时在 `ModelProfile.TABLE` 加一行。
5. 线程安全：切换任务提交到**推理用的同一个单线程池**（与 `run()` 天然串行），并在推理任务里用
   `eng != engine` 二次校验丢弃上一代模型的排队结果。

## 8. 阈值与误报抑制依据

道闸模型在陌生场景会把大片区域猜成道闸。以 5 张真实标注图作正样本、12 张通用图作负样本实测：

1. 正样本（全部正确检出，IoU 0.88~0.98）：命中分数 **0.911~0.981**，命中框面积 **6.3%~49.1%**。
2. 负样本（巴士/人/猫/狗/街景/室内/食物/洗衣机/自行车/建筑/山景/道路）：最高分 **0.410**（整图框，面积 61.7%）、
   次高 **0.354**（面积 13.1%），其余 10 张 ≤ 0.091。

据此得出两条规则（阈值都在 `ModelProfile`/`Detector` 常量里）：

1. **conf 阈值 0.5**：滤掉上述两个误报，同时相对正样本最低分 0.911 留有余量。
2. **大框低分抑制**：面积占比 ≥50% 且 `score < 0.7` 的框丢弃（正样本最大命中框 49.1%、分数 ≥0.911，不受影响）。
   注意该规则**不能跨模型复用**：套到 COCO 上会误杀正常目标（`bench 0.75`、面积 68.8%）。

## 9. 单元测试

`./gradlew :app:testDebugUnitTest`（当前 23 项，0 失败）：

1. `DetectorPipelineTest.valImagesDetectionsMatchGroundTruth`：真实道闸模型 + 3 张真实标注图（三个类别各一张），
   校验类别与 IoU（0.92 / 0.92 / 0.98，置信度 0.91~0.98）。
2. `DetectorPipelineTest.letterboxHandlesNonSquareFrame`：把图裁成 16:9 再跑，验证 letterbox 的 scale/pad 反算（IoU 0.77）。
3. `DetectorPipelineTest.decodesBoxesAndSuppressesDuplicates`：构造张量验证置信度过滤与同类 NMS。
4. `DetectorPipelineTest.letterboxGeometryAndNchwLayout`：用红/绿像素标定 letterbox 几何、NCHW 布局、RGB 顺序与 pad 取值。
5. `DetectorPipelineTest.cocoModelUsesCocoClassNames` / `barrierModelNeverOutputsCocoNames`：两个模型互相反向对照，
   检出的类名必须来自各自模型（COCO 图上是 `person/car/...`，道闸模型上必须是 `barrier_*`）。
6. `DetectorPipelineTest.filtersLowConfidenceLargeBoxGuess`：大框低分规则的四种边界（大框低分丢弃 / 大框高分保留 /
   小框中分保留 / 阈值 0.49 与 0.51）。
7. `ModelProfileTest`：metadata `names` 解析（含带空格类名、编号不连续、乱码回退）、内置表的阈值选择、表外模型默认规则。
8. `RotationMappingTest`：旋转映射的双射性与 90/180/270 方向语义。
9. `LabelPaletteTest`：同一 classId 色相恒定、落在 `[0,360)`、80 类两两最小色相间隔 >2°。
10. `DetectionLogFormatterTest`：日志两行格式与类别聚合（同类合并、按最高分降序、超 6 类截断 + 汇总），
    并用 `estimatedColumns` 锁住行宽。

测试直接加载 `app/src/main/assets/` 下的两个模型（即发布用的同一份文件），因此模型缺失或类名解析异常会立刻失败。
**改模型资产后请加 `--rerun`**（Gradle 常报 `UP-TO-DATE` 跳过测试）。

测试图：`app/src/test/resources/val/`。格式为 `[int width][int height][width*height 个 int ARGB]`（大端）——
Android 单元测试的引导类路径是 `android.jar`，没有 `java.awt` / `javax.imageio`，所以不用 JPG。
其中通用图 `cc_street.raw` 来自 Wikimedia Commons《Nong'an Street intersection with pedestrians 20190517》
（作者 Adam Jones，许可 CC BY-SA 2.0，已缩放到 640x480 后转 raw）；其余三张来自道闸数据集 val 目录。

导出脚本（在仓库根目录执行，替换第一行为你的数据集路径）：

```bash
python3 - <<'EOF'
import numpy as np, struct
from PIL import Image
src='/path/to/datasets/barrier/images/val/'
dst='app/src/test/resources/val/'
for name in ['img_0001','img_0004','img_0008']:
    im=Image.open(src+name+'.jpg').convert('RGB'); w,h=im.size
    a=np.asarray(im).astype(np.uint32)
    argb=(np.uint32(0xFF000000)|(a[:,:,0]<<16)|(a[:,:,1]<<8)|a[:,:,2]).astype('>u4')
    with open(dst+name+'.raw','wb') as f:
        f.write(struct.pack('>ii', w, h)); f.write(argb.tobytes())
EOF
```

## 10. 在设备上验证

```bash
tools/verify-on-device.sh                 # 用默认 APK 路径
tools/verify-on-device.sh <apk 路径>       # 指定 APK（例如模拟器用的双 ABI 包）
```

脚本会：安装 APK → 授予 CAMERA 权限 → 启动 → 截图 → 点击「开启检测」→ 截图 → 切换摄像头 → 切换模型 →
检查 `logcat -b crash`，并输出截图路径。脚本不写死本机路径：SDK 取 `ANDROID_SDK_ROOT` / `ANDROID_HOME`，
都没有时读仓库 `local.properties` 的 `sdk.dir`。

手工验证要点：

1. 预览正常出图（不是全黑、不花屏、方向正确，且**画面完整不被裁切**）；
2. 「切换摄像头」后画面切到另一路且不黑屏；
3. 「开启检测」后出现检测框 + 类别名 + 置信度，右上角显示 fps 与推理耗时；关闭后框消失；
4. 启动后状态栏即出现「模型已就绪：道闸模型(3类)（3 类, conf 0.5）」，无需切走再切回；
5. 切到「通用模型(COCO 80类)」后标签变成 `person/car/...`，不同类别颜色可区分；
6. 日志区出现检测行并随画面滚动，静止画面不刷屏，可上下滚动，最多 300 行。

模拟器：需要 KVM 加速（`sudo gpasswd -a $USER kvm` 后重新登录，或临时 `sudo setfacl -m u:$USER:rw /dev/kvm`）。
除内置虚拟场景外，也可以用 `v4l2loopback` + FFmpeg 把自定义图片/视频推成模拟器摄像头，做法见 `doc/` 下两份文档。
模拟器是软件渲染：首次启动可能 20~30s，建议先 `adb shell cmd package compile -m speed -f com.openthinks.onnx.example`。

## 11. 已知限制

1. 前摄不做镜像（预览与检测输入都是同一份未镜像的正立画面）；如需自拍式镜像，要同步镜像预览与检测框，
   或对模型输入做水平翻转。
2. 检测参数（conf / NMS IoU / 最多框数 / 大框低分规则）是代码里的模型配置常量，不提供 UI 调节。
3. Activity 锁定竖屏；旋转映射仍按通用公式实现。
4. 推理走 CPU（`setIntraOpNumThreads(2)`），未启用 NNAPI —— 部分算子在 NNAPI 上回退反而更慢。
5. APK 当前只含 `arm64-v8a`；x86_64 模拟器需在 `abiFilters` 里放开该 ABI（或临时副本构建）。
6. `release` 产物未签名（文件名不带 `-unsigned` 提示），安装前需自行签名。
7. 风险模型只训练了道闸场景，通用场景请切换到 COCO 模型；两个模型的阈值规则不通用（见第 8 节）。

## 12. 模型资产注意事项

1. `yolo26_barrier.onnx` 的 metadata `description` 为中性文本
   （`Ultralytics YOLO26n model trained on datasets/barrier/data.yaml`）。
   **不要写回训练机的内部主机名、用户目录或工程路径** —— 该字符串会随 assets 打进每个 APK。
2. 改二进制模型资产（如替换模型、改 metadata）时：
   - ONNX 是 protobuf，`metadata_props` 条目有**两层长度前缀**
     （`0x72 <varint entry_len>` 包住 `0x0A <varint key_len> key` + `0x12 <varint val_len> value`）；
     只改内层长度会破坏文件（ORT 报 `ORT_INVALID_PROTOBUF`），两层必须一起重写，
     长度变化后 varint 字节数也可能变，需重新编码而不是原地改字节。
   - 改完必须**用 ORT 真加载验证**：能建会话、`customMetadata` 键集合与 `names` 正确、跑一次推理输出形状不变；
     再 `assembleDebug` 后从 APK 里解出 `assets/` 复核一次。
   - 单测虽然覆盖这份资产，但记得用 `--rerun` 确认真的重跑（见第 9 节）。
