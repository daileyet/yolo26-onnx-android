package com.openthinks.onnx.example;

import android.content.Context;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.util.Collections;

import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtException;
import ai.onnxruntime.OrtSession;

/**
 * ONNX Runtime 推理引擎（Java 版，参考 doc/research.md）。
 *
 * 线程模型：OrtSession 线程安全，但本 Demo 只用单个推理线程串行调用 run()；
 * 输入缓冲由调用方（Letterboxer）复用，因此必须保证 run() 期间无人改写该缓冲
 * —— MainActivity 用 inferenceBusy 标志保证这一点。
 */
public final class OnnxInferenceEngine implements AutoCloseable {

    private static final int INPUT_SIZE = Letterboxer.INPUT_SIZE;
    private static final int NUM_FLOATS = 3 * INPUT_SIZE * INPUT_SIZE;

    private final OrtEnvironment env;
    private final OrtSession session;
    private final String inputName;
    private final FloatBuffer inputBuffer;
    private final long[] inputShape = {1, 3, INPUT_SIZE, INPUT_SIZE};

    /** 生产用法：从 assets 读取模型字节。 */
    public OnnxInferenceEngine(Context context, String assetName) throws IOException, OrtException {
        this(readAsset(context, assetName));
    }

    /** 从模型字节创建；先 OrtEnvironment.getEnvironment() 再建会话是硬性顺序要求。 */
    public OnnxInferenceEngine(byte[] modelBytes) throws OrtException {
        this.env = OrtEnvironment.getEnvironment();
        OrtSession.SessionOptions options = new OrtSession.SessionOptions();
        options.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT);
        // 手机端 2 线程足够，线程过多反而抢占相机线程
        options.setIntraOpNumThreads(2);
        this.session = env.createSession(modelBytes, options);
        this.inputName = session.getInputNames().iterator().next();
        this.inputBuffer = ByteBuffer.allocateDirect(NUM_FLOATS * 4)
                .order(ByteOrder.nativeOrder())
                .asFloatBuffer();
    }

    /** 复用型输入缓冲，交由 Letterboxer.fill() 填充。 */
    public FloatBuffer input() {
        return inputBuffer;
    }

    public String inputName() {
        return inputName;
    }

    /**
     * 执行一次推理。
     *
     * @return 模型第一路输出的 float 数组视图，形状 [1][7][8400]；仅在 Result 关闭前有效，
     *         调用方需在同一次 run 内完成解码（Detector.detect 只读数组，安全）
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
