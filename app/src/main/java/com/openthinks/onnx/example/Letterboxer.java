package com.openthinks.onnx.example;

import java.nio.FloatBuffer;

/**
 * 把「摆正后的整帧 ARGB 图像」按 letterbox 方式缩放到模型输入尺寸（默认 640x640），
 * 并写成 NCHW 的 float 张量。
 *
 * 硬约束（由模型契约决定，勿改）：
 *   1. 通道顺序 RGB，布局 NCHW，即 索引 = c*size*size + y*size + x；
 *   2. 数值必须 /255.0 归一化到 0~1（实测：送 0~255 原值会输出上百个 score=1.0 的垃圾框）；
 *   3. 保持宽高比 letterbox，填充值 0；模型输出框位于 size x size 空间，需用 scale/pad 反算回原帧。
 *
 * 输入尺寸由模型决定（Task 3）：构造时传入，默认 640。
 * 纯 Java，无 Android 依赖，可在 JVM 单元测试中直接验证。
 */
public final class Letterboxer {

    public static final int DEFAULT_INPUT_SIZE = 640;

    private static final float INV_255 = 1f / 255f;

    private final int inputSize;
    private final int plane;
    private final int[] mapX;
    private final int[] mapY;

    private int srcW;
    private int srcH;
    private float scale = 1f;
    private float padX;
    private float padY;

    public Letterboxer() {
        this(DEFAULT_INPUT_SIZE);
    }

    public Letterboxer(int inputSize) {
        if (inputSize <= 0) {
            throw new IllegalArgumentException("inputSize must be > 0: " + inputSize);
        }
        this.inputSize = inputSize;
        this.plane = inputSize * inputSize;
        this.mapX = new int[inputSize];
        this.mapY = new int[inputSize];
    }

    public int getInputSize() {
        return inputSize;
    }

    /**
     * 预计算 letterbox 几何关系（源尺寸未变化时直接复用，避免每帧重复计算）。
     */
    public void geometry(int srcW, int srcH) {
        if (srcW == this.srcW && srcH == this.srcH) {
            return;
        }
        this.srcW = srcW;
        this.srcH = srcH;
        this.scale = Math.min(inputSize / (float) srcW, inputSize / (float) srcH);
        int newW = Math.round(srcW * scale);
        int newH = Math.round(srcH * scale);
        this.padX = (inputSize - newW) / 2f;
        this.padY = (inputSize - newH) / 2f;
        for (int x = 0; x < inputSize; x++) {
            int sx = Math.round((x - padX) / scale);
            mapX[x] = (sx < 0 || sx >= srcW) ? -1 : sx;
        }
        for (int y = 0; y < inputSize; y++) {
            int sy = Math.round((y - padY) / scale);
            mapY[y] = (sy < 0 || sy >= srcH) ? -1 : sy;
        }
    }

    /**
     * 填充张量缓冲（值域 0~1），并按通道重排为 NCHW。调用后 position=0，可直接交给 ORT 建张量。
     *
     * @param out  容量 >= 3*inputSize*inputSize 的 FloatBuffer（建议 direct 缓冲）
     * @param argb 摆正后的整帧像素，长度 >= srcW*srcH，格式 0xAARRGGBB
     */
    public void fill(FloatBuffer out, int[] argb, int srcW, int srcH) {
        geometry(srcW, srcH);
        if (argb.length < srcW * srcH) {
            throw new IllegalArgumentException("argb too small: " + argb.length + " < " + (srcW * srcH));
        }
        for (int y = 0; y < inputSize; y++) {
            int sy = mapY[y];
            int rowBase = y * inputSize;
            int row = sy < 0 ? 0 : sy * srcW;
            for (int x = 0; x < inputSize; x++) {
                int sx = mapX[x];
                float r = 0f;
                float g = 0f;
                float b = 0f;
                if (sy >= 0 && sx >= 0) {
                    int p = argb[row + sx];
                    r = ((p >> 16) & 0xFF) * INV_255;
                    g = ((p >> 8) & 0xFF) * INV_255;
                    b = (p & 0xFF) * INV_255;
                }
                out.put(rowBase + x, r);
                out.put(plane + rowBase + x, g);
                out.put(2 * plane + rowBase + x, b);
            }
        }
        out.rewind();
    }

    public float getScale() {
        return scale;
    }

    public float getPadX() {
        return padX;
    }

    public float getPadY() {
        return padY;
    }

    public int getSourceWidth() {
        return srcW;
    }

    public int getSourceHeight() {
        return srcH;
    }
}
