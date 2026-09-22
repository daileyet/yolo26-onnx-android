package com.openthinks.onnx.example;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.io.BufferedReader;
import java.io.DataInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

/**
 * 在 JVM 上验证 App 的核心检测链路（与设备无关的部分）：
 *
 *   ARGB 整帧 -> Letterboxer(NCHW, /255) -> ONNX Runtime -> Detector(解码 + NMS) -> 归一化检测框
 *
 * 与真机链路唯一的差异是 YUV_420_888 -> ARGB 这一步（CameraImageConverter 依赖 Android）。
 *
 * 测试图片放在 src/test/resources/val/*.raw：格式为 [int width][int height][width*height 个 int ARGB]（大端）。
 * 之所以不用 JPG + ImageIO，是因为 Android 单元测试的编译引导类路径是 android.jar，
 * 其中不含 java.awt / javax.imageio。raw 文件由 datasets/barrier 的 val 图导出（脚本见 README）。
 */
public class DetectorPipelineTest {

    /** 工作目录被 Gradle 设为仓库根目录（见 app/build.gradle）。 */
    private static final File MODEL = new File("model/yolo26_barrier.onnx");

    /** img_0001=barrier_closed, img_0004=barrier_raising, img_0008=barrier_open，三个类别各覆盖一张。 */
    private static final String[] VAL_IDS = {"img_0001", "img_0004", "img_0008"};

    private static OnnxInferenceEngine engine;
    private static FloatBuffer input;
    private static final Letterboxer letterboxer = new Letterboxer();
    private static final Detector detector = new Detector();

    @BeforeClass
    public static void loadModel() throws Exception {
        assertTrue("模型文件不存在: " + MODEL.getAbsolutePath(), MODEL.isFile());
        engine = new OnnxInferenceEngine(Files.readAllBytes(MODEL.toPath()));
        input = engine.input();
        assertEquals("images", engine.inputName());
    }

    @AfterClass
    public static void closeModel() {
        if (engine != null) {
            engine.close();
        }
    }

    /** 端到端：val 图检测框必须与标注一致。 */
    @Test
    public void valImagesDetectionsMatchGroundTruth() throws Exception {
        for (String id : VAL_IDS) {
            Frame frame = loadFrame("/val/" + id + ".raw");
            List<GroundTruth> gts = loadGroundTruth("/val/" + id + ".txt");
            assertTrue(id + " 缺少标注", !gts.isEmpty());

            List<Detection> detections = detect(frame);
            System.out.println(id + " (" + frame.w + "x" + frame.h + ") -> " + detections);

            assertTrue(id + " 检测数量异常", detections.size() >= 1 && detections.size() <= 20);
            for (GroundTruth gt : gts) {
                Detection best = bestMatch(detections, gt);
                assertNotNull(id + " 未检出 " + gt.className(), best);
                float iou = Detector.iou(best, gt.asDetection());
                System.out.println(String.format("  %s cls=%s score=%.2f IoU=%.2f",
                        id, gt.className(), best.score, iou));
                assertTrue(id + " IoU 过低: " + iou + " (" + best + " vs " + gt + ")", iou > 0.7f);
                assertTrue(id + " 置信度未过默认阈值: " + best.score,
                        best.score > Detector.DEFAULT_CONF_THRESHOLD);
            }
        }
    }

    /** 非方形画面（16:9 裁剪）必须走 letterbox 且反算正确，否则框会整体纵向偏移。 */
    @Test
    public void letterboxHandlesNonSquareFrame() throws Exception {
        Frame full = loadFrame("/val/img_0001.raw");
        int cropTop = 100;
        int cropH = 360;
        int[] cropped = Arrays.copyOfRange(full.argb, cropTop * full.w, (cropTop + cropH) * full.w);
        Frame frame = new Frame(cropped, full.w, cropH);
        assertEquals("裁剪后比例应为 16:9", 1.777f, frame.w / (float) frame.h, 0.01f);

        List<GroundTruth> gts = loadGroundTruth("/val/img_0001.txt");
        assertEquals(1, gts.size());
        GroundTruth gt = gts.get(0);

        List<Detection> detections = detect(frame);
        System.out.println("16:9 crop -> " + detections);
        assertTrue("裁剪后未检出", !detections.isEmpty());

        // 标注换算到裁剪后坐标系
        Detection expected = new Detection(gt.classId, 1f, gt.cx,
                (gt.cy * full.h - cropTop) / cropH, gt.w, gt.h * full.h / cropH);

        float best = 0f;
        for (Detection d : detections) {
            if (d.classId == gt.classId) {
                best = Math.max(best, Detector.iou(d, expected));
            }
        }
        System.out.println(String.format("  letterbox IoU=%.2f (期望 > 0.7)", best));
        assertTrue("letterbox 反算错误，IoU=" + best, best > 0.7f);
    }

