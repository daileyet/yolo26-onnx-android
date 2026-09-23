package com.openthinks.onnx.example;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.View;

import java.util.Collections;
import java.util.List;

/**
 * 预览 + 检测框叠加的自绘 View。
 *
 * 设计要点：预览位图与检测框使用同一个坐标系（摆正后的整帧归一化坐标），
 * 因此不存在 TextureView transform 与检测框坐标系不一致导致的错位问题。
 *
 * 线程模型：updateFrame() 由相机线程调用（内部加锁后 setPixels），onDraw() 在 UI 线程，
 * 两者通过 frameLock 互斥，避免绘制期间位图被改写。
 */
public class CameraFrameView extends View {

    /**
     * 缩放缓存的放大上限（相对「完整显示的等比缩放」）。用于「按宽度铺满」策略：
     * 铺满优先，但裁切不超过该倍数，避免在平板等极端屏幕比例下把画面裁到只剩中间一条。
     */
    private static final float MAX_CROP_FACTOR = 1.4f;

    private final Object frameLock = new Object();
    private final Matrix bitmapMatrix = new Matrix();
    private final Paint bitmapPaint = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final Paint boxPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint labelPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF contentRect = new RectF();
    private final RectF boxRect = new RectF();
    private final RectF labelRect = new RectF();

    private Bitmap frameBitmap;
    private int frameW;
    private int frameH;

    private List<Detection> detections = Collections.emptyList();
    private float inferenceMs = -1f;
    private float previewFps = -1f;

    public CameraFrameView(Context context) {
        super(context);
        init();
    }

    public CameraFrameView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    private void init() {
        boxPaint.setStyle(Paint.Style.STROKE);
        boxPaint.setStrokeWidth(5f);
        textPaint.setTextSize(34f);
        textPaint.setColor(Color.WHITE);
        textPaint.setShadowLayer(5f, 2f, 2f, Color.BLACK);
        labelPaint.setStyle(Paint.Style.FILL);
    }

    /** 相机线程调用：刷新预览帧。 */
    public void updateFrame(int[] argb, int w, int h) {
        if (argb == null || w <= 0 || h <= 0 || argb.length < w * h) {
            return;
        }
        synchronized (frameLock) {
            if (frameBitmap == null || frameW != w || frameH != h) {
                // 注意：这里刻意不 recycle() 旧位图。硬件加速下 DisplayList 仍可能持有它，
                // 由 RenderThread 异步使用；recycle() 会立即释放原生像素内存，导致踩已释放内存的原生崩溃。
                // 直接替换引用并交给 GC 更安全（尺寸变化只发生在切换摄像头/分辨率变化时）。
                frameBitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
                frameW = w;
                frameH = h;
            }
            frameBitmap.setPixels(argb, 0, w, 0, 0, w, h);
        }
        postInvalidate();
    }

    /** UI 线程或 post 调用：更新检测结果。 */
    public void setDetections(List<Detection> detections, float inferenceMs, float previewFps) {
        this.detections = detections;
        this.inferenceMs = inferenceMs;
        this.previewFps = previewFps;
        postInvalidate();
    }

    public void clearDetections() {
        this.detections = Collections.emptyList();
        this.inferenceMs = -1f;
        postInvalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float cw = frameW;
        float ch = frameH;
        synchronized (frameLock) {
            if (frameBitmap != null && cw > 0 && ch > 0) {
                float fitScale = Math.min(getWidth() / cw, getHeight() / ch);
                float fillScale = Math.max(getWidth() / cw, getHeight() / ch);
                // 优先铺满（宽度方向铺满、垂直居中裁切），但裁切不超过 MAX_CROP_FACTOR 倍
                float s = Math.min(fillScale, fitScale * MAX_CROP_FACTOR);
                float dx = (getWidth() - cw * s) / 2f;
                float dy = (getHeight() - ch * s) / 2f;
                bitmapMatrix.setScale(s, s);
                bitmapMatrix.postTranslate(dx, dy);
                canvas.drawBitmap(frameBitmap, bitmapMatrix, bitmapPaint);
                // contentRect 允许为负/越界（超出 View 的部分由 Canvas 自动裁掉），检测框与画面用同一映射
                contentRect.set(dx, dy, dx + cw * s, dy + ch * s);
            } else {
                contentRect.set(0f, 0f, getWidth(), getHeight());
            }
        }

        for (Detection d : detections) {
            // 配色按 classId 生成（黄金角色相），类别数不受限：COCO 80 类也不会撞色
            int color = Color.HSVToColor(new float[]{
                    LabelPalette.hueFor(d.classId), LabelPalette.SATURATION, LabelPalette.VALUE});
            boxPaint.setColor(color);
            float left = contentRect.left + d.left() * contentRect.width();
            float top = contentRect.top + d.top() * contentRect.height();
            float right = contentRect.left + d.right() * contentRect.width();
            float bottom = contentRect.top + d.bottom() * contentRect.height();
            canvas.drawRect(left, top, right, bottom, boxPaint);

            String label = d.className() + " " + String.format("%.2f", d.score);
            float textW = textPaint.measureText(label);
            float labelH = textPaint.getTextSize() + 12f;
            float labelTop = Math.max(0f, top - labelH);
            labelRect.set(left, labelTop, left + textW + 16f, labelTop + labelH);
            labelPaint.setColor(color);
            canvas.drawRect(labelRect, labelPaint);
            canvas.drawText(label, left + 8f, labelTop + labelH - 8f, textPaint);
        }

        if (previewFps > 0f) {
            String info = String.format("预览 %.1ffps", previewFps);
            if (inferenceMs > 0f) {
                info += String.format("  推理 %.0fms", inferenceMs);
            }
            // 左下角绘制：上方留给状态文字与模型下拉框，下方留给按钮栏
            canvas.drawText(info, 24f, Math.max(80f, getHeight() - 60f), textPaint);
        }
    }
}
