package com.openthinks.onnx.example;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * 摆正旋转映射的正确性验证：
 *   1. 双射：旋转后每个目标像素都对应且仅对应一个源像素（覆盖完整、不重复 → 不会花屏/缺角）；
 *   2. 方向语义：顺时针 90 度后，源的左上角应出现在目标的右上角（反之则是逆时针，整帧会颠倒）。
 */
public class RotationMappingTest {

    private static final int SRC_W = 4;
    private static final int SRC_H = 3;

    @Test
    public void mappingIsBijectiveForAllRotations() {
        for (int rotation : new int[]{0, 90, 180, 270}) {
            int dstW = RotationMapping.rotatedWidth(rotation, SRC_W, SRC_H);
            int dstH = RotationMapping.rotatedHeight(rotation, SRC_W, SRC_H);
            assertEquals("旋转后像素总数必须不变", SRC_W * SRC_H, dstW * dstH);

            boolean[] visited = new boolean[SRC_W * SRC_H];
            for (int dy = 0; dy < dstH; dy++) {
                for (int dx = 0; dx < dstW; dx++) {
                    int sx = RotationMapping.srcX(rotation, dx, dy, SRC_W, SRC_H);
                    int sy = RotationMapping.srcY(rotation, dx, dy, SRC_W, SRC_H);
                    assertTrue("越界 sx=" + sx + " sy=" + sy + " rot=" + rotation,
                            sx >= 0 && sx < SRC_W && sy >= 0 && sy < SRC_H);
                    int index = sy * SRC_W + sx;
                    assertTrue("源像素被重复映射 (" + sx + "," + sy + ") rot=" + rotation, !visited[index]);
                    visited[index] = true;
                }
            }
            for (int i = 0; i < visited.length; i++) {
                assertTrue("源像素未被映射: " + i + " rot=" + rotation, visited[i]);
            }
        }
    }

    @Test
    public void rotationDirectionSemantics() {
        // 0 度：左上角保持在左上角
        assertEquals(0, RotationMapping.srcX(0, 0, 0, SRC_W, SRC_H));
        assertEquals(0, RotationMapping.srcY(0, 0, 0, SRC_W, SRC_H));

        // 顺时针 90 度：源的左上角(0,0) 出现在目标的右上角
        int dstW90 = RotationMapping.rotatedWidth(90, SRC_W, SRC_H);
        assertEquals(SRC_H, dstW90);
        assertEquals(0, RotationMapping.srcX(90, dstW90 - 1, 0, SRC_W, SRC_H));
        assertEquals(0, RotationMapping.srcY(90, dstW90 - 1, 0, SRC_W, SRC_H));

        // 顺时针 270 度（等价逆时针 90 度）：源的左上角出现在目标的左下角
        int dstH270 = RotationMapping.rotatedHeight(270, SRC_W, SRC_H);
        assertEquals(SRC_W, dstH270);
        assertEquals(0, RotationMapping.srcX(270, 0, dstH270 - 1, SRC_W, SRC_H));
        assertEquals(0, RotationMapping.srcY(270, 0, dstH270 - 1, SRC_W, SRC_H));

        // 180 度：源的左上角出现在目标的右下角
        assertEquals(0, RotationMapping.srcX(180, SRC_W - 1, SRC_H - 1, SRC_W, SRC_H));
        assertEquals(0, RotationMapping.srcY(180, SRC_W - 1, SRC_H - 1, SRC_W, SRC_H));
    }
}
