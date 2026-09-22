你提到的这两个依赖，是 Android 端使用 ONNX Runtime 的标准配置。`onnxruntime-android` 负责核心推理，而 `onnxruntime-extensions-android` 则用于支持模型内置的预处理/后处理算子，能极大简化开发流程。

下面是基于 Java 的完整推理步骤。

### 📝 准备工作：添加依赖与模型

首先，在 `app/build.gradle` 中添加依赖，并确保 `repositories` 中包含 `mavenCentral()`。

```gradle
dependencies {
    implementation 'com.microsoft.onnxruntime:onnxruntime-android:latest.release'
    implementation 'com.microsoft.onnxruntime:onnxruntime-extensions-android:latest.release'
}
```

然后，将你的 `.onnx` 模型文件放入 Android 项目的 `src/main/assets/` 目录下，以便通过 `AssetManager` 读取。

### 🚀 核心推理步骤

以下代码示例展示了从初始化到推理的完整流程。

#### 1. 初始化环境与加载模型
`OrtEnvironment` 是全局单例，必须在创建任何会话之前初始化。建议从 `assets` 读取模型字节，并注册扩展算子库。

```java
import ai.onnxruntime.*;
import ai.onnxruntime.extensions.OrtxPackage;
import android.content.Context;
import java.io.InputStream;
import java.nio.FloatBuffer;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

public class OnnxInferenceEngine {
    private OrtEnvironment env;
    private OrtSession session;
    private static final int IMG_SIZE = 640; // 根据你的模型调整

    public OnnxInferenceEngine(Context context, String modelName) throws Exception {
        // 1. 初始化全局环境 (必须在所有其他操作之前)
        env = OrtEnvironment.getEnvironment();

        // 2. 配置会话选项
        OrtSession.SessionOptions sessionOptions = new OrtSession.SessionOptions();
        // 注册 onnxruntime-extensions 提供的自定义算子库 (如用于内置预处理)
        sessionOptions.registerCustomOpLibrary(OrtxPackage.getLibraryPath());
        // (可选) 添加 NNAPI 执行提供程序以提升性能
        // sessionOptions.addNnapi();

        // 3. 从 assets 加载模型并创建推理会话
        byte[] modelBytes = loadModelFromAssets(context, modelName);
        session = env.createSession(modelBytes, sessionOptions);
    }

    private byte[] loadModelFromAssets(Context context, String filename) throws Exception {
        try (InputStream is = context.getAssets().open(filename)) {
            byte[] buffer = new byte[is.available()];
            is.read(buffer);
            return buffer;
        }
    }
}
```

#### 2. 图像预处理与张量创建
将 `Bitmap` 缩放并归一化，然后转换为模型所需的 `OnnxTensor`。注意输入形状通常为 `[1, C, H, W]`。

```java
import android.graphics.Bitmap;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

public OnnxTensor preprocess(Bitmap bitmap) throws OrtException {
    // 1. 缩放图像到模型输入尺寸 (例如 640x640)
    Bitmap resizedBitmap = Bitmap.createScaledBitmap(bitmap, IMG_SIZE, IMG_SIZE, true);

    // 2. 创建 FloatBuffer 并归一化 (像素值 / 255.0)
    // 注意: ONNX 默认使用 NCHW 格式，需要按通道优先排列
    FloatBuffer floatBuffer = FloatBuffer.allocate(1 * 3 * IMG_SIZE * IMG_SIZE);
    int[] pixels = new int[IMG_SIZE * IMG_SIZE];
    resizedBitmap.getPixels(pixels, 0, IMG_SIZE, 0, 0, IMG_SIZE, IMG_SIZE);

    // 分别处理 R, G, B 三个通道 (NCHW 排列)
    for (int c = 0; c < 3; c++) {
        for (int i = 0; i < pixels.length; i++) {
            int pixel = pixels[i];
            int channelValue = (pixel >> (16 - c * 8)) & 0xFF; // 提取 R/G/B
            floatBuffer.put(channelValue / 255.0f);
        }
    }
    floatBuffer.rewind();

    // 3. 创建 OnnxTensor，形状为 [1, 3, IMG_SIZE, IMG_SIZE]
    long[] shape = new long[]{1, 3, IMG_SIZE, IMG_SIZE};
    return OnnxTensor.createTensor(env, floatBuffer, shape);
}
```

