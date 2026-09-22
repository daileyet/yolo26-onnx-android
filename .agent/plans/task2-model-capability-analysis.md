# Task2 分析 — `app/src/main/assets/yolo26_barrier.onnx` 是否支持通用物体检测

## 1. 结论

**不支持。** 它是一个只训练了 3 个道闸类别（`barrier_closed` / `barrier_open` / `barrier_raising`）的专用检测模型，
检测头的输出通道只有 `4 + 3 = 7`，物理上不可能输出 COCO 80 类等通用类别；
且实测对巴士、人、狗、街道汽车等通用目标完全无响应（最高类别分数 0.012~0.016）。

## 2. 证据一：模型契约与元数据（本机实测）

用 ONNX Runtime 直接读取 `app/src/main/assets/yolo26_barrier.onnx`：

1. `inputInfo = images: FLOAT [1, 3, 640, 640]`；`outputInfo = output0: FLOAT [1, 7, 8400]`。
   7 = `4 (cx,cy,w,h) + 3 (类别分数)` —— 类别数由权重决定，无法在推理时“换成 80 类”。
2. `customMetadata`（运行时可读，键共 15 个）：
   - `task = detect`、`head = Detect`、`imgsz = [640, 640]`、`opset = 12`、`stride = 32`、`end2end = False`
   - `names = {0: 'barrier_closed', 1: 'barrier_open', 2: 'barrier_raising'}`
   - `description = Ultralytics YOLO26n model trained on \\SZH-C-007T8...\datasets\barrier\data.yaml`
     → 训练数据只有 `datasets/barrier`（道闸数据集），没有 COCO 类别的监督信号。
3. 代码侧同源约束：`app/src/main/java/com/openthinks/onnx/example/Detector.java`
   `CLASS_NAMES` 长度 3、`NUM_CLASSES = CLASS_NAMES.length`、解码只遍历通道 `4..6`。

## 3. 证据二：通用图片实测（本机实测，同一套预处理/后处理）

用项目自身的 `Letterboxer` + `OnnxInferenceEngine` + `Detector` 跑 4 张通用图 + 1 张道闸图
（取证程序 `/tmp/t2/Probe.java`，不属于仓库）：

1. `bus.jpg`（Ultralytics 官方 COCO 示例图，含 1 辆巴士 + 4 个人）：全图最高类别分数 **0.41**，
   判为 `barrier_closed`，框覆盖画面 92% x 67% —— 这是**误报**（不是巴士/人，是“整图当成道闸”）。
2. `zidane.jpg`（官方示例图，含 2 个人）：最高类别分数 **0.0163**，conf>=0.05 也无任何输出。
3. 金毛犬照片（Wikimedia Commons）：最高类别分数 **0.0120**，无输出。
4. 旧金山街景（含多辆汽车）：最高类别分数 **0.0116**，无输出。
5. 道闸验证图 `img_0001.jpg`（对照组）：最高类别分数 **0.9612**，`barrier_closed` 正确检出。

结论：对人、巴士、狗、汽车这些通用目标，模型基本处于“静默”状态（分数接近 0），
说明它学到的是道闸形态，而不是通用物体概念。

## 4. 附带发现（现有 Demo 的一个实际问题，值得单独处理）

`bus.jpg` 在 App 旧默认阈值 `conf >= 0.25` 下会画出一个覆盖 92% 画面的 `barrier_closed` 误报框（该问题已在第 8 节修复）。
成因推测：训练集里几乎没有“非道闸场景”的负样本，模型在陌生场景下会把大片区域当成道闸。

可选缓解（按代价从低到高）：

1. 提高置信度阈值到 0.4~0.5（简单，但会略微降低正常场景召回）；
2. 在 `Detector` 增加几何过滤：框面积 > 画面 70% 或宽高比异常时丢弃（针对“整图一个大框”这类模式）；
3. 在训练集中补充负样本（非道闸场景的纯背景图）后重训（最彻底，但需要重训流程）。

## 5. 若目标是“通用物体检测”，方案对比

1. **换权重（推荐）**：用 COCO 预训练的模型（如 Ultralytics YOLO26n / YOLOv8n 的 80 类权重）导出 ONNX
   （输出为 `[1, 84, 8400]`），替换 `app/src/main/assets/` 下的模型；
   App 侧做“模型无关”改造（见第 6 节）即可复用全部现有管线（camera2、letterbox、NMS、叠加渲染都不用改）。
