package com.openthinks.onnx.example;

/**
 * 单个检测结果。
 *
 * 坐标是相对「摆正后整帧」(upright frame) 的归一化值：中心点 + 宽高，取值 0~1。
 * 纯 Java 类，无 Android 依赖，便于在 JVM 单元测试中校验。
 */
public final class Detection {

    public final int classId;
    public final float score;
    public final float cx;
    public final float cy;
    public final float w;
    public final float h;

    public Detection(int classId, float score, float cx, float cy, float w, float h) {
        this.classId = classId;
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
        if (classId >= 0 && classId < Detector.CLASS_NAMES.length) {
            return Detector.CLASS_NAMES[classId];
        }
        return "class_" + classId;
    }

    @Override
    public String toString() {
        return className() + "(" + String.format("%.2f", score) + " @ "
                + String.format("%.3f,%.3f %.3fx%.3f", cx, cy, w, h) + ")";
    }
}
