package com.openthinks.onnx.example;

import android.content.Context;
import android.graphics.ImageFormat;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CameraMetadata;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.Image;
import android.media.ImageReader;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Log;
import android.util.Size;
import android.view.Display;
import android.view.Surface;
import android.view.WindowManager;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * camera2 原生 API 封装：负责枚举摄像头、开/关相机、建立只含 YUV ImageReader 的会话，
 * 并把采集帧回调给 FrameListener（回调发生在相机线程上）。
 *
 * 为什么只用 ImageReader 而不加 TextureView/SurfaceTexture：
 *   预览与检测都由同一份 YUV 帧渲染（见 CameraImageConverter），坐标系唯一，
 *   避免 TextureView 的 transform 矩阵与检测框坐标系不一致导致的框位错位。
 *
 * 线程模型：所有 camera2 回调都在内部 HandlerThread 上执行；UI 线程只负责点击事件与绘制。
 */
public class CameraController {

    private static final String TAG = "CameraController";
    private static final int MAX_IMAGES = 2;

    public interface FrameListener {
        void onFrame(Image image);
    }

    public interface StateListener {
        void onCameraState(String message, boolean error);
    }

    private final Context context;
    private final CameraManager cameraManager;
    private final HandlerThread thread;
    private final Handler handler;

    private String backCameraId;
    private String frontCameraId;

    private CameraDevice device;
    private CameraCaptureSession session;
    private ImageReader imageReader;
    private Size frameSize;
    private String openingCameraId;
    private FrameListener frameListener;
    private StateListener stateListener;

    /**
     * 帧处理与相机拆除（stop）之间的互斥锁。
     *
     * 必须有：ImageReader/Image 的像素缓冲是 HAL 的原生内存，stop() 里 close(session/device/imageReader)
     * 会释放它们；如果此时相机线程正在读 Image 的 plane（或 setPixels），就会踩到已释放内存，
     * 表现为 Fatal signal 11 (SIGSEGV) 且崩溃线程名是 camera-capture。
     */
    private final Object frameLock = new Object();
    private volatile boolean tearingDown;

    /**
     * 相机静态参数缓存：cameraId -> [sensorOrientation, LENS_FACING]。
     *
     * getCameraCharacteristics() 是 binder 调用。实测（模拟器日志）每帧调用会阻塞相机线程：
     * "Long monitor contention with owner camera-capture at CameraManager.prepareCameraCharacteristics ... for 544ms"。
     * 这些参数在运行期不变，因此只读一次并缓存。
     */
    private final Map<String, int[]> cameraMetaCache = new HashMap<>();

    public CameraController(Context context) {
        this.context = context.getApplicationContext();
        this.cameraManager = (CameraManager) context.getSystemService(Context.CAMERA_SERVICE);
        this.thread = new HandlerThread("camera-capture");
        this.thread.start();
        this.handler = new Handler(thread.getLooper());
        enumerateCameras();
    }

    private void enumerateCameras() {
        try {
            for (String id : cameraManager.getCameraIdList()) {
                int facing = cameraMeta(id)[1];
                if (facing == CameraCharacteristics.LENS_FACING_BACK && backCameraId == null) {
                    backCameraId = id;
                } else if (facing == CameraCharacteristics.LENS_FACING_FRONT && frontCameraId == null) {
                    frontCameraId = id;
                }
            }
        } catch (CameraAccessException e) {
            Log.e(TAG, "枚举摄像头失败", e);
        }
    }

    /**
     * 读取并缓存相机的 [sensorOrientation, LENS_FACING]；失败时回退为 [0, 后摄]。
     * 该方法只会在构造期或首次访问某 id 时触发 binder 调用，之后命中缓存。
     */
    private int[] cameraMeta(String cameraId) {
        int[] cached = cameraMetaCache.get(cameraId);
        if (cached != null) {
            return cached;
        }
        int[] meta = {0, CameraCharacteristics.LENS_FACING_BACK};
        try {
            CameraCharacteristics c = cameraManager.getCameraCharacteristics(cameraId);
            Integer sensorOrientation = c.get(CameraCharacteristics.SENSOR_ORIENTATION);
            Integer facing = c.get(CameraCharacteristics.LENS_FACING);
            if (sensorOrientation != null) {
                meta[0] = sensorOrientation;
            }
            if (facing != null) {
                meta[1] = facing;
            }
        } catch (CameraAccessException e) {
            Log.w(TAG, "读取相机参数失败: " + cameraId, e);
        }
        cameraMetaCache.put(cameraId, meta);
        return meta;
    }

