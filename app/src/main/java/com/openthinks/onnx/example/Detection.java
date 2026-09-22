package com.openthinks.onnx.example;

/**
 * 单个检测结果。
 *
 * 坐标是相对「摆正后整帧」(upright frame) 的归一化值：中心点 + 宽高，取值 0~1。
 * 类名随模型变化，因此随结果一起携带（Task 3 要求 CLASS_NAMES 跟随所选模型，
 * 这里不再引用任何静态类名表）。
 *
 * 纯 Java 类，无 Android 依赖，便于在 JVM 单元测试中校验。
 */
public final class Detection {

    public final int classId;
    public final String className;
    public final float score;
    public final float cx;
    public final float cy;
    public final float w;
    public final float h;

    public Detection(int classId, String className, float score,
                     float cx, float cy, float w, float h) {
        this.classId = classId;
        this.className = className;
        this.score = score;
        this.cx = cx;
        this.cy = cy;
        this.w = w;
        this.h = h;
    }

    public float left() {
        return cx - w / 2f;
    }

    public float top() {
        return cy - h / 2f;
    }

    public float right() {
        return cx + w / 2f;
    }

    public float bottom() {
        return cy + h / 2f;
    }

    public String className() {
        return className;
    }

    @Override
    public String toString() {
        return className + "(" + String.format("%.2f", score) + " @ "
                + String.format("%.3f,%.3f %.3fx%.3f", cx, cy, w, h) + ")";
    }
}
