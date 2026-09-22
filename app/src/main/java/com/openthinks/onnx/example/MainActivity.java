package com.openthinks.onnx.example;

import android.Manifest;
import android.app.Activity;
import android.content.pm.PackageManager;
import android.media.Image;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;

import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 道闸检测 Demo 主界面。
 *
 * 数据流：
 *   camera2(相机线程) -> CameraImageConverter(YUV->摆正 ARGB) -> CameraFrameView(预览)
 *                                                          \-> Letterboxer(NCHW 张量) -> 推理线程(ORT) -> Detector(NMS) -> 预览叠加框
 *
 * 线程模型：
 *   - 相机线程：相机回调 + YUV 转换 + 预览刷新（重活，不阻塞 UI）；
 *   - 推理线程：单线程串行执行 ORT 推理；用 inferenceBusy 保证同一时刻只有一帧在跑，
 *     忙时直接丢帧（检测只看最新画面，不排队累积延迟）；
 *   - UI 线程：点击事件与绘制（通过 View.post 切回）。
 */
public class MainActivity extends Activity implements CameraController.FrameListener,
        CameraController.StateListener {

    private static final int REQ_CAMERA_PERMISSION = 1001;
//    private static final String MODEL_ASSET = "yolo26_barrier.onnx";
    private static final String MODEL_ASSET = "yolo26n.onnx";

    private CameraFrameView frameView;
    private TextView statusView;
    private Button switchButton;
    private Button detectButton;

    private CameraController cameraController;
    private CameraImageConverter converter;
    private Letterboxer letterboxer;
    private Detector detector;
    private OnnxInferenceEngine engine;
    private ExecutorService inferenceExecutor;

    private final Handler uiHandler = new Handler(Looper.getMainLooper());
    private final AtomicBoolean inferenceBusy = new AtomicBoolean(false);

    private volatile boolean detecting;
    private volatile float previewFps;
    private long lastFrameTimestampMs;
    private boolean frontCamera;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        frameView = findViewById(R.id.camera_frame_view);
        statusView = findViewById(R.id.status_text);
        switchButton = findViewById(R.id.switch_camera_button);
        detectButton = findViewById(R.id.detect_button);

        converter = new CameraImageConverter();
        letterboxer = new Letterboxer();
        detector = new Detector();
        inferenceExecutor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "yolo-inference");
            t.setPriority(Thread.NORM_PRIORITY);
            return t;
        });
        cameraController = new CameraController(this);

        detectButton.setEnabled(false);
        detectButton.setOnClickListener(v -> toggleDetecting());
        switchButton.setOnClickListener(v -> switchCamera());
        switchButton.setEnabled(cameraController.hasBothCameras());
        if (!cameraController.hasBothCameras()) {
            setStatus("仅检测到一路摄像头，无法切换");
        }

        loadModelAsync();
    }

    private void loadModelAsync() {
        setStatus("正在加载模型 " + MODEL_ASSET + " ...");
        new Thread(() -> {
            try {
                OnnxInferenceEngine loaded = new OnnxInferenceEngine(this, MODEL_ASSET);
                engine = loaded;
                uiHandler.post(() -> {
                    detectButton.setEnabled(true);
                    setStatus("模型已就绪，点击「开启检测」开始");
                });
            } catch (Throwable t) {
                uiHandler.post(() -> setStatus("模型加载失败: " + t));
            }
        }, "yolo-model-loader").start();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.CAMERA}, REQ_CAMERA_PERMISSION);
            return;
        }
        startCamera();
    }

    @Override
    protected void onPause() {
        stopCamera();
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        detecting = false;
        // 先停推理线程并等它跑完当前这一帧，再关 ORT 会话：
        // 否则可能出现“会话已 close、推理仍在 run()”的原生崩溃。
        inferenceExecutor.shutdown();
        try {
            if (!inferenceExecutor.awaitTermination(3, TimeUnit.SECONDS)) {
                inferenceExecutor.shutdownNow();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            inferenceExecutor.shutdownNow();
        }
        // release() 内部会与相机线程的帧处理互斥，保证不会在读 Image 时释放原生缓冲
        cameraController.release();
        if (engine != null) {
            engine.close();
        }
        super.onDestroy();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_CAMERA_PERMISSION) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                startCamera();
            } else {
                setStatus("未授予相机权限，无法预览");
            }
        }
    }

    private void startCamera() {
        String cameraId = frontCamera ? cameraController.getFrontCameraId()
                : cameraController.getBackCameraId();
        if (cameraId == null) {
            cameraId = cameraController.getBackCameraId() != null
                    ? cameraController.getBackCameraId() : cameraController.getFrontCameraId();
            frontCamera = cameraId != null && cameraId.equals(cameraController.getFrontCameraId());
        }
        if (cameraId == null) {
            setStatus("未找到可用摄像头");
            return;
        }
        previewFps = 0f;
        lastFrameTimestampMs = 0L;
        cameraController.start(cameraId, this, this);
    }

    private void stopCamera() {
        detecting = false;
        updateDetectButton();
        frameView.clearDetections();
        cameraController.stop();
    }

    private void switchCamera() {
        if (!cameraController.hasBothCameras()) {
            return;
        }
        frontCamera = !frontCamera;
        cameraController.stop();
        frameView.post(() -> frameView.clearDetections());
        startCamera();
        setStatus(frontCamera ? "切换到前置摄像头" : "切换到后置摄像头");
    }

    private void toggleDetecting() {
        if (engine == null) {
            setStatus("模型尚未加载完成");
            return;
        }
        detecting = !detecting;
        updateDetectButton();
        if (!detecting) {
            frameView.post(() -> frameView.clearDetections());
            setStatus("检测已关闭");
        } else {
            setStatus("检测已开启");
        }
    }

    private void updateDetectButton() {
        detectButton.setText(detecting ? R.string.detect_off : R.string.detect_on);
    }

    @Override
    public void onFrame(Image image) {
        CameraController controller = cameraController;
        String cameraId = controller.getOpenedCameraId();
        if (cameraId == null) {
            return;
        }
        int rotation = controller.getRotationDegrees(cameraId);
        int[] argb = converter.convert(image, rotation);
        final int w = converter.getWidth();
        final int h = converter.getHeight();

        long now = SystemClock.uptimeMillis();
        if (lastFrameTimestampMs != 0L) {
            float instant = 1000f / Math.max(1L, now - lastFrameTimestampMs);
            previewFps = previewFps <= 0f ? instant : previewFps * 0.85f + instant * 0.15f;
        }
        lastFrameTimestampMs = now;

        frameView.updateFrame(argb, w, h);

        if (!detecting || engine == null) {
            return;
        }
        // 只有抢到 busy 标志时才填充输入缓冲，确保推理线程读取期间缓冲不被改写
        if (!inferenceBusy.compareAndSet(false, true)) {
            return;
        }
        letterboxer.fill(engine.input(), argb, w, h);
        final float scale = letterboxer.getScale();
        final float padX = letterboxer.getPadX();
        final float padY = letterboxer.getPadY();
        final long startMs = SystemClock.uptimeMillis();

        try {
            inferenceExecutor.execute(() -> runInference(scale, padX, padY, w, h, startMs));
        } catch (Throwable t) {
            inferenceBusy.set(false);
        }
    }

    private void runInference(float scale, float padX, float padY, int srcW, int srcH, long startMs) {
        try {
            float[][][] output = engine.run();
            List<Detection> detections = detector.detect(output, scale, padX, padY, srcW, srcH);
            if (detections == null) {
                detections = Collections.emptyList();
            }
            final List<Detection> result = detections;
            final float ms = SystemClock.uptimeMillis() - startMs;
            final float fps = previewFps;
            uiHandler.post(() -> frameView.setDetections(result, ms, fps));
        } catch (Throwable t) {
            uiHandler.post(() -> {
                frameView.clearDetections();
                setStatus("推理失败: " + t);
            });
        } finally {
            inferenceBusy.set(false);
        }
    }

    @Override
    public void onCameraState(String message, boolean error) {
        uiHandler.post(() -> setStatus((error ? "[错误] " : "") + message));
    }

    private void setStatus(String message) {
        statusView.setText(message);
    }
}
