# Task3 方案 — app 界面提供模型文件切换

## 1. 目标与范围

1. 在 App 界面上切换 `app/src/main/assets` 下的模型文件（现有两个：`yolo26_barrier.onnx`、`yolo26n.onnx`）。
2. 切换后检测立即使用新模型，类别名、类别数、阈值随模型自动适配（这是关键：两个模型的类别数与最优阈值完全不同）。
3. 不在范围内：模型下载/在线更新、模型量化、按模型存偏好设置（见第 7 节 `[待确认]`）。

## 2. 可行性（含实测数据）

模型契约实测（本机 ONNX Runtime，取证程序 `/tmp/t3/Probe80.java`，不属于仓库）：

| 项 | `yolo26_barrier.onnx` | `yolo26n.onnx` |
|---|---|---|
| 输出形状 | `[1, 7, 8400]` → nc=3 | `[1, 84, 8400]` → nc=80 |
| 类别名 | `{0:'barrier_closed',1:'barrier_open',2:'barrier_raising'}`（62 字符） | COCO 80 类（1171 字符，含带空格的 `'traffic light'`/`'hot dog'`/`'sports ball'`/`'hair drier'`/`'potted plant'`） |
| 输入 | `[1,3,640,640]` FLOAT | `[1,3,640,640]` FLOAT |
| 训练集（metadata） | 道闸数据集 `datasets/barrier/data.yaml` | `coco.yaml` |
| 体积 | 9.79 MB | 9.93 MB |

技术前提（均已实测通过）：

1. 运行时取类别数：`((TensorInfo) session.getOutputInfo().values().iterator().next().getInfo()).getShape()` → `nc = shape[1] - 4`。
2. 运行时取类名：`session.getMetadata().getCustomMetadata().get("names")`，格式 `{0: 'person', 1: 'bicycle', ...}`，
   类名可含空格 → 解析正则用 `(\d+)\s*:\s*'([^']*)'`。
3. 枚举 assets：`context.getAssets().list("")` 后过滤 `.onnx`，无需硬编码文件名（以后往 assets 丢模型即可出现在列表里）。

**必须按模型区分阈值（这是本任务最容易踩的坑，有数据）**：

1. 道闸模型：正样本命中分数 0.911~0.981、命中框面积 6.3%~49.1%；负样本（通用图）最高分 0.410、误报框面积 61.7%
   → 现配置 `conf=0.5` + 「面积≥50% 且 score<0.7 丢弃」是**对的**。
2. COCO 模型实测：
   - `bus.jpg`：`bus` 0.88 / 面积 46.3%，最大框 47.6%，无 ≥50% 候选；
   - `zidane.jpg`：`person` 0.90 / 面积 29.6%；
   - Commons 人像图：`bench` 0.75 / 面积 **68.8%**，conf≥0.25 的候选里**有 14 个面积 ≥50%**，其中 0.69/0.62 等分数低于 0.7。
     → 若沿用「面积≥50% 且 score<0.7 丢弃」，这些**正常输出会被误杀**。
   - 结论：COCO 模型用 `conf=0.25`（COCO 常规值，实测结果干净：`street2` 街景 0 候选）、**关闭大框低分规则**。

## 3. 设计

### 3.1 模型配置模型（ModelProfile）

新增纯 Java 类 `ModelProfile`（可在 JVM 单测）：

```
fields: String assetName, String displayName, int numClasses, String[] classNames,
        float confThreshold, float iouThreshold, int maxDetections, boolean bigBoxFilterEnabled
```

1. `numClasses` / `classNames` 来自运行时模型（`nc = 通道数-4`，`names` 解析），不写死。
2. `confThreshold` 默认规则：类数 ≤ 10 的专用模型取 `0.5`；其它（通用模型）取 `0.25`；
   当 assetName 命中内置表时优先用表里的值（表里含 `yolo26_barrier.onnx → 0.5 + 大框规则开`、
   `yolo26n.onnx → 0.25 + 大框规则关`），表外模型走默认规则。
3. `bigBoxFilterEnabled` 仅在专用模型（类别少、训练集单一）上开启。

### 3.2 UI 设计（两种，推荐 A）

1. **方案 A（推荐）**：顶部状态行右侧放一个 `Spinner` 显示当前模型名，底部按钮栏保持两个按钮不变。
   `Spinner` 属于 framework 控件（`android.widget.Spinner`），不引入任何新依赖。
