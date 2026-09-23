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
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.ListView;
import android.widget.Spinner;
import android.widget.TextView;

import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 实时目标检测 Demo 主界面。
 *
 * 数据流：
 *   camera2(相机线程) -> CameraImageConverter(YUV->摆正 ARGB) -> CameraFrameView(预览)
 *                                                          \-> Letterboxer(NCHW 张量) -> 推理线程(ORT) -> Detector(NMS) -> 预览叠加框
 *
 * 线程模型：
 *   - 相机线程：相机回调 + YUV 转换 + 预览刷新（重活，不阻塞 UI）；
 *   - 推理线程（单线程池）：串行执行 ORT 推理 **以及模型切换**；用 inferenceBusy 保证同一时刻只有一帧在跑，
 *     忙时直接丢帧（检测只看最新画面，不排队累积延迟）；
 *   - UI 线程：点击事件与绘制（通过 View.post / Handler 切回）。
 *
 * 模型切换（Task 3）：
 *   - 模型列表来自 assets 下所有 .onnx；Spinner 选择后触发切换；
 *   - 切换在推理线程上串行执行：暂停投帧 -> 加载新模型 -> 成功后再 close 旧会话 -> 整体替换
 *     engine/letterboxer/detector 引用（volatile）-> 恢复检测开关；
 *   - 类名、类别数、阈值全部来自模型（ModelProfile），因此换模型后标签与阈值自动跟随。
 */
