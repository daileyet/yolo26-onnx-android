package com.openthinks.onnx.example;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 检测日志文本格式化（纯 Java，可在 JVM 单测）。
 *
 * 输出两行，短屏也能完整显示（原来的单行格式在窄屏上会被省略号截断）：
 *
 *   17:52:03.845 | 通用模型(COCO 80类) | 推理 28ms | 7 个目标
 *   person 0.89×3, car 0.71, truck 0.58, backpack 0.67  (共 4 类/7 个)
 *
 * 目标行按类别聚合（同类只保留最高分与数量），按最高分降序，最多列 {@link #MAX_CLASSES_PER_LINE} 类。
 */
public final class DetectionLogFormatter {

    public static final int MAX_CLASSES_PER_LINE = 6;

    private DetectionLogFormatter() {
    }

    /** 两行正文（head + detail）。 */
    public static String body(String modelLabel, List<Detection> detections, float inferenceMs) {
        int count = detections == null ? 0 : detections.size();
        return head(modelLabel, count, inferenceMs) + '\n' + detail(detections);
    }

    /** 第一行：模型 | 推理耗时 | 目标数。 */
    public static String head(String modelLabel, int count, float inferenceMs) {
        return modelLabel + " | 推理 " + Math.round(inferenceMs) + "ms | " + count + " 个目标";
    }

    /** 第二行：按类别聚合的目标列表 + 汇总（无目标时返回「无目标」）。 */
    public static String detail(List<Detection> detections) {
        if (detections == null || detections.isEmpty()) {
            return "无目标";
        }
        // 按类别聚合：同类保留最高分与数量
        Map<String, float[]> aggregated = new HashMap<>();
        for (Detection d : detections) {
            float[] agg = aggregated.get(d.className());
            if (agg == null) {
                aggregated.put(d.className(), new float[]{d.score, 1f});
            } else {
                agg[0] = Math.max(agg[0], d.score);
                agg[1] += 1f;
            }
        }
        List<Map.Entry<String, float[]>> entries = new ArrayList<>(aggregated.entrySet());
        entries.sort((a, b) -> Float.compare(b.getValue()[0], a.getValue()[0]));

        StringBuilder detail = new StringBuilder(96);
        int shown = Math.min(MAX_CLASSES_PER_LINE, entries.size());
        for (int i = 0; i < shown; i++) {
            if (i > 0) {
                detail.append(", ");
            }
            Map.Entry<String, float[]> entry = entries.get(i);
            detail.append(entry.getKey()).append(' ')
                    .append(String.format(Locale.ROOT, "%.2f", entry.getValue()[0]));
            int count = (int) entry.getValue()[1];
            if (count > 1) {
                detail.append('×').append(count);
            }
        }
        if (entries.size() > shown) {
            detail.append(" …");
        }
        detail.append("  (共 ").append(entries.size()).append(" 类/").append(detections.size()).append(" 个)");
        return detail.toString();
    }

    /**
     * 估算显示列数（等宽字体：ASCII 1 列、CJK 2 列），用于测试与排查「一行是否显示得下」。
     * 参考：11sp 等宽字体在 1080p 竖屏一行约 50~56 列。
     */
    public static int estimatedColumns(String text) {
        if (text == null) {
            return 0;
        }
        int columns = 0;
        for (int i = 0; i < text.length(); i++) {
            columns += text.charAt(i) > 0x2E80 ? 2 : 1;
        }
        return columns;
    }
}