如果你的模型使用了 `onnxruntime-extensions` 的内置预处理（例如输入为 `UINT8` 的原始图像字节），则可以直接传入字节数组，无需手动归一化。

#### 3. 执行推理
将输入张量映射到模型的输入名称，然后运行会话。

```java
public OrtSession.Result runInference(OnnxTensor inputTensor) throws OrtException {
    // 获取模型的输入名称 (通常可以通过 session.getInputNames() 查看)
    String inputName = session.getInputNames().iterator().next();

    // 创建输入映射
    Map<String, OnnxTensor> inputs = new HashMap<>();
    inputs.put(inputName, inputTensor);

    // 执行推理
    return session.run(inputs);
}
```

#### 4. 解析输出结果
`OrtSession.Result` 包含了所有输出张量。你需要根据模型的定义来解析它们。例如，一个检测模型可能返回边界框和分数。

```java
public void parseResults(OrtSession.Result results) throws OrtException {
    // 方法一：通过索引获取 (顺序取决于模型定义)
    OnnxValue outputValue = results.get(0);
    // 假设输出是 float 数组
    float[] outputData = (float[]) outputValue.getValue();

    // 方法二：通过名称获取 (更推荐，需知道输出节点名称)
    // OnnxValue boxOutput = results.get("output_boxes").get();

    // 根据模型输出结构解析 outputData...
    // 例如，YOLO 输出可能需要 reshape 和 NMS 后处理

    // 使用完毕后，确保关闭结果以释放资源
    results.close();
}
```

### ⚠️ 常见问题与最佳实践

1.  **初始化顺序至关重要**：务必先调用 `OrtEnvironment.getEnvironment()`，再创建 `SessionOptions` 或 `OrtSession`，否则会抛出 `UnsatisfiedLinkError`。
2.  **资源管理**：`OrtSession`、`OnnxTensor` 和 `OrtSession.Result` 都实现了 `AutoCloseable`。推荐使用 try-with-resources 或在 `finally` 块中显式调用 `close()`，防止内存泄漏。
3.  **扩展库的作用**：`onnxruntime-extensions-android` 提供了如 `ImageDecoder`、`Resize` 等算子。如果你的模型内置了这些预处理步骤，就可以直接在模型中完成缩放和归一化，省去在 Java 层编写大量图像处理代码。
4.  **性能优化**：对于支持 NNAPI 的设备，可以在 `SessionOptions` 中调用 `addNnapi()` 来利用硬件加速。

### 💎 完整代码封装示例

下面是一个封装好的 `OnnxInferenceEngine` 类，整合了上述所有步骤，你可以直接参考使用。

```java
public class OnnxInferenceEngine implements AutoCloseable {
    private final OrtEnvironment env;
    private final OrtSession session;
    private static final int IMG_SIZE = 640;

    public OnnxInferenceEngine(Context context, String modelName) throws Exception {
        this.env = OrtEnvironment.getEnvironment();
        OrtSession.SessionOptions sessionOptions = new OrtSession.SessionOptions();
        sessionOptions.registerCustomOpLibrary(OrtxPackage.getLibraryPath());
        // sessionOptions.addNnapi(); // 可选：启用 NNAPI
        byte[] modelBytes = loadModelFromAssets(context, modelName);
        this.session = env.createSession(modelBytes, sessionOptions);
    }

    public float[] detect(Bitmap bitmap) throws OrtException {
        // 预处理
        Bitmap resized = Bitmap.createScaledBitmap(bitmap, IMG_SIZE, IMG_SIZE, true);
        FloatBuffer buffer = bitmapToFloatBuffer(resized);
        long[] shape = new long[]{1, 3, IMG_SIZE, IMG_SIZE};

        try (OnnxTensor inputTensor = OnnxTensor.createTensor(env, buffer, shape);
             OrtSession.Result results = session.run(Collections.singletonMap(
                     session.getInputNames().iterator().next(), inputTensor))) {

            // 解析第一个输出
            OnnxValue output = results.get(0);
            return (float[]) output.getValue();
        }
    }

    private FloatBuffer bitmapToFloatBuffer(Bitmap bitmap) {
        // ... 实现 NCHW 归一化逻辑 (见上文)
        return null; // 占位
    }

    @Override
    public void close() throws Exception {
        if (session != null) session.close();
        // OrtEnvironment 通常是全局单例，无需手动关闭
    }
}
```

你可以根据自己模型的具体输入输出名称和结构，调整 `session.run` 中的输入映射和结果解析逻辑。