    /** 解码 + 同类 NMS 的确定性用例（不依赖模型）。 */
    @Test
    public void decodesBoxesAndSuppressesDuplicates() {
        float[][][] out = new float[1][7][8400];
        // anchor0: 中心 (320,320) 100x50，类别 1 置信度 0.90
        setAnchor(out, 0, 320f, 320f, 100f, 50f, new float[]{0f, 0.9f, 0f});
        // anchor1: 与 anchor0 高度重叠、置信度更低 -> 应被 NMS 抑制
        setAnchor(out, 1, 322f, 321f, 100f, 50f, new float[]{0f, 0.5f, 0f});
        // anchor2: 类别 0，位置不重叠 -> 保留
        setAnchor(out, 2, 600f, 600f, 40f, 40f, new float[]{0.6f, 0f, 0f});
        // anchor3: 全部低于阈值 -> 丢弃
        setAnchor(out, 3, 100f, 100f, 30f, 30f, new float[]{0.01f, 0.02f, 0.03f});

        List<Detection> detections = detector.detect(out, 1f, 0f, 0f, 640, 640);
        assertEquals(2, detections.size());
        assertEquals(1, detections.get(0).classId);
        assertEquals(0.9f, detections.get(0).score, 1e-5f);
        assertEquals(0.5f, detections.get(0).cx, 1e-4f);
        assertEquals(0.5f, detections.get(0).cy, 1e-4f);
        assertEquals(100f / 640f, detections.get(0).w, 1e-4f);
        assertEquals(50f / 640f, detections.get(0).h, 1e-4f);
        assertEquals(0, detections.get(1).classId);
    }

    /** 误报抑制规则：低置信度的大框（“整图猜测”）必须被丢弃，正常框与高分大框必须保留。 */
    @Test
    public void filtersLowConfidenceLargeBoxGuess() {
        // 1) bus.jpg 实测误报模式：整图大框(92%x67%)、分数 0.41 -> 丢弃（低于 conf 阈值）
        assertEquals(0, detectSingle(0.41f, 589f, 429f).size());

        // 2) 大框(面积约 60%)但分数只有 0.55 -> 丢弃（大框必须 >= 0.7）
        assertEquals(0, detectSingle(0.55f, 496f, 496f).size());

        // 3) 大框(面积约 60%)且分数 0.90 -> 保留（近景道闸场景）
        List<Detection> big = detectSingle(0.90f, 496f, 496f);
        assertEquals(1, big.size());
        assertEquals(0.9f, big.get(0).score, 1e-5f);

        // 4) 小框(面积约 4%)、分数 0.55 -> 保留（正常大小的目标不受大框规则影响）
        assertEquals(1, detectSingle(0.55f, 128f, 128f).size());

        // 5) 阈值边界：0.49 < 0.5 丢弃，0.51 保留
        assertEquals(0, detectSingle(0.49f, 128f, 128f).size());
        assertEquals(1, detectSingle(0.51f, 128f, 128f).size());
    }

    /** 构造单目标输出并跑默认 Detector（置信度/框尺寸均在 640 空间）。 */
    private static List<Detection> detectSingle(float score, float boxW, float boxH) {
        float[][][] out = new float[1][7][8400];
        setAnchor(out, 0, 320f, 320f, boxW, boxH, new float[]{0f, 0f, score});
        return new Detector().detect(out, 1f, 0f, 0f, 640, 640);
    }

