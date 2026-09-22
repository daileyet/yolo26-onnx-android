package com.openthinks.onnx.example;

/**
 * 检测框/标签配色：按 classId 生成稳定色相（黄金角），类别数不受限。
 *
 * 之前的实现是 3 个固定颜色取模（`classId % 3`），切到 COCO 80 类后 classId 相差 3 就撞色。
 * 这里用黄金角 137.508° 均匀铺开色相：同一 classId 的颜色恒定（便于跨帧追踪同一类目标），
 * 相邻 classId 的色相间隔最大；实测 80 类时两两最小色相间隔约 2.94°。
 *
 * 纯 Java，无 Android 依赖，可在 JVM 单元测试中直接验证（Android 侧再转成 Color）。
 */
public final class LabelPalette {

    public static final float GOLDEN_ANGLE_DEGREES = 137.508f;
    public static final float SATURATION = 0.9f;
    public static final float VALUE = 0.95f;

    private LabelPalette() {
    }

    /** classId -> 色相（0~360，含 0 不含 360）。 */
    public static float hueFor(int classId) {
        float hue = (classId * GOLDEN_ANGLE_DEGREES) % 360f;
        return hue < 0f ? hue + 360f : hue;
    }
}
