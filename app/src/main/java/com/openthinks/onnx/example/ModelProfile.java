package com.openthinks.onnx.example;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 模型配置：随所选模型变化的一切后处理参数（类名、类别数、候选数、阈值、NMS 上限、是否启用几何过滤）。
 *
 * 设计要点（Task 3）：
 *   1. 配置整体构造、不提供 setter —— 切换模型时构造新的 {@link Detector} 并整体替换引用，
 *      避免出现「新类名 + 旧类别数/旧阈值」的半更新中间态（例如 80 类模型仍只解 3 个通道）；
 *   2. 类名与类别数来自模型自身（输出通道数与 metadata 的 names），不写死在代码里；
 *   3. 「大框低分」这类启发式规则只对已验证过的模型（内置表）开启 —— 实测把道闸模型的规则
 *      套到 COCO 模型上会误杀正常输出（人像图里 bench 0.75 / 框面积 68.8%）。
 *
 * 纯 Java，无 Android 依赖，可在 JVM 单测中直接验证。
 */
public final class ModelProfile {

    public static final int DEFAULT_NUM_ANCHORS = 8400;

    /** 道闸模型的类名 metadata（与模型内一致，供无参构造/单测使用）。 */
    public static final String BARRIER_NAMES_METADATA =
            "{0: 'barrier_closed', 1: 'barrier_open', 2: 'barrier_raising'}";

    private static final Pattern CLASS_NAME_PATTERN = Pattern.compile("(\\d+)\\s*:\\s*'([^']*)'");

    /** 类数 <= 该值视为「专用模型」，用更严的阈值；启发式过滤只对内置表内模型开启。 */
    private static final int SMALL_MODEL_MAX_CLASSES = 10;
    private static final float SMALL_MODEL_CONF_THRESHOLD = 0.5f;
    private static final float GENERAL_MODEL_CONF_THRESHOLD = 0.25f;
    private static final float DEFAULT_IOU_THRESHOLD = 0.45f;
    private static final int SMALL_MODEL_MAX_DETECTIONS = 20;
    private static final int GENERAL_MODEL_MAX_DETECTIONS = 30;

    /** 内置模型表：只对实测过的模型使用特定阈值与启发式规则。 */
    private static final Entry[] TABLE = {
            new Entry("yolo26_barrier.onnx", "道闸模型(3类)", 0.5f, 0.45f, 20, true),
            new Entry("yolo26n.onnx", "通用模型(COCO 80类)", 0.25f, 0.45f, 30, false),
    };

    public final String assetName;
    public final String displayName;
    public final int numClasses;
    public final int numAnchors;
    public final String[] classNames;
    public final float confThreshold;
    public final float iouThreshold;
    public final int maxDetections;
    public final boolean bigBoxFilterEnabled;

    private ModelProfile(String assetName, String displayName, int numClasses, int numAnchors,
                         String[] classNames, float confThreshold, float iouThreshold,
                         int maxDetections, boolean bigBoxFilterEnabled) {
        this.assetName = assetName;
        this.displayName = displayName;
        this.numClasses = numClasses;
        this.numAnchors = numAnchors;
        this.classNames = classNames;
        this.confThreshold = confThreshold;
        this.iouThreshold = iouThreshold;
        this.maxDetections = maxDetections;
        this.bigBoxFilterEnabled = bigBoxFilterEnabled;
    }

