package io.github.qiangjian.connect;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.IBinder;
import android.os.Parcel;
import android.os.RemoteException;
import android.widget.Toast;

/** Per-native-request permission UI only; never displays a preview or claims remote success. */
public final class CameraShareConsentActivity extends Activity {
    private static final int CAMERA_REQUEST = 719;
    private String handle;
    private IBinder controller;
    private boolean bound, resumed, allowRequested, permissionRequested, activated;
    private AlertDialog dialog;
    private final ServiceConnection connection = new ServiceConnection() {
        @Override public void onServiceConnected(ComponentName name, IBinder service) {
            controller = service;
            try { showRequest(); } catch (RuntimeException | RemoteException expired) { unavailable(); }
        }
        @Override public void onServiceDisconnected(ComponentName name) { controller = null; unavailable(); }
        @Override public void onBindingDied(ComponentName name) { controller = null; unavailable(); }
        @Override public void onNullBinding(ComponentName name) { controller = null; unavailable(); }
    };

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        handle = getIntent().getStringExtra(CameraShareSourceService.HANDLE);
        if (handle == null || !handle.matches("[0-9a-f]{64}")) { unavailable(); return; }
        bound = bindService(new Intent(this, CameraShareSourceService.class), connection, Context.BIND_AUTO_CREATE);
        if (!bound) unavailable();
    }

    private Parcel transact(int operation) throws RemoteException {
        if (controller == null) throw new RemoteException("camera_controller_unavailable");
        Parcel data = Parcel.obtain(), reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(CameraShareSourceService.UI_DESCRIPTOR); data.writeString(handle);
            if (!controller.transact(operation, data, reply, 0)) throw new RemoteException("camera_private_ui_unavailable");
            reply.readException(); return reply;
        } catch (RuntimeException | RemoteException failure) { reply.recycle(); throw failure; }
        finally { data.recycle(); }
    }

    private void showRequest() throws RemoteException {
        if (isFinishing() || dialog != null) return;
        Parcel reply = transact(CameraShareSourceService.DESCRIBE);
        String peer, camera;
        int width, height, fps, stillWidth = 0, stillHeight = 0;
        try {
            if (!reply.readBoolean()) { unavailable(); return; }
            peer = reply.readString(); camera = reply.readString();
            width = reply.readInt(); height = reply.readInt(); fps = reply.readInt();
            if (reply.readBoolean()) { stillWidth = reply.readInt(); stillHeight = reply.readInt(); }
            reply.enforceNoDataAvail();
        } finally { reply.recycle(); }
        if (peer == null || peer.isBlank() || peer.length() > 128 || camera == null || camera.length() > 128
                || width < 1 || height < 1 || fps < 1 || fps > 30 || stillWidth < 0 || stillHeight < 0
                || stillWidth > 8192 || stillHeight > 8192) { unavailable(); return; }
        String stillDescription = stillWidth > 0 && stillHeight > 0
                ? "\n允许本次原生业务拍照：" + stillWidth + " × " + stillHeight : "";
        dialog = new AlertDialog.Builder(this).setTitle("允许使用本机摄像头？")
                .setMessage("对端：" + peer + "\n摄像头：" + camera + "\n画面：" + width + " × " + height + "，" + fps
                        + " 帧/秒" + stillDescription + "\n\n仅用于这次原生设备请求。采集期间会显示系统通知，可随时停止。")
                .setPositiveButton("允许本次使用", (ignored, which) -> allow())
                .setNegativeButton("拒绝", (ignored, which) -> denyAndFinish())
                .setOnCancelListener(ignored -> denyAndFinish()).create();
        dialog.show();
    }

    private void allow() {
        allowRequested = true;
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            permissionRequested = true;
            requestPermissions(new String[]{Manifest.permission.CAMERA}, CAMERA_REQUEST);
        } else activateIfVisible();
    }

    @Override public void onRequestPermissionsResult(int code, String[] permissions, int[] grants) {
        super.onRequestPermissionsResult(code, permissions, grants);
        if (code != CAMERA_REQUEST) return;
        permissionRequested = false;
        if (grants.length == 1 && grants[0] == PackageManager.PERMISSION_GRANTED) activateIfVisible();
        else denyAndFinish();
    }

    private void activateIfVisible() {
        if (!resumed || !allowRequested || permissionRequested || activated || isFinishing()) return;
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) { denyAndFinish(); return; }
        try {
            Parcel reply = transact(CameraShareSourceService.APPROVE);
            boolean allowed;
            try { allowed = reply.readBoolean(); reply.enforceNoDataAvail(); } finally { reply.recycle(); }
            if (!allowed) { unavailable(); return; }
            // This actual visible Activity/user click is the FGS start origin; no background exception bypass.
            startForegroundService(new Intent(this, CameraShareSourceService.class)
                    .setAction(CameraShareSourceService.ACTIVATE).putExtra(CameraShareSourceService.HANDLE, handle));
            activated = true;
            finish(); // Actual capture/render/close results remain on the native request's controller.
        } catch (RemoteException | RuntimeException denied) { denyAndFinish(); }
    }

    private void denyAndFinish() {
        if (!activated && controller != null) {
            try { Parcel reply = transact(CameraShareSourceService.DENY); reply.recycle(); }
            catch (RemoteException | RuntimeException ignored) { }
        }
        finish();
    }

    private void unavailable() {
        if (!isFinishing()) Toast.makeText(this, "摄像头请求已失效，请从原设备重新发起", Toast.LENGTH_SHORT).show();
        denyAndFinish();
    }

    @Override public void onResume() { super.onResume(); resumed = true; activateIfVisible(); }
    @Override public void onPause() { resumed = false; super.onPause(); }
    @Override public void onDestroy() {
        if (!activated && !isChangingConfigurations() && isFinishing() && controller != null) {
            try { Parcel reply = transact(CameraShareSourceService.DENY); reply.recycle(); }
            catch (RemoteException | RuntimeException ignored) { }
        }
        if (dialog != null) dialog.dismiss();
        if (bound) { unbindService(connection); bound = false; }
        super.onDestroy();
    }
}
