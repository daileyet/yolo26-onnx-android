# Task 6：APK 产物统一命名为 yolo26-onnx-example.apk

> 需求原文：build 的 apk 名字 命名为 yolo26-onnx-example.apk, debug 和 release 都一样

## 1. 现状

1. AGP 默认产物名：`app/build/outputs/apk/debug/app-debug.apk`、`app/build/outputs/apk/release/app-release-unsigned.apk`
   （release 未配置签名，所以带 `-unsigned` 后缀）。
2. 当前 debug 目录里实际躺着一个 `yolo26-onnx.apk`（2026-09-23 14:47）—— 是**手工改名**：
   同目录 `output-metadata.json` 里仍写着 `"outputFile": "app-debug.apk"`，仓库里没有任何改名配置。
3. 引用产物路径、需要同步修改的地方：

| 位置 | 内容 | 是否必须改 |
|---|---|---|
| `tools/verify-on-device.sh:25` | 默认 APK 路径 `.../debug/app-debug.apk` | 必须（否则脚本找不到 APK） |
| `README.md:68`（§3 构建） | 产出路径注释 | 必须 |
| `README.md:332`（§10.5） | `adb install -r -t ...debug/app-debug.apk` | 必须 |
| `.github/workflows/android.yml:38` | GitHub Release 上传 `app/build/outputs/apk/release/*.apk` | **不用改**（通配符，兼容新名字） |
| `.agent/plans/task1-*.md` | 历史记录里的旧产物名 | 不改（历史快照） |

## 2. 方案

### 方案 A（推荐）：Gradle 变体 API 里重命名

```groovy
// 产物重命名：debug 与 release 统一叫 yolo26-onnx-example.apk
android.applicationVariants.all { variant ->
    variant.outputs.all { outputFileName = 'yolo26-onnx-example.apk' }
}
```

**为什么只能用 legacy API（有实证）**：AGP 8.9.1 的新变体 API 没有文件名入口 ——
`javap -cp gradle-api-8.9.1.jar com.android.build.api.variant.VariantOutput` 只有：

```
getVersionCode() / getVersionName() / getEnabled() / getEnable()
```

没有 `outputFileName`；而 legacy 的 `applicationVariants`（`BaseExtension`）在 AGP 8.9.1 里仍存在（jar 内可查到）。
代价：legacy 变体 API **已被标记废弃，AGP 9 会移除**，升级 AGP 时需要迁移（届时新 API 若仍无此能力，
就退化为后处理任务或改由 CI/脚本改名）——在代码注释里写明这一点。

### 方案 B：构建后重命名任务（`tasks.register(...).doLast { rename }`）

不推荐：AGP 产物目录与 `output-metadata.json` 会与实际文件不一致，IDE「Build APK」、
`adb install`、CI 若直接用具体路径都可能拿不到文件，属于和 `app-debug.apk` 同类的"手工改名"问题。

### 方案 C：保留默认名 + 额外复制一份新名

不满足「debug 和 release 都一样」的需求（会产生两份文件），且同样存在 metadata 不一致。

## 3. 实现清单（按方案 A）

1. `app/build.gradle`：在 `android { ... }` 之后加 `android.applicationVariants.all { ... }` 代码块 + 注释
   （说明为什么用 legacy API、AGP 升级时的注意事项）。
2. `tools/verify-on-device.sh:25`：默认路径改为 `.../debug/yolo26-onnx-example.apk`。
3. `README.md`：§3 注释与 §10.5 的 `adb install` 示例改为新文件名；§2 目录结构注释无需改。
4. 删除工作区里手工改名的 `app/build/outputs/apk/debug/yolo26-onnx.apk`（构建产物，不进 git，重命名后自然消失）。

## 4. 验证方式

1. `./gradlew :app:assembleDebug :app:assembleRelease` → BUILD SUCCESSFUL。
2. 产物检查：两个目录下都应是 `yolo26-onnx-example.apk`，且**旧名不再存在**
   （`ls app/build/outputs/apk/{debug,release}/`）。
