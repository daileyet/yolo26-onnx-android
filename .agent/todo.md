
# Todo Task

预置规则 - Agent必读和约束

* 完成后需自动更新标注完成
* 设计实现plan至当前项目`.agents/plans`目录下
* plan文件名以`taskxxx-`开头
* plan first
* task 倒序排列
* 不自动git commit 禁止自动git push

---

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