2. 方案 B：底部按钮栏加第三个按钮「模型」，点击弹 `AlertDialog` 单选列表（文件名 + 类别数）。
3. 两者都支持的文案：切换中显示「正在加载 yolo26n.onnx …」，完成后显示
   「模型已就绪：yolo26n.onnx（80 类）」。检测框在切换瞬间清空。

### 3.3 切换时序（线程安全，参照 Task 1 崩溃教训）

1. 切换动作在**推理单线程池上串行执行**（提交一个 `Runnable`），天然与 `engine.run()` 互斥，
   避免「ORT 会话已 close、`run()` 仍在执行」的原生崩溃。
2. 顺序：暂停投递新帧（`detecting=false` 或置 busy）→ 加载新模型（`new OnnxInferenceEngine(bytes)`）→
   **成功后再 close 旧会话**（失败则保留旧模型并提示，避免"无模型可用"）→ 更新 `Detector`/`ModelProfile`/UI 文案 →
   恢复之前的检测开关状态。
3. 模型字节读取（assets，9.9MB）放在同一个后台任务里，不占 UI 线程。

### 3.4 解码/后处理改造（模型无关）

1. `OnnxInferenceEngine` 增加：`inputSize()`（从输入形状推导，当前两模型都是 640）、
   `numClasses()`、`classNames()`（解析 metadata，失败回退 `class_0..class_N`）、`close()` 保持现语义。
2. `Detector` 改为按 `nc` 解码：`nc = 输出通道-4`；类名/阈值/NMS 参数由构造参数（`ModelProfile`）传入；
   NMS 按 `nc` 个类别归组（现有实现已是按类，只需把范围从 3 改成 `nc`）。
3. 每帧成本：argmax 由 `8400×3` 变为 `8400×80`（约 67 万次比较，仍是毫秒级）；`maxDetections` 通用模型建议放宽到 30。
4. `MainActivity`：模型加载完成后用 profile 构造 `Detector`，并把模型名/类别数写入状态栏。

### 3.5 `Detector#CLASS_NAMES` 的模型驱动改造（Task 3 显式要求）

需求：`com.openthinks.onnx.example.Detector#CLASS_NAMES` 需要针对具体选择的模型更新。

### 3.5.1 现有耦合

1. `Detector.CLASS_NAMES` 是 `public static final String[]`，长度决定 `NUM_CLASSES`，解码只遍历通道 `4..6`。
2. `Detection.className()` 也读这个静态数组；`CameraFrameView`（画标签/上色）与单测都间接依赖它。
3. 因此"只把静态数组换掉"是不够的：长度（类别数）、解码通道范围、类名、阈值必须**成对**跟随模型，
   否则会出现"新类名 + 旧类别数/旧阈值"的中间态（例如 80 类模型仍只解 3 个通道）。

### 3.5.2 三个可选改法

1. **方案 B（推荐）：实例化 + 整体替换，去掉静态类名表**
   - `Detector` 的类名/类别数/阈值/过滤开关全部来自构造参数（`ModelProfile`），不再有静态 `CLASS_NAMES`；
   - `Detection` 增加 `final String className` 字段（构造时由 `Detector` 从 profile 填入），
     `Detection.className()` 不再依赖任何静态表 → `CameraFrameView` 无需改动；
   - 切换模型时**构造一个新的 `Detector`（不可变）并用 `volatile` 引用替换**，推理线程下一帧自然用新配置；
   - 好处：无全局可变状态、无"半更新"状态、单测互不污染（每个测试自己 new 一个 Detector）。
2. 方案 C：保留静态 `CLASS_NAMES`，切换时替换引用（`volatile static`）+ setter 改 `NUM_CLASSES`
   - 改动最小，但静态可变全局状态会让跨用例/跨线程出现"读到别人改过的类名"，需要额外同步；不推荐。
3. 方案 A：不做模型无关化，只把 `CLASS_NAMES` 换成所选模型的硬编码表（switch-case 两套常量）
   - 最省事，但每加一个模型就要改代码，且类别数/阈值仍会写死；与"往后往 assets 丢模型即可用"冲突。不推荐。

### 3.5.3 方案 B 的连带改动（必须一起做，否则编不过/行为不对）

1. `Detector`：构造签名 `Detector(ModelProfile profile)`；解码循环 `for (c = 0; c < profile.numClasses; c++)`；
   `NUM_ANCHORS` 与输入尺寸也从模型形状推导（当前两模型都是 `8400` / `640`，但 1280 输入的模型 anchor 数会变）。
