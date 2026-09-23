package com.openthinks.onnx.example;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * 类别配色（黄金角色相）的性质：
 *   1. 同一 classId 恒定（跨帧/跨调用一致）；
 *   2. 落在 [0,360) 内；
 *   3. 类别变多（如 COCO 80 类）时两两颜色仍然可分辨（最小色相间隔 > 2°）。
 */
public class LabelPaletteTest {

    @Test
    public void hueIsStableAndInRange() {
        assertEquals(0f, LabelPalette.hueFor(0), 1e-4f);
        assertEquals(LabelPalette.GOLDEN_ANGLE_DEGREES, LabelPalette.hueFor(1), 1e-3f);
        for (int classId = 0; classId < 200; classId++) {
            float hue = LabelPalette.hueFor(classId);
            assertTrue("色相越界: " + hue, hue >= 0f && hue < 360f);
            assertEquals("同一 classId 必须恒定", hue, LabelPalette.hueFor(classId), 1e-6f);
        }
    }

    @Test
    public void colorsRemainDistinguishableFor80Classes() {
        int classes = 80;
        float[] hues = new float[classes];
        for (int i = 0; i < classes; i++) {
            hues[i] = LabelPalette.hueFor(i);
        }
        // 两两不重复
        for (int i = 0; i < classes; i++) {
            for (int j = i + 1; j < classes; j++) {
                assertTrue("classId " + i + " 与 " + j + " 色相相同", Math.abs(hues[i] - hues[j]) > 1e-4f);
            }
        }
        // 最小环状间隔（单位为度）——旧实现 classId%3 在这里会得到 0
        float minGap = 360f;
        for (int i = 0; i < classes; i++) {
            for (int j = i + 1; j < classes; j++) {
                float diff = Math.abs(hues[i] - hues[j]);
                diff = Math.min(diff, 360f - diff);
                minGap = Math.min(minGap, diff);
            }
        }
        System.out.println(String.format("80 类最小色相间隔 = %.2f 度", minGap));
        assertTrue("80 类色相间隔过小: " + minGap, minGap > 2f);
    }
}
