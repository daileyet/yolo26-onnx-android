# Task4 方案 — 界面优化三点（默认模型加载 / 预览高度 + 检测日志框 / 类别配色）

## 1. 目标

1. 修掉「启动时默认选中项不加载模型，必须切走再切回」的问题。
2. 预览与检测框不再铺满整屏（高度受限），并新增一个可滚动的**检测结果日志框**（行视图复用）。
3. 修掉 `CameraFrameView#BOX_COLORS` 只有 3 色、切到 COCO 80 类后颜色重复的问题。

## 2. 问题 1：默认模型不加载

### 2.1 根因（代码级）

`app/src/main/java/com/openthinks/onnx/example/MainActivity.java` 的 `setupModelSpinner()`：

```java
modelSpinner.setSelection(defaultIndex, false);          // 第 156 行：先设选中
modelSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() { ... });  // 第 157 行：后注册
```

`Spinner.setSelection()` 在**首次布局之前**被调用时，只是记录了选中位置并 `requestLayout()`；
`AdapterView` 在第一次布局时若判定选中项相对「上次布局的快照」没有变化，就**不会触发** `onItemSelected`。
用户看到的现象（切到另一个模型再切回来才加载）与此完全一致：只有真正发生选中变化时才回调。

### 2.2 修复（不依赖 Spinner 回调时序）

1. 先注册监听，再 `setSelection`；
2. **另外显式触发一次初始加载**：`switchModel(modelAssets[defaultIndex])`（放在 `onCreate` 末尾或 `setupModelSpinner()` 内），
   不再把「首次加载」寄托在 Spinner 回调上；
3. 靠已有的守卫避免重复加载：`switchModel()` 里的 `assetName.equals(currentAsset) && engine != null` 与 `modelLoading`
   两个判断已能覆盖「显式调用 + Spinner 回调同时到达」的情况（回调先到 → 加载完成 → 显式调用被守卫拦；显式调用先到 → 回调被 modelLoading 拦）。

## 3. 问题 2：预览高度受限 + 检测日志框

### 3.1 布局结构（改为垂直三段）

```
LinearLayout(vertical, match_parent)
├─ FrameLayout  预览容器：layout_height=0dp, layout_weight=3
│   ├─ CameraFrameView（match_parent，内部仍是等比居中绘制，不会变形）
│   ├─ TextView status_text（top|start）
│   └─ Spinner model_spinner（top|end）
├─ LinearLayout  日志头（可选）：TextView「检测日志」+ TextView 计数
├─ ListView detection_log  日志区：layout_height=0dp, layout_weight=2
└─ LinearLayout  底部按钮栏（切换摄像头 / 检测开关，位置不变）
```

1. 预览高度策略（默认）：权重 3:2 → 预览约占屏高 60%，底部日志占 40%，按钮栏固定高度。
2. `CameraFrameView` 内部已经是「等比缩放 + 居中 + 检测框按同一 `contentRect` 映射」，所以高度变小只是画面变小、
   留黑边，**框不会错位**（无需改绘制逻辑）。
3. 预览位图仍是相机分辨率（1280x720），只是被缩放到受限区域绘制（不改转换分辨率，与「预览优化不做」的决定一致）。

### 3.2 日志框选型

1. **方案 A（推荐）：framework 的 `ListView` + `ArrayAdapter`** —— 零新依赖，`convertView` 天然复用行视图，
   `notifyDataSetChanged()` + `setSelection(count-1)` 实现自动滚到底部。
2. 方案 B：`RecyclerView`（`androidx.recyclerview:recyclerview`）—— 行复用更规范、可加 `DiffUtil`，
   但要新增 AndroidX 依赖（工程目前零 AndroidX 依赖库）。
3. 两者都满足「滚动 + 行复用」；默认按方案 A 实施，若你要求 RecyclerView 我再加依赖（见第 8 节）。

### 3.3 日志数据与写入策略

