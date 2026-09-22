package com.openthinks.onnx.example;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.junit.Test;

/**
 * 检测日志格式化的性质：
 *   1. 两行结构（head / detail）；
 *   2. 目标行按类别聚合（同类只留最高分 + 数量）并按最高分降序；
 *   3. 类别数超限时截断并给出「共 N 类/N 个」；
 *   4. 行宽受控：即使是最长的 COCO 类名，整行在窄屏（约 50 列/行）下 3 行内也能显示完整。
 */
public class DetectionLogFormatterTest {

    private static Detection det(String name, float score) {
        return new Detection(0, name, score, 0.5f, 0.5f, 0.1f, 0.1f);
    }

    @Test
    public void aggregatesByClassNameKeepingBestScore() {
        List<Detection> detections = Arrays.asList(
                det("person", 0.89f), det("person", 0.80f), det("person", 0.80f),
                det("car", 0.71f), det("truck", 0.58f));
        String detail = DetectionLogFormatter.detail(detections);
        System.out.println("detail: " + detail);
        assertTrue(detail, detail.startsWith("person 0.89×3, car 0.71, truck 0.58"));
        assertTrue("应给出类别与目标总数", detail.endsWith("(共 3 类/5 个)"));
        // 单个目标不显示 ×N
        assertTrue(DetectionLogFormatter.detail(Arrays.asList(det("person", 0.9f))).contains("person 0.90"));
    }

    @Test
    public void sortsClassesByBestScoreDescending() {
        List<Detection> detections = Arrays.asList(
                det("truck", 0.30f), det("person", 0.95f), det("car", 0.60f));
        String detail = DetectionLogFormatter.detail(detections);
        assertTrue(detail.indexOf("person") < detail.indexOf("car"));
        assertTrue(detail.indexOf("car") < detail.indexOf("truck"));
    }

    @Test
    public void truncatesWhenTooManyClasses() {
        List<Detection> detections = new ArrayList<>();
        for (int i = 0; i < 12; i++) {
            detections.add(det("class_" + i, 0.90f - i * 0.01f));
        }
        String detail = DetectionLogFormatter.detail(detections);
        System.out.println("detail(12 类): " + detail);
        assertTrue("应显示省略提示", detail.contains("…"));
        assertTrue("应给出总类别数与目标数", detail.endsWith("(共 12 类/12 个)"));
        long listed = Arrays.stream(detail.split(",")).count();
        assertEquals("最多列出 MAX_CLASSES_PER_LINE 类", DetectionLogFormatter.MAX_CLASSES_PER_LINE, listed);
    }

    @Test
    public void headAndDetailFitOnShortScreens() {
        // 典型 COCO 场景
        String head = DetectionLogFormatter.head("通用模型(COCO 80类)", 7, 28f);
        String detail = DetectionLogFormatter.detail(Arrays.asList(
                det("person", 0.89f), det("person", 0.80f), det("person", 0.80f),
                det("car", 0.71f), det("truck", 0.58f), det("backpack", 0.67f), det("car", 0.47f)));
        int headCols = DetectionLogFormatter.estimatedColumns(head);
        int detailCols = DetectionLogFormatter.estimatedColumns(detail);
        System.out.println("head=" + headCols + " 列, detail=" + detailCols + " 列");
        assertTrue("head 行应在窄屏一行内显示: " + headCols, headCols <= 64);
        assertTrue("detail 行应在窄屏两行内显示: " + detailCols, detailCols <= 110);

        // 最坏情况：6 个最长的 COCO 类名
        String worst = DetectionLogFormatter.detail(Arrays.asList(
                det("traffic light", 0.99f), det("potted plant", 0.99f), det("hair drier", 0.99f),
                det("sports ball", 0.99f), det("fire hydrant", 0.99f), det("parking meter", 0.99f)));
        int worstCols = DetectionLogFormatter.estimatedColumns(worst);
        System.out.println("worst detail=" + worstCols + " 列");
        // 50 列/行 × 3 行（maxLines=3）≈ 150 列的上限
        assertTrue("最坏情况也应在 3 行内显示: " + worstCols, worstCols <= 150);
    }

    @Test
    public void bodyIsTwoLinesAndHandlesEmpty() {
        String body = DetectionLogFormatter.body("道闸模型(3类)",
                Arrays.asList(det("barrier_closed", 0.96f)), 12f);
        System.out.println("body:\n" + body);
        String[] lines = body.split("\n");
        assertEquals(2, lines.length);
        assertEquals("道闸模型(3类) | 推理 12ms | 1 个目标", lines[0]);
        assertTrue(lines[1].startsWith("barrier_closed 0.96"));

        String empty = DetectionLogFormatter.detail(new ArrayList<>());
        assertEquals("无目标", empty);
    }
}
