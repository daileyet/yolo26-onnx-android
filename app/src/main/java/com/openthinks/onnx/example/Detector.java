package com.openthinks.onnx.example;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * YOLO26 detect 头输出的解码 + 按类 NMS。
 *
 * 模型契约（model/yolo26_barrier.onnx，实测）：
 *   输出 output0 = [1, 7, 8400]，其中 4 + NUM_CLASSES；
 *   - 通道 0..3：cx, cy, w, h —— 已经是解码后的中心式坐标，位于 640x640 letterbox 空间（像素）；
 *   - 通道 4..6：三个类别分数，图中已含 Sigmoid，取值 0~1；
 *   - 没有 objectness，也无需 DFL 解码（YOLO26 直回归头）。
 *
 * 纯 Java，无 Android 依赖，可在 JVM 单元测试中直接验证。
 */
public final class Detector {

    public static final String[] CLASS_NAMES = {"barrier_closed", "barrier_open", "barrier_raising"};

    private static final int NUM_ANCHORS = 8400;
    private static final int NUM_CLASSES = CLASS_NAMES.length;

    private static final Comparator<Detection> BY_SCORE_DESC = new Comparator<Detection>() {
        @Override
        public int compare(Detection a, Detection b) {
            return Float.compare(b.score, a.score);
        }
    };

    private final float confThreshold;
    private final float iouThreshold;
    private final int maxDetections;

    public Detector() {
        this(0.25f, 0.45f, 20);
    }

    public Detector(float confThreshold, float iouThreshold, int maxDetections) {
        this.confThreshold = confThreshold;
        this.iouThreshold = iouThreshold;
        this.maxDetections = maxDetections;
    }

    /**
     * @param out   模型输出，形状 [1][4 + NUM_CLASSES][8400]
     * @param scale Letterboxer 的缩放比
     * @param padX  Letterboxer 的水平填充
     * @param padY  Letterboxer 的垂直填充
     * @param srcW  摆正后整帧宽度（像素）
     * @param srcH  摆正后整帧高度（像素）
     * @return 归一化坐标(cx,cy,w,h，0~1)的检测结果，按置信度降序，最多 maxDetections 个
     */
    public List<Detection> detect(float[][][] out, float scale, float padX, float padY, int srcW, int srcH) {
        float[][] o = out[0];
        List<Detection> candidates = new ArrayList<>();
        for (int i = 0; i < NUM_ANCHORS; i++) {
            int bestClass = 0;
            float bestScore = o[4][i];
            for (int c = 1; c < NUM_CLASSES; c++) {
                float s = o[4 + c][i];
                if (s > bestScore) {
                    bestScore = s;
                    bestClass = c;
                }
            }
            if (bestScore < confThreshold) {
                continue;
            }
            // 640x640 letterbox 空间 -> 原帧像素 -> 归一化
            float cx = (o[0][i] - padX) / scale;
            float cy = (o[1][i] - padY) / scale;
            float w = o[2][i] / scale;
            float h = o[3][i] / scale;
            candidates.add(new Detection(bestClass, bestScore,
                    cx / srcW, cy / srcH, w / srcW, h / srcH));
        }
        return nms(candidates);
    }

    /**
     * 因为每个候选只归属一个类别，按置信度降序做同类抑制即可。
     */
    private List<Detection> nms(List<Detection> candidates) {
        Collections.sort(candidates, BY_SCORE_DESC);
        List<Detection> kept = new ArrayList<>();
        for (Detection cand : candidates) {
            boolean suppressed = false;
            for (Detection k : kept) {
                if (k.classId == cand.classId && iou(k, cand) > iouThreshold) {
                    suppressed = true;
                    break;
                }
            }
            if (!suppressed) {
                kept.add(cand);
                if (kept.size() >= maxDetections) {
                    break;
                }
            }
        }
        return kept;
    }

    /** 归一化坐标下的 IoU。 */
    public static float iou(Detection a, Detection b) {
        float ax1 = a.left();
        float ay1 = a.top();
        float ax2 = a.right();
        float ay2 = a.bottom();
        float bx1 = b.left();
        float by1 = b.top();
        float bx2 = b.right();
        float by2 = b.bottom();
        float ix = Math.max(0f, Math.min(ax2, bx2) - Math.max(ax1, bx1));
        float iy = Math.max(0f, Math.min(ay2, by2) - Math.max(ay1, by1));
        float inter = ix * iy;
        float union = (ax2 - ax1) * (ay2 - ay1) + (bx2 - bx1) * (by2 - by1) - inter;
        return union > 0f ? inter / union : 0f;
    }
}