1. 数据：`ArrayList<String>`，**上限 300 行**（超出删除最旧），避免长时间运行内存增长。
2. 写入节流：最小间隔 200ms **且** 内容与上一行不同才追加（避免每帧刷屏、避免日志把 UI 线程拖慢）。
3. 内容格式（一行一条）：

```
17:52:03.512 | 道闸模型(3类)      | 推理 12ms | 1 个目标: barrier_closed 0.96
17:52:03.845 | 通用模型(COCO 80类) | 推理 28ms | 4 个目标: person 0.89, person 0.80, car 0.71, truck 0.58
```

   目标超过 5 个时只列前 5 个并加 `…(共 N)`（避免行过长）。
4. 线程：推理线程已有 `uiHandler.post(...)` 回 UI 线程的通道，日志追加就在那里做（UI 线程），不新增线程。
5. 自动滚底：默认追加后滚到最新；用户手动滚动时不强制打断（可选开关见第 8 节）。

## 4. 问题 3：类别配色（BOX_COLORS 只有 3 色）

### 4.1 现状

`CameraFrameView.BOX_COLORS = {红, 蓝, 绿}`，取色 `BOX_COLORS[classId % 3]` → COCO 80 类里颜色大量重复
（classId 相差 3 就撞色），相邻类别也难以区分。

### 4.2 方案：按 classId 生成稳定色相（黄金角）

1. `hue = (classId * 137.508°) mod 360°`（黄金角保证任意类别数下相邻 classId 的色相间隔最大且分布均匀），
   饱和度/明度固定（例如 `s=0.9, v=0.95`）→ `Color.HSVToColor(...)`。
2. **同一 classId 的颜色恒定**（不随帧变化），便于跨帧追踪同一类目标；80 类时两两色相最小间隔约 4.5°（可分辨度可接受）。
3. 为保证可读性：框线用生成色；标签底色用同一生成色、文字白色并保留现有黑色阴影（`textPaint.setShadowLayer`）。
4. 抽取为**纯 Java 可单测**的类 `LabelPalette`：

```java
public final class LabelPalette {
    public static final float GOLDEN_ANGLE = 137.508f;
    public static float hueFor(int classId) { return (classId * GOLDEN_ANGLE) % 360f; }   // 纯数学，可单测
}
```

   `CameraFrameView` 里 `Color.HSVToColor(new float[]{LabelPalette.hueFor(classId), 0.9f, 0.95f})`。

## 5. 实现清单（逐文件）

1. `MainActivity.java`：
   - `setupModelSpinner()`：注册监听 → `setSelection` → 显式触发初始加载（问题 1）；
   - 新增日志区相关：`ListView`/`ArrayAdapter` 初始化、`appendDetectionLog(...)`（节流 + 上限 + 自动滚底）、
     `onDestroy` 清理；
   - 推理结果回调里调用 `appendDetectionLog(...)`（复用现有 `uiHandler.post`）。
2. `CameraFrameView.java`：改用 `LabelPalette.hueFor(classId)` 生成颜色，删除 `BOX_COLORS`。
3. 新增 `LabelPalette.java`（纯 Java）。
4. `res/layout/activity_main.xml`：改为垂直三段结构（预览容器带权重、日志 `ListView`、底部按钮栏）。
5. `res/values/strings.xml`：日志区标题、空日志占位文案等。
6. 新增 `res/layout/item_detection_log.xml`（日志行样式，等宽小字号、单行省略）。
7. 测试：新增 `LabelPaletteTest`（色相单调不重复、同 classId 恒定、0~79 范围内两两最小间隔 > 0），
   其余 UI 行为靠设备验证。
8. `README.md`：界面结构、日志框说明、配色规则；`.agent/plans/task4-impl-plan.md`（实施记录，按需）。

## 6. 测试与验证

1. 单测：`LabelPaletteTest`；既有 16 项测试保持通过。
2. 设备/模拟器验证：
   - 冷启动后**无需任何操作**状态栏应出现「模型已就绪：道闸模型(3类)…」（问题 1 的回归点）；
   - 切换模型后日志区开始出现检测行，行内类名随模型变化（道闸 → `barrier_*`，COCO → `person/car/...`）；
   - 预览区域不再铺满屏幕高度，日志区可上下滚动、行视图复用（快速增减目标不出现内存/卡顿异常）；
   - 切到 COCO 后不同类别框颜色可区分。