2. `Detection`：新增类名字段（`className()` 返回它）；`Detector.iou()` 等静态工具不受影响。
3. `CameraFrameView`：不用改（仍调 `d.className()`）；如需按类别上色，用 `classId % 颜色数`（现状已如此）。
4. 单测：`DetectorPipelineTest` 里 `GroundTruth.className()` 原来读 `Detector.CLASS_NAMES` → 改为各用例显式给出
   类名数组（道闸 3 类 / COCO 取前若干类即可），新增 `ModelProfileTest` 覆盖 names 解析与表内外阈值选择。
5. `README.md` 的模型契约章节：把"类别名 = 3 个道闸类"改为"类别名来自模型 metadata `names`，随所选模型变化"。

## 4. 实现清单（逐文件）

1. 新增 `app/src/main/java/com/openthinks/onnx/example/ModelProfile.java`（内置模型表 + 默认规则 + `names` 解析静态方法）。
2. 改 `OnnxInferenceEngine.java`：暴露 `inputSize()`/`numClasses()`/`classNames()`/`numAnchors()`（全部由模型形状与 metadata 推导）。
3. 改 `Detector.java`：构造参数化（profile），删除静态 `CLASS_NAMES`，`NUM_CLASSES`/`NUM_ANCHORS` 由 profile 提供，
   解码按 `nc` 遍历；保留 `DEFAULT_*` 常量作为道闸模型的配置来源（供内置表引用）。
4. 改 `Detection.java`：新增 `className` 字段（由 `Detector` 从 profile 填入），`className()` 不再读静态表。
5. 改 `MainActivity.java`：模型列表枚举、Spinner（方案 A）或按钮+对话框（方案 B）、切换任务（推理线程串行、先加载后关闭）、
   状态栏文案、检测状态保持、`volatile Detector` 引用替换。
6. 改 `app/src/main/res/layout/activity_main.xml`、`res/values/strings.xml`（新增模型切换控件与文案）。
7. 改 `app/src/test/java/.../DetectorPipelineTest.java`（类名显式传入 + 新增 COCO 模型回归），新增 `ModelProfileTest.java`。
8. 改 `README.md`（模型切换说明 + 类别名来源 + 每模型阈值取值依据）。

## 5. 测试与验证

1. 单测（JVM）：
   - `ModelProfileTest`：解析 COCO names 串（含空格类名、`末位 79: 'toothbrush'`）与道闸 names 串；异常输入回退 `class_N`；内置表命中/未命中的阈值选择。
   - `Detector` nc=80 合成张量：正确解码 classId/score/归一化坐标、按类 NMS、`maxDetections` 上限、大框规则开关生效/关闭。
   - 真实模型回归（两条，正好覆盖 Task 2 发现的标签错位）：
     a. `yolo26_barrier.onnx` + 道闸 val 图 → `barrier_closed`；
     b. `yolo26n.onnx` + 一张通用图 → 期望类别为 COCO 类（如 `person`/`bus`）且分数 >0.5，**且不得出现 `barrier_*` 类名**。
     需要新增一张通用图 raw 资源（见 `[待确认]`）。
2. 模拟器验证：`tools/verify-on-device.sh` 扩展为「切换模型 → 开启检测 → 再切换模型 → 关闭检测」，检查
   `logcat -b crash` 无本 App、状态栏文案随模型更新、截图里框的标签不属于另一模型。
3. 构建：`./gradlew :app:assembleDebug :app:testDebugUnitTest`（APK 将增至约 76MB，两个模型都打包）。

## 6. 风险

1. **切换瞬间的会话生命周期**：必须在推理线程上串行切换（见 3.3），否则重演「会话已关、run() 仍在执行」的原生崩溃。
2. **内存峰值**：先加载后关闭会有约 1 个会话的峰值（几十 MB）。若真机内存吃紧，可改为"先关后载 + 失败则回载旧模型"。
3. **阈值配置错的代价**：把道闸模型的 `conf=0.5`+大框规则套到 COCO 模型上会明显漏检（已有数据），必须按 profile 走。
4. **类名解析**：COCO 类名含空格、`'`，用正则 `(\d+)\s*:\s*'([^']*)'`；解析不到时回退 `class_N` 而不是抛异常。
5. **APK 体积**：两个模型共 19.7MB，全量打包后约 76MB；若在意，可后续用 build flavor 只打一个（见 `[待确认]`）。

## 7. [待确认]

