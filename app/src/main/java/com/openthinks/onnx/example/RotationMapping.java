package com.openthinks.onnx.example;

/**
 * 摆正旋转的坐标映射。
 *
 * 语义：dst 是「把传感器原始帧沿顺时针方向旋转 rotation 度」后的正立图像；
 * 因此 dst(dx, dy) 的像素取自 src(srcX, srcY)。
 *
 * 纯 Java，无 Android 依赖 —— 旋转是最容易搞错的一环（90/270 互换会导致整帧横竖颠倒），
 * 因此把它单独抽出来做双向（bijection）与方向语义的单元测试。
 */
public final class RotationMapping {

    private RotationMapping() {
    }

    /** 旋转后的图像宽度。 */
    public static int rotatedWidth(int rotation, int srcW, int srcH) {
        return (rotation == 90 || rotation == 270) ? srcH : srcW;
    }

    /** 旋转后的图像高度。 */
    public static int rotatedHeight(int rotation, int srcW, int srcH) {
        return (rotation == 90 || rotation == 270) ? srcW : srcH;
    }

    /** dst(dx, dy) -> src 列号。 */
    public static int srcX(int rotation, int dx, int dy, int srcW, int srcH) {
        switch (rotation) {
            case 90:
                return dy;
            case 180:
                return srcW - 1 - dx;
            case 270:
                return srcW - 1 - dy;
            default:
                return dx;
        }
    }

    /** dst(dx, dy) -> src 行号。 */
    public static int srcY(int rotation, int dx, int dy, int srcW, int srcH) {
        switch (rotation) {
            case 90:
                return srcH - 1 - dx;
            case 180:
                return srcH - 1 - dy;
            case 270:
                return dx;
            default:
                return dy;
        }
    }
}