2. **双模型共存**：道闸专用模型 + 通用模型，UI 增加切换（会引入额外功能，与 Task 1 确认项 6「否」冲突，需你确认）。
3. **合并类别重训**：把通用类与道闸类合并成一个数据集重新训练（成本最高，需要数据与算力）。
4. **不可行做法**：直接修改现有 ONNX 的输出通道把 3 类“扩成”80 类 —— 没有对应的分类权重，只能得到随机输出。

## 6. “模型无关”改造清单（走方案 1 时需要改的点）

1. `Detector`：类别数由输出形状推导（`channels - 4`），类名改为构造参数传入；NMS 已是按类抑制，逻辑通用。
2. 新增类名读取：`session.getMetadata().getCustomMetadata().get("names")` 解析 `{0: 'xxx', ...}`。
   **已实测可读**（运行时拿到 `{0: 'barrier_closed', ...}`），解析失败时回退为 `class_0..class_N`。
3. 输入尺寸：`Letterboxer.INPUT_SIZE` 从 `session.getInputInfo()` 的输入形状推导（当前硬编码 640；COCO 模型通常也是 640，可保持默认）。
4. 阈值策略：类别变多后建议 class-wise 阈值或整体提高阈值，并叠加第 4 节的几何过滤，压制“整图一个大框”的误报。
5. 单元测试：新增“类名/通道数解析”与“COCO 模型冒烟”两组用例（后者需要一张通用图作为测试资源）。

## 7. [待确认]

1. Task 2 是否只要求“结论”（已给出），还是需要我继续实施第 6 节的**通用化改造**并换用 COCO 模型？
   若需要，请确认：允许下载通用模型权重并导出 ONNX（约 12MB，需要 `ultralytics` 或直接取现成 ONNX），
   以及是否保持单模型、单按钮的 UI 形态不变（不加模型选择入口）。
2. 第 4 节的 `bus.jpg` 误报（现有 Demo 在陌生场景会画出整图大框）是否需要我顺手加“面积/宽高比过滤 + 提高阈值”？

## 8. 修复记录（误报抑制，已实施）

### 8.1 先收集数据（12 张通用负样本 + 5 张 val 正样本）

取证程序 `/tmp/t2/Probe2.java`（不属于仓库），用项目自身的 `Letterboxer` + `OnnxInferenceEngine` + `Detector`：

1. 正样本（5 张 val 图，全部正确检出，IoU 0.88~0.98）：命中分数 **0.911~0.981**，命中框面积 **6.3%~49.1%**。
2. 负样本（12 张通用图）：最高分数 **0.410**（`bus.jpg`，整图框 92%x67% = 面积 61.7%）、
   **0.354**（室内图，面积 13.1%），其余 10 张 ≤ 0.091（猫/狗/人/街景/食物/洗衣机/自行车/建筑/山景/道路）。

### 8.2 原提议的“面积 > 70% 过滤”被数据否决

`bus.jpg` 的误报框面积只有 61.7%（未达 70%），单纯面积阈值拦不住；而正样本里最大的合法框已达 49.1%
（近景道闸，`img_0004`），两者之间没有安全间隔 → 不能用面积硬阈值。
另外宽高比也不可靠：正样本合法框的宽高比横跨 0.145（`barrier_open` 竖立杆）到 5.0（`barrier_closed` 横杆）。

### 8.3 最终规则（`Detector.java` 中的常量，均附实测依据注释）

1. `DEFAULT_CONF_THRESHOLD = 0.5`：滤掉 0.410 / 0.354 两个误报，相对正样本最低分 0.911 留 0.41 余量。
2. 大框低分过滤：面积占比 `>= 0.5` 且 `score < 0.7` → 丢弃（“整图猜测”模式），正样本不受影响。

### 8.4 复测结果

1. 负样本：修复前 2 张出现误报框 → 修复后 12 张全部 0 个框。
2. 正样本：5 张的类别、分数（0.911~0.981）、IoU（0.88~0.98）与修复前完全一致，无召回损失。
3. 新增单测 `DetectorPipelineTest.filtersLowConfidenceLargeBoxGuess`（大框低分丢弃 / 大框高分保留 /
   小框中分保留 / 边界 0.49 与 0.51）；`./gradlew :app:testDebugUnitTest` 共 **7 项全部通过**，
   `./gradlew :app:assembleDebug` 构建通过。

### 8.5 未采纳的选项

训练集补负样本重训：最彻底，但需要数据与重训流程，超出本次范围（若后续要做，建议在 `datasets/barrier` 中
补充非道闸场景的纯背景图后再训练）。