3. `output-metadata.json` 的 `outputFile` 应等于新名字 —— 证明是构建期改名而不是事后 `mv`。
4. `aapt2 dump badging app/build/outputs/apk/debug/yolo26-onnx-example.apk`：
   包名 `com.openthinks.onnx.example`、versionCode 1、versionName 1.0、`native-code: 'arm64-v8a'`
   （当前 `abiFilters` 只留 arm64-v8a）应与改名前一致。
5. `unzip -l` 抽查：`assets/yolo26_barrier.onnx`、`assets/yolo26n.onnx`、`lib/arm64-v8a/libonnxruntime.so` 均在。
6. `bash -n tools/verify-on-device.sh` + 确认脚本默认路径存在（脚本能定位到新产物）。
7. 可选：把新 debug 包装到模拟器冒烟（当前模拟器离线）。

## 7. 实施记录（2026-09）

用户选择 **方案 A**（release 不加 signingConfig，README 注明未签名；手工产物直接删除）：

1. `app/build.gradle` 增加 `android.applicationVariants.all { variant.outputs.all { outputFileName = 'yolo26-onnx-example.apk' } }`
   （含「为什么只能用 legacy API、AGP 9 会移除」的注释）。
   原因实证：`javap` AGP 8.9.1 的 `com.android.build.api.variant.VariantOutput` 只有 versionCode/versionName/enabled。
2. `tools/verify-on-device.sh` 默认 APK 路径改为 `debug/yolo26-onnx-example.apk`（另修一处写成 `$(1:-...)` 的笔误，
   已 `bash -n` + 参数解析实测：无参走默认路径、带参用传入路径）。
3. `README.md`：§3 构建段（新产物名、release 未签名、legacy API 说明）、§7 第 5/7 条（ABI 现状与未签名提示）、
   §10.5（ABI 现状 + 新产物名的安装命令）。
4. 删除手工改名的 `app/build/outputs/apk/debug/yolo26-onnx.apk`。

验证结果（实测）：

1. `./gradlew :app:assembleDebug :app:assembleRelease :app:testDebugUnitTest` → BUILD SUCCESSFUL，23 项单测 0 失败。
2. 两个目录均产出 `yolo26-onnx-example.apk`，旧名（`app-debug.apk` / `app-release-unsigned.apk` / `yolo26-onnx.apk`）全部消失。
3. `output-metadata.json` 的 `outputFile` = `yolo26-onnx-example.apk`（debug 与 release 都是），证明是构建期改名。
4. `aapt2 dump badging`：包名 `com.openthinks.onnx.example`、versionCode 1、versionName 1.0、
   label「道闸检测 Demo」、`native-code: 'arm64-v8a'` 均未变。
5. `unzip -l`：`assets/yolo26_barrier.onnx`（9794752，脱敏版）、`assets/yolo26n.onnx`、
   `lib/arm64-v8a/libonnxruntime.so` 均在；debug 42.8MiB / release 42.7MiB。
6. CI 通配 `app/build/outputs/apk/release/*.apk` 仍命中新文件，无需改 workflow。

## 5. 风险与注意点

1. **未签名 release 的 `-unsigned` 提示会消失**：名字统一后，`yolo26-onnx-example.apk`（release）实际仍未签名，
   直接用 `adb install` 会报 `INSTALL_PARSE_FAILED_NO_CERTIFICATES`。CI 把它当 Release 附件发布时，
   下载者会遇到同样问题。（本次决定：不加 signingConfig，README 第 7 节第 7 条已注明。）
2. **AGP 升级**：legacy 变体 API 废弃（见 §2），换 AGP 大版本时要重新评估。
3. **CI 无需改动**：workflow 用 `release/*.apk` 通配，改名后仍能正确上传；但若以后改成写死路径，需同步。

## 6. 决策记录（用户「按推荐实施」）

1. 采用**方案 A**（legacy `applicationVariants` 改名）。
2. release **不加** `signingConfig`，只在 README 注明「未签名、安装前需自行签名」。
3. 手工改名的 `app/build/outputs/apk/debug/yolo26-onnx.apk` 直接删除。