3. `tools/verify-on-device.sh` 仍可用（按钮按文本查找，位置变化不影响）；可扩展为「检查状态栏文案 + 日志行数」。

## 7. 风险

1. 日志每帧刷新会加重 UI 线程负担（这台模拟器上预览本就吃 CPU）→ 已用「200ms + 内容变化」双条件节流与 300 行上限约束。
2. 预览高度变小后，手指遮挡/触控区域变小，无功能影响（界面无手势交互）。
3. 深色/浅色背景下的标签可读性：白色文字 + 黑色阴影 + 高饱和底色，必要时把明度降到 0.9。
4. `ListView` 自动滚底与用户手动滚动会互相打断 → 默认始终滚底，如需「暂停自动滚动」再加开关。

## 8. 决策记录（用户选择「按推荐实施」）

1. 日志框选型：**方案 A `ListView`**（零依赖，行视图自动复用）。
2. 预览高度：**权重 3:2**（预览约占屏高 60%）。
3. 日志格式与节流：按第 3.3 节（`时间 | 模型 | 推理Xms | N 个目标: ...`，最多列 5 个）；**无目标不写**。
4. 日志上限 **300 行**；**不加**「清空」按钮。
5. 自动滚底**始终生效**，不加「暂停自动滚动」入口。

以下为原始 [待确认] 列表（已按上述决定实施）：

1. `[待确认]` 日志框选型：**方案 A ListView（零依赖，推荐）** 还是方案 B RecyclerView（需新增 `androidx.recyclerview` 依赖）？
2. `[待确认]` 预览高度策略：权重 3:2（推荐，最省事）还是固定按相机比例（如 4:3 或 16:9，需要按屏幕宽度算高度）？
3. `[待确认]` 日志内容与节流：按第 3.3 节的格式（时间 | 模型 | 推理耗时 | 目标列表）与「200ms + 内容变化」节流是否合适？
   无目标时是**不写**（推荐，避免刷屏）还是按固定间隔写一行「无目标」？
4. `[待确认]` 日志上限 300 行是否合适？是否需要在日志头加一个「清空」按钮（默认不加，避免超范围）？
5. `[待确认]` 自动滚底是否始终生效（推荐），还是提供一个「暂停自动滚动」的入口？

## 9. 实施记录（按推荐方案实施）

### 9.1 已实施

1. 问题 1：`setupModelSpinner()` 改为「先注册监听 → 再 setSelection → 显式 `switchModel(modelAssets[defaultIndex])`」，
   并靠 `switchModel` 已有的 `currentAsset/modelLoading` 守卫去重；`README.md` 第 11.3 节记录了根因。
2. 问题 2：`activity_main.xml` 改为垂直三段（预览 `weight=3` / 日志 `weight=2` / 底部按钮栏），
   新增 `ListView detection_log`（+ 日志头与计数）与 `item_detection_log.xml`；
   `MainActivity` 新增 `appendDetectionLog()`/`buildLogBody()`/`resetLogState()`，
   规则为「有目标 + 内容变化 + ≥200ms」、最多 300 行、自动滚到底；切换模型/关闭检测/停相机时重置去重状态。
3. 问题 3：新增 `LabelPalette`（黄金角色相，纯 Java、可单测），`CameraFrameView` 删除 `BOX_COLORS`
   并改用 `Color.HSVToColor(hueFor(classId), 0.90, 0.95)`。

### 9.2 验证结果

1. `./gradlew :app:assembleDebug :app:testDebugUnitTest` → BUILD SUCCESSFUL，**18 项单测 0 失败**
   （`DetectorPipelineTest` 7 + `ModelProfileTest` 7 + `RotationMappingTest` 2 + `LabelPaletteTest` 2）；
   `LabelPaletteTest` 输出的 80 类最小色相间隔 = 2.94°。
