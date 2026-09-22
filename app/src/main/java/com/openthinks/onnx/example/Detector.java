package com.openthinks.onnx.example;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * YOLO26 detect 头输出的解码 + 按类 NMS + 几何过滤。
 *
 * 模型契约（通用，实测覆盖 3 类道闸模型与 80 类 COCO 模型）：
 *   输出 output0 = [1, 4 + nc, anchors]；
 *   - 通道 0..3：cx, cy, w, h —— 已是解码后的中心式坐标，位于 letterbox 空间（像素）；
 *   - 通道 4..(4+nc-1)：类别分数，图中已含 Sigmoid，取值 0~1；
 *   - 没有 objectness，也无需 DFL 解码（YOLO26 直回归头）。
 *
 * 配置（类名、类别数、候选数、阈值、是否启用几何过滤）全部来自 {@link ModelProfile}，
 * 切换模型时构造新的 Detector 并整体替换引用 —— 避免「新类名 + 旧类别数/旧阈值」的半更新中间态。
 *
 * 纯 Java，无 Android 依赖，可在 JVM 单元测试中直接验证。
 */
public final class Detector {

    /** 面积占整帧比例达到该值即视为“大框”。道闸模型正样本最大命中框 49.1%，bus.jpg 误报框 61.7%。 */
    private static final float BIG_BOX_AREA_RATIO = 0.5f;

    /** 大框必须达到的置信度；否则认定为“整图猜测”并丢弃（仅对内置表中开启该规则的模型生效）。 */
    private static final float BIG_BOX_MIN_SCORE = 0.7f;

    private static final Comparator<Detection> BY_SCORE_DESC = new Comparator<Detection>() {
        @Override
        public int compare(Detection a, Detection b) {
            return Float.compare(b.score, a.score);
        }
    };

    private final ModelProfile profile;
    private final int numClasses;
    private final int numAnchors;
    private final int maxDetections;
    private final String[] classNames;
    private final float confThreshold;
    private final float iouThreshold;
    private final boolean bigBoxFilterEnabled;

    public Detector(ModelProfile profile) {
        this.profile = profile;
        this.numClasses = profile.numClasses;
        this.numAnchors = profile.numAnchors;
        this.maxDetections = profile.maxDetections;
        this.classNames = profile.classNames;
        this.confThreshold = profile.confThreshold;
        this.iouThreshold = profile.iouThreshold;
        this.bigBoxFilterEnabled = profile.bigBoxFilterEnabled;
    }

    /** 道闸模型默认配置（等价于 Task 1 的行为，供旧用例与默认场景使用）。 */
    public Detector() {
        this(ModelProfile.barrierDefault());
    }

    public ModelProfile profile() {
        return profile;
    }

    /**
     * @param out   模型输出，形状 [1][4 + nc][anchors]
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
        for (int i = 0; i < numAnchors; i++) {
            int bestClass = 0;
            float bestScore = o[4][i];
            for (int c = 1; c < numClasses; c++) {
                float s = o[4 + c][i];
                if (s > bestScore) {
                    bestScore = s;
                    bestClass = c;
                }
            }
            if (bestScore < confThreshold) {
                continue;
            }
            // letterbox 空间 -> 原帧像素 -> 归一化
            float cx = (o[0][i] - padX) / scale;
            float cy = (o[1][i] - padY) / scale;
            float nw = o[2][i] / scale / srcW;
            float nh = o[3][i] / scale / srcH;
            // 几何过滤：覆盖大半画面的框必须足够自信，否则视为“整图猜测”丢弃
            if (bigBoxFilterEnabled && nw * nh >= BIG_BOX_AREA_RATIO && bestScore < BIG_BOX_MIN_SCORE) {
                continue;
            }
            String name = bestClass < classNames.length ? classNames[bestClass] : "class_" + bestClass;
            candidates.add(new Detection(bestClass, name, bestScore, cx / srcW, cy / srcH, nw, nh));
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