    /** letterbox 几何与 NCHW / RGB 通道顺序（用纯色像素标定）。 */
    @Test
    public void letterboxGeometryAndNchwLayout() {
        Letterboxer lb = new Letterboxer();
        int srcW = 1280;
        int srcH = 720;
        int[] argb = new int[srcW * srcH];
        FloatBuffer buf = ByteBuffer.allocateDirect(3 * 640 * 640 * 4)
                .order(ByteOrder.nativeOrder())
                .asFloatBuffer();

        // 红点：源 (640,360) -> 目标 (320,320)   [dst = src*scale + pad]
        argb[360 * srcW + 640] = 0xFFFF0000;
        // 绿点：源 (0,0) -> 目标 (0,140)
        argb[0] = 0xFF00FF00;
        lb.fill(buf, argb, srcW, srcH);

        assertEquals(0.5f, lb.getScale(), 1e-6f);
        assertEquals(0f, lb.getPadX(), 1e-6f);
        assertEquals(140f, lb.getPadY(), 1e-6f);

        int plane = 640 * 640;
        int red = 320 * 640 + 320;
        assertEquals("R 通道", 1f, buf.get(red), 1e-6f);
        assertEquals("G 通道应为 0", 0f, buf.get(plane + red), 1e-6f);
        assertEquals("B 通道应为 0", 0f, buf.get(2 * plane + red), 1e-6f);

        int green = 140 * 640 + 0;
        assertEquals("R 通道应为 0", 0f, buf.get(green), 1e-6f);
        assertEquals("G 通道", 1f, buf.get(plane + green), 1e-6f);

        // 上下 pad 区域必须是 0
        int padded = 10 * 640 + 320;
        assertEquals(0f, buf.get(padded), 1e-6f);
        assertEquals(0f, buf.get(plane + padded), 1e-6f);
        assertEquals(0f, buf.get(2 * plane + padded), 1e-6f);
    }

    // ===== helpers =====

    private static void setAnchor(float[][][] out, int anchor, float cx, float cy, float w, float h,
                                  float[] classScores) {
        out[0][0][anchor] = cx;
        out[0][1][anchor] = cy;
        out[0][2][anchor] = w;
        out[0][3][anchor] = h;
        for (int c = 0; c < classScores.length; c++) {
            out[0][4 + c][anchor] = classScores[c];
        }
    }

    private static List<Detection> detect(Frame frame) throws Exception {
        letterboxer.fill(input, frame.argb, frame.w, frame.h);
        return detector.detect(engine.run(), letterboxer.getScale(),
                letterboxer.getPadX(), letterboxer.getPadY(), frame.w, frame.h);
    }

    private static Detection bestMatch(List<Detection> detections, GroundTruth gt) {
        Detection best = null;
        float bestIou = 0f;
        for (Detection d : detections) {
            if (d.classId != gt.classId) {
                continue;
            }
            float iou = Detector.iou(d, gt.asDetection());
            if (iou > bestIou) {
                bestIou = iou;
                best = d;
            }
        }
        return best;
    }

    private static final class Frame {
        final int[] argb;
        final int w;
        final int h;

        Frame(int[] argb, int w, int h) {
            this.argb = argb;
            this.w = w;
            this.h = h;
        }
    }

    /** 读取 [int width][int height][width*height 个 int ARGB]（大端）格式的测试图。 */
    private static Frame loadFrame(String resource) throws IOException {
        try (InputStream raw = DetectorPipelineTest.class.getResourceAsStream(resource)) {
            assertNotNull("测试资源缺失: " + resource, raw);
            DataInputStream in = new DataInputStream(raw);
            int w = in.readInt();
            int h = in.readInt();
            int[] argb = new int[w * h];
            for (int i = 0; i < argb.length; i++) {
                argb[i] = in.readInt();
            }
            return new Frame(argb, w, h);
        }
    }

    /** YOLO 格式标注：class cx cy w h（均已归一化）。 */
    private static final class GroundTruth {
        final int classId;
        final float cx;
        final float cy;
        final float w;
        final float h;

        GroundTruth(int classId, float cx, float cy, float w, float h) {
            this.classId = classId;
            this.cx = cx;
            this.cy = cy;
            this.w = w;
            this.h = h;
        }

        String className() {
            return Detector.CLASS_NAMES[classId];
        }

        Detection asDetection() {
            return new Detection(classId, 1f, cx, cy, w, h);
        }

        @Override
        public String toString() {
            return String.format("%s(%.3f,%.3f %.3fx%.3f)", className(), cx, cy, w, h);
        }
    }

    private static List<GroundTruth> loadGroundTruth(String resource) throws IOException {
        List<GroundTruth> list = new ArrayList<>();
        try (InputStream in = DetectorPipelineTest.class.getResourceAsStream(resource)) {
            assertNotNull("标注资源缺失: " + resource, in);
            BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
            String line;
            while ((line = reader.readLine()) != null) {
                String[] parts = line.trim().split("\\s+");
                if (parts.length < 5) {
                    continue;
                }
                list.add(new GroundTruth(Integer.parseInt(parts[0]),
                        Float.parseFloat(parts[1]), Float.parseFloat(parts[2]),
                        Float.parseFloat(parts[3]), Float.parseFloat(parts[4])));
            }
        }
        return list;
    }
}