2. 运行验证：**未做**。本机模拟器已关闭（`adb devices` 为空），且工程只打 `arm64-v8a`（x86_64 需用工程副本构建）。
   已把当前源码同步到 `/tmp/t3/emu-x86` 并构建出可安装的 x86_64 APK
   （`/tmp/t3/emu-x86/app/build/outputs/apk/debug/app-debug.apk`，时间戳晚于源码、dex 内含新代码），
   模拟器拉起后可直接安装验证；运行验证由用户手动完成。

## 10. 更新点分析（2026-09 追加）

### 10.1 预览宽度铺满屏幕（更新点 1）

**可以铺满，但需要选择「裁切」而不是「拉伸」，并给裁切设上限。**

现状（`CameraFrameView.onDraw`）：`s = min(W/cw, H/ch)` + 居中偏移 → 等比完整显示，因此会留黑边。
以竖屏手机为例（屏 1080x2340，预览区 1080x1404，帧 720x1280）：

| 屏幕 | 预览区 | 当前 fit 缩放 | 绘制尺寸 | 左右黑边 | 改 fill 后（按宽度铺满） |
|---|---|---|---|---|---|
| 1080x2340 | 1080x1404 | 1.097 | 790x1404 | 各 145px（宽度 27% 浪费） | 1080x1920，黑边 0，垂直裁 516px（帧高 20%） |
| 1440x3120 | 1440x1872 | 1.462 | 1053x1872 | 各 194px | 1440x2560，黑边 0，垂直裁 688px（27%） |
| 2560x1600（平板） | 2560x960 | 0.750 | 540x960 | 各 1010px | 要 3.556 倍缩放，垂直裁 3591px（帧高 78%，几乎只剩中间一条） |

方案对比：

1. **方案 A（推荐）：按宽度铺满 + 垂直居中裁切，并设裁切上限**
   - `fillScale = max(W/cw, H/ch)`；再限制 `scale = min(fillScale, fitScale × MAX_CROP_FACTOR)`，
     建议 `MAX_CROP_FACTOR = 1.4`（裁切不超过帧高的 ~30%）；
   - 达到上限时允许保留少量黑边（避免平板上把画面裁到只剩一条）；
   - 框映射沿用同一个 `contentRect`（可为负/越界，超出 View 的部分由 Canvas 自动裁掉），**框与画面依旧对齐**。
2. 方案 B：保持等比、把预览区高度按帧比例设置（`高度 = 屏宽 × ch/cw`）→ 竖屏下高度会超过屏高，
   倒退成「预览占满整屏」，与 Task 4 原始要求冲突。不推荐。
3. 方案 C：非等比拉伸铺满 → 画面与人/物比例失真。不推荐。

### 10.2 日志行显示不完整（更新点 2）

现状：`item_detection_log.xml` 是 `singleLine + ellipsize=end`，而一行实际字符宽度远超窄屏可显示列数
（估算：11sp 等宽、1080p 竖屏约 **50~56 列**）：

| 行内容 | 估算列数 | 结果 |
|---|---|---|
| 单模型 1 目标（道闸） | 72 | 被省略 |
| COCO 4 目标 | 105 | 被省略 |
| COCO 5 目标 + 截断提示 | 131 | 被省略 |
| 聚合压缩后（COCO 7 目标） | 89 | 仍被省略 |
| 两行结构的 head 行 | 57 | 基本可显示 |
| 两行结构的目标行（聚合） | 71 | 换行后可完整显示 |

方案（组合使用，推荐 1+2，可选 3）：

1. **两行结构**：head 行 `HH:mm:ss.SSS | 模型 | 推理Xms | N 个目标`，目标行只放目标列表；
   行样式去掉 `singleLine`，改 `maxLines=3 + ellipsize=end`（允许换行，90% 场景可完整显示）。
2. **按类别聚合压缩目标行**：`person 0.89×3, car 0.71, truck 0.58, backpack 0.67`（同类只列最高分与数量），
   可把 105 列压到 ~60 列；再按可用宽度限制最多列出的类别数（如 6 类）+ `…(共 N 类/N 个)`。
