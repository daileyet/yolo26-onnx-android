package com.openthinks.onnx.example;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * ModelProfile 的类名解析与阈值选择规则（纯 JVM，不加载模型）。
 *
 * 覆盖 Task 3 的核心要求：类名/类别数跟随模型，而不是全局写死。
 */
public class ModelProfileTest {

    /** 取自 yolo26n.onnx 的真实 names 片段（含带空格的类名、编号不连续）。 */
    private static final String COCO_SAMPLE =
            "{0: 'person', 1: 'bicycle', 9: 'traffic light', 78: 'hair drier', 79: 'toothbrush'}";

    @Test
    public void parsesClassNamesWithSpacesAndGaps() {
        String[] names = ModelProfile.parseClassNames(COCO_SAMPLE, 80);
        assertEquals(80, names.length);
        assertEquals("person", names[0]);
        assertEquals("bicycle", names[1]);
        assertEquals("traffic light", names[9]);
        assertEquals("hair drier", names[78]);
        assertEquals("toothbrush", names[79]);
        // 未在串里出现的编号回退为 class_N（不抛异常、不越界）
        assertEquals("class_50", names[50]);
    }

    @Test
    public void parsesBarrierClassNames() {
        String[] names = ModelProfile.parseClassNames(ModelProfile.BARRIER_NAMES_METADATA, 3);
        assertEquals(3, names.length);
        assertEquals("barrier_closed", names[0]);
        assertEquals("barrier_open", names[1]);
        assertEquals("barrier_raising", names[2]);
    }

    @Test
    public void fallsBackToClassNWhenMetadataMissingOrBroken() {
        String[] fromNull = ModelProfile.parseClassNames(null, 2);
        assertEquals("class_0", fromNull[0]);
        assertEquals("class_1", fromNull[1]);

        String[] fromEmpty = ModelProfile.parseClassNames("", 1);
        assertEquals("class_0", fromEmpty[0]);

        // 越界编号与乱码都必须被忽略
        String[] broken = ModelProfile.parseClassNames("{99: 'out_of_range', 0: 'ok', garbage", 2);
        assertEquals("ok", broken[0]);
        assertEquals("class_1", broken[1]);
    }

    @Test
    public void builtInTableProvidesPerModelThresholds() {
        ModelProfile barrier = ModelProfile.of("yolo26_barrier.onnx", 3, 8400,
                ModelProfile.BARRIER_NAMES_METADATA);
        assertEquals("道闸模型(3类)", barrier.displayName);
        assertEquals(0.5f, barrier.confThreshold, 1e-6f);
        assertEquals(0.45f, barrier.iouThreshold, 1e-6f);
        assertEquals(20, barrier.maxDetections);
        assertTrue("道闸模型应启用大框低分规则", barrier.bigBoxFilterEnabled);

        ModelProfile coco = ModelProfile.of("yolo26n.onnx", 80, 8400, COCO_SAMPLE);
        assertEquals("通用模型(COCO 80类)", coco.displayName);
        assertEquals(0.25f, coco.confThreshold, 1e-6f);
        assertEquals(30, coco.maxDetections);
        assertFalse("COCO 模型不应启用大框低分规则（会误杀正常输出）", coco.bigBoxFilterEnabled);
    }

    @Test
    public void unknownModelsUseDefaultRules() {
        // 类数少 -> 严阈值；启发式过滤只对内置表内模型开启
        ModelProfile small = ModelProfile.of("my_small.onnx", 4, 8400, null);
        assertEquals(0.5f, small.confThreshold, 1e-6f);
        assertEquals(20, small.maxDetections);
        assertFalse(small.bigBoxFilterEnabled);
        assertEquals("my_small(4类)", small.displayName);

        // 类数多 -> 通用阈值
        ModelProfile general = ModelProfile.of("my_general.onnx", 80, 8400, null);
        assertEquals(0.25f, general.confThreshold, 1e-6f);
        assertEquals(30, general.maxDetections);
        assertFalse(general.bigBoxFilterEnabled);
    }

    @Test
    public void shortLabelStripsExtensionForUnknownModels() {
        assertEquals("道闸模型(3类)", ModelProfile.shortLabel("yolo26_barrier.onnx"));
        assertEquals("通用模型(COCO 80类)", ModelProfile.shortLabel("yolo26n.onnx"));
        assertEquals("some_model", ModelProfile.shortLabel("some_model.onnx"));
        assertEquals("noextension", ModelProfile.shortLabel("noextension"));
    }

    @Test
    public void barrierDefaultMatchesHistoricalBehaviour() {
        ModelProfile profile = ModelProfile.barrierDefault();
        assertEquals(3, profile.numClasses);
        assertEquals(8400, profile.numAnchors);
        assertEquals(0.5f, profile.confThreshold, 1e-6f);
        assertTrue(profile.bigBoxFilterEnabled);
        assertEquals("barrier_closed", profile.classNames[0]);
    }
}
