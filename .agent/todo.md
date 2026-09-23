
# Todo Task

预置规则 - Agent必读和约束

* 完成后需自动更新标注完成
* 设计实现plan至当前项目`.agents/plans`目录下
* plan文件名以`taskxxx-`开头
* plan first
* task 倒序排列
* 不自动git commit 禁止自动git push

---

## Task 4 [完成]

需要优化点如下:

1. app主界面 切换模型 初始化默认当前选项 没有加载模型, 需要切到另一个模型,再切回来,才能加载
2. app主界面 摄像头预览位图与检测框的view不需要铺满整个设备屏幕, 至少高度不需要. 加一个日志显示框, 显示检测结果, 滚动, recycle
3. com.openthinks.onnx.example.CameraFrameView#BOX_COLORS 切到coco 80就不够了

更新点:

1. 摄像头预览位图与检测框的view width是否可以铺满屏幕
2. 日志每行都显示不下内容, 思考可以显示完整的方案

完成说明 (方案: `.agent/plans/task4-ui-optimizations.md`; 验证: 前 3 点用户已手动验证通过, 更新点 2 项已实施):

1. 默认模型加载: `setSelection` 早于监听注册导致首次不回调 -> 改为「先注册监听 -> setSelection -> 显式 switchModel(默认模型)」
2. 布局改垂直三段 (预览 weight=3 / 日志 weight=2, ListView + 适配器复用行视图), 日志规则: 有目标+内容变化+≥200ms 才写, 上限 300 行, 自动滚底
3. 配色改黄金角色相 `LabelPalette.hueFor(classId)`, 删除 BOX_COLORS 固定三色, 80 类最小色相间隔 2.94°
4. 更新点 1: 预览改「宽度铺满 + 垂直居中裁切」, 裁切上限 1.4 倍 (平板等极端比例保留黑边), 框与画面同一 contentRect 映射
5. 更新点 2: 日志改两行结构 + 按类别聚合 (`person 0.89x3`) + maxLines=3 换行, 抽 `DetectionLogFormatter` 纯 Java 类并单测锁行宽

测试: 23 项单测 0 失败 (`./gradlew :app:assembleDebug :app:testDebugUnitTest`)

## Task 3 [完成]

app界面 提供模型文件切换 `app\src\main\assets` 目前有两各模型文件

`com.openthinks.onnx.example.Detector#CLASS_NAMES` 需要针对具体选择模型更新

## Task 2 [完成]

`app\src\main\assets\yolo26_barrier.onnx` 是否支持通用物体检测

## Task 1 [完成]

在当前目录创建Android Java项目, 用于演示利用手机摄像头实时进行目标检测.
实现原理基于以下信息参考:
1. yolo26已训练模型 `model\yolo26_barrier.onnx`
2. onnx android框架 `doc\research.md`
3. camera2 原生api

具体实现功能:
1. app主界面 实时显示 摄像头 图像 支持 正反摄像头切换
2. 提供检测按钮 开启实时检测 效果 实时图像上应有检测的物体框

### build.gradle

build.gradle参考如下配置

```
buildscript {
    repositories {
        maven { url 'https://maven.aliyun.com/repository/public' }
        maven { url 'https://maven.aliyun.com/repository/google' }
        maven { url 'https://maven.aliyun.com/repository/gradle-plugin' }
        // 备份源
        google()
        mavenCentral()
    }
    dependencies {
        classpath 'com.android.tools.build:gradle:8.9.1'
        classpath "com.google.protobuf:protobuf-gradle-plugin:0.9.5"
    }
}

allprojects {
    repositories {
        maven { url 'https://maven.aliyun.com/repository/public' }
        maven { url 'https://maven.aliyun.com/repository/google' }
        maven { url 'https://maven.aliyun.com/repository/gradle-plugin' }
        // 备份源
        google()
        mavenCentral()
    }
}
```

在实施前,首先进行可行性分析,plan first

### 确认

1. com.openthinks.onnx.example / 「道闸检测 Demo」）。
2. 自行验证
3. `arm64-v8a + x86_64`
4. 否
5. 不用 CameraX
6. 否