public class MainActivity extends Activity implements CameraController.FrameListener,
        CameraController.StateListener {

    private static final int REQ_CAMERA_PERMISSION = 1001;

    /** 默认优先选用的模型（存在则优先），否则用 assets 中排序后的第一个。 */
    private static final String PREFERRED_DEFAULT_MODEL = "yolo26_barrier.onnx";

    /** 检测日志：最多保留行数与两行之间的最小间隔（行内容格式见 DetectionLogFormatter）。 */
    private static final int LOG_MAX_LINES = 300;
    private static final long LOG_MIN_INTERVAL_MS = 200L;

    private CameraFrameView frameView;
    private TextView statusView;
    private Button switchButton;
    private Button detectButton;
    private Spinner modelSpinner;
    private ListView detectionLogView;
    private TextView logCountView;

    /** 检测日志（仅 UI 线程访问）：行文本、适配器与去重/节流状态。 */
    private final List<String> detectionLogLines = new ArrayList<>();
    private ArrayAdapter<String> detectionLogAdapter;
    private final SimpleDateFormat logTimeFormat = new SimpleDateFormat("HH:mm:ss.SSS", Locale.ROOT);
    private long lastLogTimestampMs;
    private String lastLogSignature;

    private CameraController cameraController;
    private CameraImageConverter converter;
    private ExecutorService inferenceExecutor;

    /** 随模型一起整体替换的三件套（volatile：相机线程/推理线程都会读）。 */
    private volatile OnnxInferenceEngine engine;
    private volatile Letterboxer letterboxer;
    private volatile Detector detector;

    private String[] modelAssets = new String[0];
    private volatile String currentAsset;
    private volatile boolean modelLoading;

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
        modelSpinner = findViewById(R.id.model_spinner);
        detectionLogView = findViewById(R.id.detection_log);
        logCountView = findViewById(R.id.log_count);
        detectionLogAdapter = new ArrayAdapter<>(this, R.layout.item_detection_log,
                R.id.log_line, detectionLogLines);
        detectionLogView.setAdapter(detectionLogAdapter);

        converter = new CameraImageConverter();
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

        setupModelSpinner();
    }

    // ===== 模型列表与切换 =====

    /** 枚举 assets 下所有 .onnx 模型（以后往 assets 丢模型即可出现在列表里）。 */
    private String[] listModelAssets() {
        try {
            String[] files = getAssets().list("");
            List<String> models = new ArrayList<>();
            if (files != null) {
                for (String f : files) {
                    if (f.toLowerCase(Locale.ROOT).endsWith(".onnx")) {
                        models.add(f);
                    }
                }
            }
            Collections.sort(models);
            return models.toArray(new String[0]);
        } catch (IOException e) {
            setStatus("读取 assets 失败: " + e);
            return new String[]{PREFERRED_DEFAULT_MODEL};
        }
    }

    private void setupModelSpinner() {
        modelAssets = listModelAssets();
        if (modelAssets.length == 0) {
            setStatus("assets 下没有 .onnx 模型");
            return;
        }
        List<String> labels = new ArrayList<>();
        for (String asset : modelAssets) {
            labels.add(ModelProfile.shortLabel(asset));
        }
        ArrayAdapter<String> adapter = new ArrayAdapter<>(this, R.layout.item_model_spinner,
                R.id.model_name, labels);
        adapter.setDropDownViewResource(R.layout.item_model_spinner);
        modelSpinner.setAdapter(adapter);

        int defaultIndex = 0;
        for (int i = 0; i < modelAssets.length; i++) {
            if (modelAssets[i].equals(PREFERRED_DEFAULT_MODEL)) {
                defaultIndex = i;
                break;
            }
        }
        modelSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                if (position >= 0 && position < modelAssets.length) {
                    switchModel(modelAssets[position]);
                }
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
                // 无需处理
            }
        });
        modelSpinner.setSelection(defaultIndex, false);
        // Spinner 的初始选中不会触发 onItemSelected（首次布局判定为「选中项未变化」），
        // 所以这里显式加载一次默认模型；若 Spinner 之后仍回调，switchModel 的守卫会去重。
        switchModel(modelAssets[defaultIndex]);
    }

    /**
     * 切换模型：暂停投帧 -> 在推理线程上串行完成「加载新模型 + 关闭旧会话 + 整体替换配置」。
     * 失败时保留旧模型（新模型加载成功前不 close 旧的），并把检测开关复位。
     */
    private void switchModel(String assetName) {
        if (assetName == null || assetName.isEmpty() || modelLoading) {
            return;
        }
        if (assetName.equals(currentAsset) && engine != null) {
            return; // 重复选择同一模型
        }
        final boolean resumeDetecting = detecting;
        modelLoading = true;
        detecting = false;                       // 暂停投帧，避免用到正在替换的配置
        updateDetectButton();
        frameView.post(() -> frameView.clearDetections());
        setStatus("正在加载 " + assetName + " ...");
        try {
            inferenceExecutor.execute(() -> loadModel(assetName, resumeDetecting));
        } catch (Throwable t) {
            modelLoading = false;
            setStatus("模型切换任务提交失败: " + t);
        }
    }

    private void loadModel(String assetName, boolean resumeDetecting) {
        try {
            OnnxInferenceEngine newEngine = new OnnxInferenceEngine(this, assetName);
            ModelProfile profile = ModelProfile.of(assetName, newEngine.numClasses(),
                    newEngine.numAnchors(), newEngine.namesMetadata());
            Letterboxer newLetterboxer = new Letterboxer(newEngine.inputSize());
            Detector newDetector = new Detector(profile);

            OnnxInferenceEngine oldEngine = engine;
            // 整体替换：新配置一次性生效，不存在「新类名 + 旧类别数/旧阈值」的中间态
            letterboxer = newLetterboxer;
            detector = newDetector;
            engine = newEngine;
            currentAsset = assetName;
            if (oldEngine != null) {
                // 本任务与 run() 在同一线程上串行，因此此刻不可能有 run() 在执行
                oldEngine.close();
            }
            uiHandler.post(() -> {
                modelLoading = false;
                detectButton.setEnabled(true);
                detecting = resumeDetecting;
                updateDetectButton();
                resetLogState();
                setStatus("模型已就绪：" + profile.displayName + "（" + profile.numClasses
                        + " 类, conf " + profile.confThreshold + "）");
            });
        } catch (Throwable t) {
            final String message = "模型加载失败(" + assetName + "): " + t;
            uiHandler.post(() -> {
                modelLoading = false;
                detecting = false;
                updateDetectButton();
                setStatus(message);
            });
        }
    }

    // ===== 生命周期 =====

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
        // 先停推理线程并等它跑完当前这一帧（含可能的模型加载任务），再关 ORT 会话：
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
        OnnxInferenceEngine current = engine;
        if (current != null) {
            current.close();
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
        resetLogState();
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
        if (modelLoading) {
            setStatus("模型正在加载，请稍候");
            return;
        }
        if (engine == null) {
            setStatus("模型尚未加载完成");
            return;
        }
        detecting = !detecting;
        updateDetectButton();
        if (!detecting) {
            frameView.post(() -> frameView.clearDetections());
            resetLogState();
            setStatus("检测已关闭");
        } else {
            setStatus("检测已开启");
        }
    }

    private void updateDetectButton() {
        detectButton.setText(detecting ? R.string.detect_off : R.string.detect_on);
    }

    // ===== 帧处理与推理 =====

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

        // 三件套一次性快照：切换模型时整体替换，因此这里的引用始终自洽
        final OnnxInferenceEngine eng = engine;
        final Letterboxer lb = letterboxer;
        final Detector det = detector;
        if (!detecting || modelLoading || eng == null || lb == null || det == null) {
            return;
        }
        // 只有抢到 busy 标志时才填充输入缓冲，确保推理线程读取期间缓冲不被改写
        if (!inferenceBusy.compareAndSet(false, true)) {
            return;
        }
        lb.fill(eng.input(), argb, w, h);
        final float scale = lb.getScale();
        final float padX = lb.getPadX();
        final float padY = lb.getPadY();
        final long startMs = SystemClock.uptimeMillis();

        try {
            inferenceExecutor.execute(() -> runInference(eng, det, scale, padX, padY, w, h, startMs));
        } catch (Throwable t) {
            inferenceBusy.set(false);
        }
    }

    private void runInference(OnnxInferenceEngine eng, Detector det,
                              float scale, float padX, float padY,
                              int srcW, int srcH, long startMs) {
        try {
            if (eng != engine) {
                // 期间已切换模型：丢弃这帧旧会话的结果（旧会话可能已被关闭，绝不能调 run()）
                return;
            }
            float[][][] output = eng.run();
            List<Detection> detections = det.detect(output, scale, padX, padY, srcW, srcH);
            if (detections == null) {
                detections = Collections.emptyList();
            }
            if (eng != engine) {
                return; // 结果属于上一代模型，丢弃
            }
            final List<Detection> result = detections;
            final float ms = SystemClock.uptimeMillis() - startMs;
            final float fps = previewFps;
            final String modelLabel = det.profile().displayName;
            uiHandler.post(() -> {
                frameView.setDetections(result, ms, fps);
                appendDetectionLog(modelLabel, result, ms);
            });
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

    /**
     * 追加一行检测日志（仅 UI 线程调用）。
     * 规则：无目标不写；内容与上一条相同不写；两条之间至少间隔 LOG_MIN_INTERVAL_MS；最多保留 LOG_MAX_LINES 行。
     */
    private void appendDetectionLog(String modelLabel, List<Detection> detections, float inferenceMs) {
        if (detections.isEmpty()) {
            return;
        }
        long now = SystemClock.uptimeMillis();
        String body = DetectionLogFormatter.body(modelLabel, detections, inferenceMs);
        if (body.equals(lastLogSignature) || now - lastLogTimestampMs < LOG_MIN_INTERVAL_MS) {
            return;
        }
        lastLogSignature = body;
        lastLogTimestampMs = now;

        detectionLogLines.add(logTimeFormat.format(new Date()) + " | " + body);
        while (detectionLogLines.size() > LOG_MAX_LINES) {
            detectionLogLines.remove(0);
        }
        detectionLogAdapter.notifyDataSetChanged();
        detectionLogView.setSelection(detectionLogLines.size() - 1);   // 始终滚动到最新一行
        logCountView.setText(getString(R.string.log_count_format, detections.size()));
    }

    /** 重置日志去重状态：切换模型 / 关闭检测 / 停相机时调用，保证下一批结果能写入。 */
    private void resetLogState() {
        lastLogSignature = null;
        lastLogTimestampMs = 0L;
    }

    private void setStatus(String message) {
        statusView.setText(message);
    }
}