    public String getBackCameraId() {
        return backCameraId;
    }

    public String getFrontCameraId() {
        return frontCameraId;
    }

    public boolean hasBothCameras() {
        return backCameraId != null && frontCameraId != null;
    }

    /** 当前实际打开的摄像头 id（未打开时返回 null）。 */
    public String getOpenedCameraId() {
        return openingCameraId;
    }

    /**
     * 计算把传感器画面摆正所需的顺时针旋转角（与屏幕方向、前后摄有关）。
     * 每帧都会调用，因此只做算术运算，参数全部走缓存（不做 binder 调用）。
     */
    public int getRotationDegrees(String cameraId) {
        int[] meta = cameraMeta(cameraId);
        int sensorOrientation = meta[0];
        boolean front = meta[1] == CameraCharacteristics.LENS_FACING_FRONT;
        int deviceRotation = getDeviceRotationDegrees();
        if (front) {
            return (sensorOrientation + deviceRotation) % 360;
        }
        return (sensorOrientation - deviceRotation + 360) % 360;
    }

    @SuppressWarnings("deprecation")
    private int getDeviceRotationDegrees() {
        WindowManager wm = (WindowManager) context.getSystemService(Context.WINDOW_SERVICE);
        if (wm == null || wm.getDefaultDisplay() == null) {
            return 0;
        }
        switch (wm.getDefaultDisplay().getRotation()) {
            case Surface.ROTATION_90:
                return 90;
            case Surface.ROTATION_180:
                return 180;
            case Surface.ROTATION_270:
                return 270;
            case Surface.ROTATION_0:
            default:
                return 0;
        }
    }

    /** 打开指定摄像头并开始出帧。重复调用前请先 stop()。 */
    public void start(String cameraId, FrameListener listener, StateListener stateListener) {
        this.frameListener = listener;
        this.stateListener = stateListener;
        this.openingCameraId = cameraId;
        this.tearingDown = false;
        try {
            if (imageReader == null) {
                frameSize = chooseFrameSize(cameraId);
                imageReader = ImageReader.newInstance(frameSize.getWidth(), frameSize.getHeight(),
                        ImageFormat.YUV_420_888, MAX_IMAGES);
                imageReader.setOnImageAvailableListener(this::onImageAvailable, handler);
                Log.i(TAG, "采集尺寸 " + frameSize.getWidth() + "x" + frameSize.getHeight());
            }
            cameraManager.openCamera(cameraId, deviceCallback, handler);
        } catch (CameraAccessException | SecurityException | IllegalArgumentException e) {
            notifyState("打开相机失败: " + e.getMessage(), true);
        }
    }

    /**
     * 关闭会话与相机，并释放 ImageReader。
     *
     * 顺序很重要：先置 tearingDown 并停止重复请求（HAL 不再产出新帧），再在 frameLock 内等待
     * 已经在处理的那一帧结束，最后才 close(session/device/imageReader) 释放原生缓冲。
     */
    public void stop() {
        tearingDown = true;
        CameraCaptureSession s = session;
        CameraDevice d = device;
        ImageReader r = imageReader;
        session = null;
        device = null;
        imageReader = null;
        openingCameraId = null;

        try {
            if (s != null) {
                // 只停止重复请求。刻意不调用 abortCaptures()：模拟器的 ranchu HAL 在
                // CameraDeviceSession::waitFlushingDone() 处 SIGABRT（见 README 第 9 节），
                // 而 close() 本身会完成 flush，无需 abortCaptures 这一记“重锤”。
                s.stopRepeating();
            }
        } catch (Exception e) {
            Log.w(TAG, "停止重复请求异常", e);
        }
        // 与 onImageAvailable 互斥：保证没有帧正在读 Image / 原生像素缓冲
        synchronized (frameLock) {
            try {
                if (s != null) {
                    s.close();
                }
            } catch (Exception e) {
                Log.w(TAG, "关闭会话异常", e);
            }
            try {
                if (d != null) {
                    d.close();
                }
            } catch (Exception e) {
                Log.w(TAG, "关闭相机异常", e);
            }
            if (r != null) {
                r.setOnImageAvailableListener(null, null);
                r.close();
            }
        }
        tearingDown = false;
    }

    /** 释放线程，Activity 销毁时调用。 */
    public void release() {
        stop();
        thread.quitSafely();
    }

    public Size getFrameSize() {
        return frameSize;
    }