    /**
     * 依据模型自身的形状与 metadata 构造配置。
     *
     * @param numClasses    类别数（= 输出通道数 - 4）
     * @param numAnchors    候选框数（= 输出最后一维）
     * @param namesMetadata 模型 metadata 里的 names 串，形如 {@code {0: 'person', 1: 'bicycle', ...}}
     */
    public static ModelProfile of(String assetName, int numClasses, int numAnchors, String namesMetadata) {
        String[] classNames = parseClassNames(namesMetadata, numClasses);
        Entry entry = lookup(assetName);
        if (entry != null) {
            return new ModelProfile(assetName, entry.displayName, numClasses, numAnchors, classNames,
                    entry.confThreshold, entry.iouThreshold, entry.maxDetections, entry.bigBoxFilterEnabled);
        }
        boolean small = numClasses <= SMALL_MODEL_MAX_CLASSES;
        return new ModelProfile(assetName, shortLabel(assetName) + "(" + numClasses + "类)",
                numClasses, numAnchors, classNames,
                small ? SMALL_MODEL_CONF_THRESHOLD : GENERAL_MODEL_CONF_THRESHOLD,
                DEFAULT_IOU_THRESHOLD,
                small ? SMALL_MODEL_MAX_DETECTIONS : GENERAL_MODEL_MAX_DETECTIONS,
                false);
    }

    /** 自定义配置（供单测与内置表外的特殊模型使用）。 */
    public static ModelProfile custom(String assetName, int numClasses, int numAnchors, String[] classNames,
                                      float confThreshold, float iouThreshold, int maxDetections,
                                      boolean bigBoxFilterEnabled) {
        return new ModelProfile(assetName, shortLabel(assetName) + "(" + numClasses + "类)",
                numClasses, numAnchors, classNames, confThreshold, iouThreshold, maxDetections,
                bigBoxFilterEnabled);
    }

    /** 道闸模型的默认配置（等价于 Task 1 的行为，供无参 Detector 与单测使用）。 */
    public static ModelProfile barrierDefault() {
        return of("yolo26_barrier.onnx", 3, DEFAULT_NUM_ANCHORS, BARRIER_NAMES_METADATA);
    }

    /** Spinner 显示名：内置表命中用友好名，否则去扩展名。 */
    public static String shortLabel(String assetName) {
        Entry entry = lookup(assetName);
        if (entry != null) {
            return entry.displayName;
        }
        int dot = assetName.lastIndexOf('.');
        return dot > 0 ? assetName.substring(0, dot) : assetName;
    }

    /**
     * 解析 metadata 里的类名表。解析不到的位置回退 {@code class_N}，不抛异常。
     * 注意 COCO 类名含空格（'traffic light'、'hair drier'），所以用引号界定而非按空格切分。
     */
    public static String[] parseClassNames(String namesMetadata, int numClasses) {
        String[] names = new String[numClasses];
        for (int i = 0; i < numClasses; i++) {
            names[i] = "class_" + i;
        }
        if (namesMetadata == null || namesMetadata.isEmpty()) {
            return names;
        }
        Matcher matcher = CLASS_NAME_PATTERN.matcher(namesMetadata);
        while (matcher.find()) {
            int index = Integer.parseInt(matcher.group(1));
            if (index >= 0 && index < numClasses) {
                names[index] = matcher.group(2);
            }
        }
        return names;
    }

    private static Entry lookup(String assetName) {
        if (assetName == null) {
            return null;
        }
        for (Entry entry : TABLE) {
            if (entry.assetName.equalsIgnoreCase(assetName)) {
                return entry;
            }
        }
        return null;
    }

    @Override
    public String toString() {
        return displayName + " [asset=" + assetName + ", nc=" + numClasses + ", anchors=" + numAnchors
                + ", conf=" + confThreshold + ", iou=" + iouThreshold + ", maxDet=" + maxDetections
                + ", bigBoxFilter=" + bigBoxFilterEnabled + "]";
    }

    private static final class Entry {
        final String assetName;
        final String displayName;
        final float confThreshold;
        final float iouThreshold;
        final int maxDetections;
        final boolean bigBoxFilterEnabled;

        Entry(String assetName, String displayName, float confThreshold, float iouThreshold,
              int maxDetections, boolean bigBoxFilterEnabled) {
            this.assetName = assetName;
            this.displayName = displayName;
            this.confThreshold = confThreshold;
            this.iouThreshold = iouThreshold;
            this.maxDetections = maxDetections;
            this.bigBoxFilterEnabled = bigBoxFilterEnabled;
        }
    }
}
