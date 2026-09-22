package com.openthinks.onnx.example;

import android.media.Image;

import java.nio.ByteBuffer;

/**
 * 把 camera2 的 YUV_420_888 帧转换成「摆正后的 ARGB 整帧」。
 *
 * 约定：
 *   1. 输出坐标系的含义是「相对相机传感器输出的画面，顺时针旋转 rotationDegrees 后的正立图像」；
 *   2. rotationDegrees 由 CameraController.getRotationDegrees() 依据 sensorOrientation 与屏幕旋转计算；
 *   3. YUV -> RGB 使用 BT.601 全范围（Android camera 输出的标准形态），整数运算避免浮点开销。
 *
 * 该类的输出（ARGB 整帧）同时用于两处：屏幕预览与 Letterboxer 的检测输入，
 * 这样预览与检测框天然共用同一坐标系，不会错位。
 */
public final class CameraImageConverter {

    private byte[] yPlane;
    private byte[] uPlane;
    private byte[] vPlane;
    private int[] rgb;

    private int srcW;
    private int srcH;
    private int chromaW;
    private int chromaH;

    private int width;
    private int height;

    /**
     * @param image           camera2 输出的 YUV_420_888 帧
     * @param rotationDegrees 需要顺时针旋转的角度：0 / 90 / 180 / 270
     * @return 摆正后的整帧像素（0xAARRGGBB），长度为 width*height；缓冲会被下一帧复用
     */
    public int[] convert(Image image, int rotationDegrees) {
        srcW = image.getWidth();
        srcH = image.getHeight();
        chromaW = (srcW + 1) / 2;
        chromaH = (srcH + 1) / 2;

        int yLen = srcW * srcH;
        int cLen = chromaW * chromaH;
        if (yPlane == null || yPlane.length < yLen) {
            yPlane = new byte[yLen];
        }
        if (uPlane == null || uPlane.length < cLen) {
            uPlane = new byte[cLen];
            vPlane = new byte[cLen];
        }

        Image.Plane[] planes = image.getPlanes();
        copyPlane(planes[0], yPlane, srcW, srcH);
        copyPlane(planes[1], uPlane, chromaW, chromaH);
        copyPlane(planes[2], vPlane, chromaW, chromaH);

        width = RotationMapping.rotatedWidth(rotationDegrees, srcW, srcH);
        height = RotationMapping.rotatedHeight(rotationDegrees, srcW, srcH);
        int need = width * height;
        if (rgb == null || rgb.length < need) {
            rgb = new int[need];
        }

        int dst = 0;
        for (int dy = 0; dy < height; dy++) {
            for (int dx = 0; dx < width; dx++) {
                int sx = RotationMapping.srcX(rotationDegrees, dx, dy, srcW, srcH);
                int sy = RotationMapping.srcY(rotationDegrees, dx, dy, srcW, srcH);
                int yy = yPlane[sy * srcW + sx] & 0xFF;
                int ci = (sy >> 1) * chromaW + (sx >> 1);
                int uu = uPlane[ci] & 0xFF;
                int vv = vPlane[ci] & 0xFF;

                int r = yy + ((1436 * (vv - 128)) >> 10);
                int g = yy - ((352 * (uu - 128) + 731 * (vv - 128)) >> 10);
                int b = yy + ((1815 * (uu - 128)) >> 10);
                if (r < 0) {
                    r = 0;
                } else if (r > 255) {
                    r = 255;
                }
                if (g < 0) {
                    g = 0;
                } else if (g > 255) {
                    g = 255;
                }
                if (b < 0) {
                    b = 0;
                } else if (b > 255) {
                    b = 255;
                }
                rgb[dst++] = 0xFF000000 | (r << 16) | (g << 8) | b;
            }
        }
        return rgb;
    }

    public int getWidth() {
        return width;
    }

    public int getHeight() {
        return height;
    }

    /**
     * 逐行拷贝平面数据，正确处理 rowStride / pixelStride。
     * YUV_420_888 的 plane rowStride 通常大于宽度（含 padding），U/V 的 pixelStride 可能是 1 或 2。
     */
    private static void copyPlane(Image.Plane plane, byte[] dst, int w, int h) {
        ByteBuffer buf = plane.getBuffer();
        buf.rewind();
        int rowStride = plane.getRowStride();
        int pixelStride = plane.getPixelStride();
        if (pixelStride == 1 && rowStride == w) {
            buf.get(dst, 0, w * h);
            return;
        }
        int idx = 0;
        for (int row = 0; row < h; row++) {
            int base = row * rowStride;
            for (int col = 0; col < w; col++) {
                dst[idx++] = buf.get(base + col * pixelStride);
            }
        }
    }
}
