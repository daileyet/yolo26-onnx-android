package com.openthinks.onnx.example;

import android.content.Context;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.util.Arrays;
import java.util.Collections;

import ai.onnxruntime.NodeInfo;
import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtException;
import ai.onnxruntime.OrtSession;
import ai.onnxruntime.TensorInfo;

/**
 * ONNX Runtime 推理引擎（Java 版，参考 doc/research.md）。
 *
 * 模型无关（Task 3）：输入尺寸、类别数（= 输出通道数 - 4）、候选框数与类名都从模型自身读取，
 * 因此同一套代码可以跑道闸 3 类模型与 COCO 80 类模型；切换模型时构造新实例并整体替换引用。
 *
 * 线程模型：OrtSession 线程安全，但本 Demo 只用单个推理线程串行调用 run()；
 * 输入缓冲由调用方（Letterboxer）复用，因此必须保证 run() 期间无人改写该缓冲
 * —— MainActivity 用 inferenceBusy 标志保证这一点；模型切换也在同一线程上串行执行。
 */
public final class OnnxInferenceEngine implements AutoCloseable {

    private final OrtEnvironment env;
    private final OrtSession session;
    private final String inputName;
    private final FloatBuffer inputBuffer;
    private final long[] inputShape;
    private final int inputSize;
    private final int numClasses;
    private final int numAnchors;
    private final String namesMetadata;

    /** 生产用法：从 assets 读取模型字节。 */
    public OnnxInferenceEngine(Context context, String assetName) throws IOException, OrtException {
        this(readAsset(context, assetName));
    }

    /**
     * 从模型字节创建；先 OrtEnvironment.getEnvironment() 再建会话是硬性顺序要求。
     * 构造期间读取输入/输出形状与 metadata，用于推导输入尺寸、类别数与类名。
     */
    public OnnxInferenceEngine(byte[] modelBytes) throws OrtException {
        this.env = OrtEnvironment.getEnvironment();
        OrtSession.SessionOptions options = new OrtSession.SessionOptions();
        options.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT);
        // 手机端 2 线程足够，线程过多反而抢占相机线程
        options.setIntraOpNumThreads(2);
        this.session = env.createSession(modelBytes, options);
        this.inputName = session.getInputNames().iterator().next();

        long[] inShape = shapeOf(session.getInputInfo().values().iterator().next());
        int h = inShape.length == 4 && inShape[2] > 0 ? (int) inShape[2] : Letterboxer.DEFAULT_INPUT_SIZE;
        int w = inShape.length == 4 && inShape[3] > 0 ? (int) inShape[3] : Letterboxer.DEFAULT_INPUT_SIZE;
        if (h != w) {
            throw new IllegalArgumentException("只支持方形输入，模型输入为 " + w + "x" + h);
        }
        this.inputSize = h;
        this.inputShape = new long[]{1, 3, inputSize, inputSize};
        this.inputBuffer = ByteBuffer.allocateDirect(3 * inputSize * inputSize * 4)
                .order(ByteOrder.nativeOrder())
                .asFloatBuffer();

        long[] outShape = shapeOf(session.getOutputInfo().values().iterator().next());
        if (outShape.length != 3) {
            throw new IllegalArgumentException("无法识别的输出形状: " + Arrays.toString(outShape));
        }
        this.numClasses = (int) outShape[1] - 4;
        this.numAnchors = (int) outShape[2];
        if (numClasses <= 0 || numAnchors <= 0) {
            throw new IllegalArgumentException("无法从输出形状推导类别数/候选数: " + Arrays.toString(outShape));
        }

        String names = null;
        try {
            names = session.getMetadata().getCustomMetadata().get("names");
        } catch (Throwable t) {
            // 部分模型没有 custom metadata，回退为 class_N
        }
        this.namesMetadata = names;
    }

    private static long[] shapeOf(NodeInfo nodeInfo) {
        if (nodeInfo.getInfo() instanceof TensorInfo) {
            return ((TensorInfo) nodeInfo.getInfo()).getShape();
        }
        return new long[0];
    }

    /** 复用型输入缓冲，交由 Letterboxer.fill() 填充。 */
    public FloatBuffer input() {
        return inputBuffer;
    }

    public String inputName() {
        return inputName;
    }

    /** 模型输入边长（方形，默认 640）。 */
    public int inputSize() {
        return inputSize;
    }

    /** 类别数 = 输出通道数 - 4。 */
    public int numClasses() {
        return numClasses;
    }

    /** 候选框数 = 输出最后一维（640 输入通常为 8400）。 */
    public int numAnchors() {
        return numAnchors;
    }

    /** metadata 里的 names 原始串（可能为 null）。 */
    public String namesMetadata() {
        return namesMetadata;
    }

    /**
     * 执行一次推理。
     *
     * @return 模型第一路输出的 float 数组（形状 [1][4+nc][anchors]）；getValue() 已是 Java 数组拷贝，
     *         因此出了 try-with-resources 仍然可用
     */
    public float[][][] run() throws OrtException {
        inputBuffer.rewind();
        try (OnnxTensor tensor = OnnxTensor.createTensor(env, inputBuffer, inputShape);
             OrtSession.Result result = session.run(Collections.singletonMap(inputName, tensor))) {
            return (float[][][]) result.get(0).getValue();
        }
    }

    @Override
    public void close() {
        try {
            session.close();
        } catch (Exception ignored) {
            // 关闭失败不影响退出流程
        }
    }

    private static byte[] readAsset(Context context, String assetName) throws IOException {
        try (InputStream in = context.getAssets().open(assetName);
             ByteArrayOutputStream out = new ByteArrayOutputStream(16 * 1024 * 1024)) {
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
            }
            return out.toByteArray();
        }
    }
}
