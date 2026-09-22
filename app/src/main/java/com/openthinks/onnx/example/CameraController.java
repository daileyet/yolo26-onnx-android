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
import java.util.List;

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
                Integer facing = cameraManager.getCameraCharacteristics(id)
                        .get(CameraCharacteristics.LENS_FACING);
                if (facing == null) {
                    continue;
                }
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
     */
    public int getRotationDegrees(String cameraId) {
        try {
            CameraCharacteristics c = cameraManager.getCameraCharacteristics(cameraId);
            Integer sensorOrientation = c.get(CameraCharacteristics.SENSOR_ORIENTATION);
            Integer facing = c.get(CameraCharacteristics.LENS_FACING);
            if (sensorOrientation == null) {
                return 0;
            }
            int deviceRotation = getDeviceRotationDegrees();
            boolean front = facing != null && facing == CameraCharacteristics.LENS_FACING_FRONT;
            if (front) {
                return (sensorOrientation + deviceRotation) % 360;
            }
            return (sensorOrientation - deviceRotation + 360) % 360;
        } catch (CameraAccessException e) {
            Log.e(TAG, "读取相机参数失败", e);
            return 0;
        }
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

    /** 关闭会话与相机，并释放 ImageReader。 */
    public void stop() {
        try {
            if (session != null) {
                session.close();
                session = null;
            }
        } catch (Exception e) {
            Log.w(TAG, "关闭会话异常", e);
        }
        try {
            if (device != null) {
                device.close();
                device = null;
            }
        } catch (Exception e) {
            Log.w(TAG, "关闭相机异常", e);
        }
        if (imageReader != null) {
            imageReader.close();
            imageReader = null;
        }
        openingCameraId = null;
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
        Image image = reader.acquireLatestImage();
        if (image == null) {
            return;
        }
        try {
            FrameListener listener = frameListener;
            if (listener != null) {
                listener.onFrame(image);
            }
        } catch (Throwable t) {
            Log.e(TAG, "处理帧失败", t);
        } finally {
            image.close();
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