    private void onImageAvailable(ImageReader reader) {
        // reader 已被替换或正在拆除：说明本次回调是“迟到的”排队任务，
        // 此时 acquire 拿到的 Image 可能已被 close（ImageReader.close() 会关闭其中的 Image），
        // 直接用会抛 IllegalStateException("Image is already closed")，因此直接丢弃。
        if (tearingDown || reader != imageReader) {
            return;
        }
        Image image;
        try {
            image = reader.acquireLatestImage();
        } catch (IllegalStateException e) {
            // reader 已被 stop() 关闭
            return;
        }
        if (image == null) {
            return;
        }
        synchronized (frameLock) {
            if (tearingDown || reader != imageReader) {
                image.close();
                return;
            }
            try {
                image.getWidth(); // 探测 Image 是否仍有效（HAL/reader 已失效时会抛 IllegalStateException）
                FrameListener listener = frameListener;
                if (listener != null) {
                    listener.onFrame(image);
                }
            } catch (IllegalStateException e) {
                // 预期内的竞态（相机拆除/HAL 重启导致 Image 失效），按警告记录，不再刷栈
                Log.w(TAG, "丢弃失效帧: " + e.getMessage());
            } catch (Throwable t) {
                Log.e(TAG, "处理帧失败", t);
            } finally {
                image.close();
            }
        }
    }

    private final CameraDevice.StateCallback deviceCallback = new CameraDevice.StateCallback() {
        @Override
        public void onOpened(CameraDevice camera) {
            device = camera;
            createSession();
        }

        @Override
        public void onDisconnected(CameraDevice camera) {
            Log.w(TAG, "相机关闭/断开");
            camera.close();
            device = null;
            notifyState("相机已断开", true);
        }

        @Override
        public void onError(CameraDevice camera, int error) {
            Log.e(TAG, "相机错误: " + error);
            camera.close();
            device = null;
            notifyState("相机错误: " + error, true);
        }
    };

    private void createSession() {
        if (device == null || imageReader == null) {
            return;
        }
        try {
            List<Surface> surfaces = Collections.singletonList(imageReader.getSurface());
            device.createCaptureSession(surfaces, new CameraCaptureSession.StateCallback() {
                @Override
                public void onConfigured(CameraCaptureSession configured) {
                    session = configured;
                    startRepeating();
                }

                @Override
                public void onConfigureFailed(CameraCaptureSession failed) {
                    notifyState("会话配置失败", true);
                }
            }, handler);
        } catch (CameraAccessException e) {
            notifyState("创建会话失败: " + e.getMessage(), true);
        }
    }

    private void startRepeating() {
        if (device == null || session == null || imageReader == null) {
            return;
        }
        try {
            CaptureRequest.Builder builder = device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
            builder.addTarget(imageReader.getSurface());
            builder.set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO);
            builder.set(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_PICTURE);
            session.setRepeatingRequest(builder.build(), null, handler);
            notifyState("预览中 (" + frameSize.getWidth() + "x" + frameSize.getHeight() + ")", false);
        } catch (CameraAccessException e) {
            notifyState("启动预览失败: " + e.getMessage(), true);
        }
    }

    private void notifyState(String message, boolean error) {
        StateListener listener = stateListener;
        if (listener != null) {
            listener.onCameraState(message, error);
        }
    }

    /**
     * 从 YUV_420_888 的输出尺寸里挑一个：优先 1280x720 / 960x540 / 640x480，
     * 否则取不超过 1920x1080 里面积最大的那一档。
     */
    private Size chooseFrameSize(String cameraId) throws CameraAccessException {
        CameraCharacteristics characteristics = cameraManager.getCameraCharacteristics(cameraId);
        StreamConfigurationMap map = characteristics
                .get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
        Size[] sizes = map == null ? null : map.getOutputSizes(ImageFormat.YUV_420_888);
        if (sizes == null || sizes.length == 0) {
            return new Size(1280, 720);
        }
        int[][] preferred = {{1280, 720}, {960, 540}, {640, 480}};
        for (int[] want : preferred) {
            for (Size s : sizes) {
                if (s.getWidth() == want[0] && s.getHeight() == want[1]) {
                    return s;
                }
            }
        }
        Size best = null;
        List<Size> sorted = new ArrayList<>();
        Collections.addAll(sorted, sizes);
        for (Size s : sorted) {
            long area = (long) s.getWidth() * s.getHeight();
            if (area > 1920L * 1080L) {
                continue;
            }
            if (best == null || area > (long) best.getWidth() * best.getHeight()) {
                best = s;
            }
        }
        return best != null ? best : sizes[0];
    }
}