0. `CLASS_NAMES` 随模型更新这一点已由 `.agent/todo.md` 的 Task 3 更新明确（不再是可选项），本方案按第 3.5 节的**方案 B**
   （去掉静态类名表 → `Detector` 参数化 + `Detection` 自带类名 + 切换时整体替换实例）实施。
1. `[待确认]` UI 形式：**方案 A**（顶部 Spinner 显示当前模型，底部两按钮不变，推荐）还是方案 B（底部第三个按钮 + 单选对话框）？
2. `[待确认]` 每个模型的阈值是否按本方案内置表：`yolo26_barrier.onnx → conf 0.5 + 大框低分规则开`、`yolo26n.onnx → conf 0.25 + 大框规则关`（依据见第 2 节数据）？表外模型走「类数≤10→0.5，否则 0.25，大框规则关」。
3. `[待确认]` 是否允许新增一张**通用图**作为单测资源（raw ARGB ≈1.3MB，用于 COCO 模型回归）？从 Wikimedia Commons 取 CC 许可图，或你提供一张自有图。
4. `[待确认]` 是否需要记住上次选择的模型（`SharedPreferences`）？默认**不做**。
5. `[待确认]` 两个模型都打包（APK≈76MB）是否可接受？还是需要 build flavor 二选一（那样 UI 切换就没意义了，二选一与此任务冲突）。
6. `[待确认]` 切换模型时是否保持检测开关状态？默认**保持**（切换完成后继续检测）。

## 8. 实施记录（按推荐方案实施）

### 8.1 已实施

1. 新增 `ModelProfile`（纯 Java）：类名/类别数/候选数来自模型，内置模型表给阈值与大框规则开关，
   表外模型走默认规则；`names` 解析支持带空格类名与乱码回退。
2. `OnnxInferenceEngine` 模型无关化：从输入形状推导 `inputSize()`、输出形状推导 `numClasses()`/`numAnchors()`、
   metadata 暴露 `namesMetadata()`；输入缓冲按模型尺寸分配。
3. `Detector` 构造参数化（吃 `ModelProfile`），**删除静态 `CLASS_NAMES`**；`Detection` 自带 `className` 字段
   （`CameraFrameView` 无需改动）。
4. `Letterboxer` 支持自定义输入边长（默认 640）。
5. `MainActivity`：顶部 `Spinner` 枚举 assets 下所有 `.onnx`；切换任务在推理线程串行执行（先加载后关闭旧会话、
   `volatile` 整体替换三件套、失败保留旧模型、恢复检测开关）；推理任务带 `eng != engine` 二次校验。
6. `tools/verify-on-device.sh` 扩展为「预览 → 开启检测 → 切换摄像头 → **切换模型** → 崩溃检查」。

### 8.2 验证结果

1. `./gradlew :app:assembleDebug :app:testDebugUnitTest` → BUILD SUCCESSFUL，**16 项单测 0 失败**
   （`DetectorPipelineTest` 7 + `ModelProfileTest` 7 + `RotationMappingTest` 2）。
2. 两个模型的端到端结果：
   - 道闸模型 + val 图：`barrier_closed 0.96 IoU 0.92`、`barrier_raising 0.91 IoU 0.92`、`barrier_open 0.98 IoU 0.98`；
   - COCO 模型 + 通用图：`person 0.89 / person 0.80 / car 0.71 / backpack 0.67 / truck 0.58`，标签中无 `barrier_*`
     （Task 2 的标签错位问题被回归覆盖）。
3. 模拟器运行验证：**未完成**。原因（实测证据）：工程当前只打 `arm64-v8a`，模拟器是 x86_64 →
   `INSTALL_FAILED_NO_MATCHING_ABIS`；改用工程副本（`/tmp/t3/emu-x86`，仅改 ABI 行）产出双 ABI APK 后安装成功，
   但在这台软件渲染模拟器上 `am start -W` 首次启动耗时 24~34s（`failed to complete startup` 后重试才显示成功：
   `采集尺寸 1280x720`、`Displayed ... +34s`），按用户要求**放弃自动 UI 验证，改由用户手动验证**。

### 8.3 待用户确认/处理

1. `[待确认]` `app/build.gradle` 的 `abiFilters` 目前只有 `arm64-v8a`（用户手改），与 Task 1 确认项 3
   （`arm64-v8a + x86_64`）不一致，且导致模拟器无法安装；若要在模拟器验证需恢复该组合。
2. 用户手动验证通过后，把 `.agent/todo.md` 的 Task 3 标注 `[完成]`。