3. 可选（需确认）：点击某行弹出对话框显示完整内容（目标全量 + 各自坐标），并把该行复制到剪贴板。

### 10.3 决策记录（用户「按推荐实施」）

1. 预览：采用**方案 A** —— 宽度铺满 + 垂直居中裁切，裁切上限 `MAX_CROP_FACTOR = 1.4`（≈ 最多裁帧高 30%）。
2. 日志：采用「两行结构 + 类别聚合 + `maxLines=3` 换行」，目标行最多列 6 类，聚合格式 `person 0.89×3`。
3. 「点击日志行查看完整内容（对话框 + 复制到剪贴板）」**不做**（原方案中标注为可选项）。

原始 [待确认] 列表（已按上述决定实施）：

1. `[待确认]` 预览铺满方式：方案 A（宽度铺满 + 垂直裁切，裁切上限 1.4×）——是否采用？裁切上限取 1.4 是否合适
   （数值越大越"铺满"，裁掉的画面越多）？
2. `[待确认]` 日志完整显示：采用「两行结构 + 类别聚合 + `maxLines=3` 换行」吗？聚合格式 `person 0.89×3` 是否可以？
3. `[待确认]` 是否要加「点击日志行查看完整内容（对话框 + 复制到剪贴板）」？

## 11. 实施记录（更新点，第二轮）

1. 预览铺满：`CameraFrameView` 新增 `MAX_CROP_FACTOR = 1.4f` 常量；`onDraw` 缩放由 `fit` 改为
   `s = min(fillScale, fitScale × MAX_CROP_FACTOR)`，`contentRect` 允许负值/越界（框仍与画面同一映射）。
2. 日志完整显示：`item_detection_log.xml` 去掉 `singleLine`、改 `maxLines=3 + ellipsize=end`；
   正文抽到新类 `DetectionLogFormatter`（纯 Java，`head`/`detail`/`body`/`estimatedColumns`），
   `MainActivity.appendDetectionLog` 直接用 `body()` 的结果做去重签名与展示（只格式化一次）。
3. 新增 `DetectionLogFormatterTest`（5 例）：聚合保留最高分与数量、按最高分降序、超 6 类截断 + 汇总、
   两行结构与空目标、行宽上界（实测 head 42 列 / 典型目标行 68 列 / 最坏 127 列）。
4. 未做：「点击日志行查看完整内容」。
5. 验证：`./gradlew :app:assembleDebug :app:testDebugUnitTest` → BUILD SUCCESSFUL，**23 项单测 0 失败**
   （DetectorPipelineTest 7 + ModelProfileTest 7 + RotationMappingTest 2 + LabelPaletteTest 2 + DetectionLogFormatterTest 5）。
   运行验证待用户手动执行（预览铺满效果与日志可读性属视觉项）。

## 12. 手动验证要点（前 3 点 + 更新点，合并）

1. 冷启动**不做任何操作**，状态栏应出现「模型已就绪：道闸模型(3类)（3 类, conf 0.5）」（问题 1 的回归点）。
2. 点「开启检测」后，日志区出现**两行**行，如：

   ```
   17:52:03.845 | 道闸模型(3类) | 推理 12ms | 1 个目标
   barrier_closed 0.96  (共 1 类/1 个)
   ```

   并随画面变化滚动；静止画面不会持续刷屏；日志最多 300 行。**关键看第二行是否完整显示**（不再被省略号截断）。
3. 切到「通用模型(COCO 80类)」后：日志行内的类名变成 `person/car/...` 且同类合并为 `person 0.89×3` 形式；
   不同类别框颜色可区分（黄金角配色）。
4. 预览区不再占满整屏高度（约 60%），日志区可上下滚动；**预览画面左右应无黑边**（按宽度铺满，上下被裁切），
   检测框位置与画面中的物体仍对得上。
5. 竖屏/横屏或平板等不同比例设备上，若出现黑边属预期（裁切被 1.4 倍上限限制）。

