package cn.local.miaotask;

import android.content.ComponentName;
import android.content.Context;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;

import rikka.shizuku.Shizuku;

final class ShizukuBridge {
    private static ShizukuBridge instance;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final java.util.concurrent.ExecutorService cleanup = java.util.concurrent.Executors.newSingleThreadExecutor();
    private final Shizuku.UserServiceArgs args;
    private final PackageManager packages;
    private final String packageName;
    private volatile IShizukuService remote;
    private boolean binding;
    private String detail = "等待连接";

    private ServiceConnection connection;

    static synchronized ShizukuBridge get(Context context) {
        if (instance == null) instance = new ShizukuBridge(context.getApplicationContext());
        return instance;
    }

    private ShizukuBridge(Context context) {
        packages = context.getPackageManager(); packageName = context.getPackageName();
        args = new Shizuku.UserServiceArgs(new ComponentName(context, ShizukuService.class))
                .tag("taobao-activity-runner").version(BuildConfig.VERSION_CODE).daemon(false).processNameSuffix("shizuku_runner");
        Shizuku.addBinderReceivedListenerSticky(this::connect, main);
        Shizuku.addBinderDeadListener(() -> disconnected("Shizuku 已停止，请先重新启动"), main);
        Shizuku.addRequestPermissionResultListener((code, grant) -> {
            if (code != 19) return;
            if (grant == PackageManager.PERMISSION_GRANTED) connect();
            else detail = "未获得 Shizuku 授权，可在 Shizuku 的已授权应用中检查";
        }, main);
    }

    boolean authorized() {
        try { return Shizuku.pingBinder() && Shizuku.getVersion() >= 13 && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED; }
        catch (RuntimeException e) { return false; }
    }

    boolean ready() { IShizukuService service = remote; return authorized() && service != null && service.asBinder().isBinderAlive(); }

    String state() {
        if (!Shizuku.pingBinder()) return "Shizuku：未运行，请先启动 Shizuku";
        try { if (Shizuku.getVersion() < 13) return "请将 Shizuku 更新到 v13 或以上"; }
        catch (RuntimeException e) { return "Shizuku：连接已断开"; }
        if (!authorized()) return "Shizuku：未授权，点击下方授权按钮";
        String scope = packages.checkPermission("moe.shizuku.manager.permission.API_V23", packageName) == PackageManager.PERMISSION_GRANTED
                ? "已授权" : "本次会话已授权";
        return "Shizuku：" + scope + " · " + detail;
    }

    void requestPermission() {
        try {
            if (!Shizuku.pingBinder() || Shizuku.getVersion() < 13) { detail = "请先启动最新版 Shizuku"; return; }
            if (authorized()) connect();
            else Shizuku.requestPermission(19);
        } catch (RuntimeException e) { detail = "授权失败：" + e.getMessage(); }
    }

    void connect() {
        if (!authorized() || ready() || binding) return;
        ServiceConnection callback = new ServiceConnection() {
            @Override public void onServiceConnected(ComponentName name, IBinder binder) {
                if (connection != this) return;
                binding = false;
                try {
                    IShizukuService service = IShizukuService.Stub.asInterface(binder);
                    if (service.protocolVersion() != 5) throw new IllegalStateException("执行服务版本不匹配");
                    int uid = service.executionUid();
                    if (uid != 0 && uid != 2000) throw new IllegalStateException("执行服务未获得 ADB/Root 身份（UID " + uid + "）");
                    remote = service; detail = "执行服务已连接（" + (uid == 2000 ? "ADB" : "Root") + "）";
                    binder.linkToDeath(() -> main.post(() -> {
                        if (remote != null && remote.asBinder() == binder) disconnected("执行服务已断开，请重新连接");
                    }), 0);
                } catch (Exception e) { disconnected("连接失败：" + e.getMessage()); }
            }
            @Override public void onServiceDisconnected(ComponentName name) {
                if (connection == this) disconnected("执行服务已断开，请重新连接");
            }
        };
        connection = callback;
        binding = true; detail = "正在连接执行服务";
        try {
            Shizuku.bindUserService(args, callback);
            main.postDelayed(() -> {
                if (binding && connection == callback) disconnected("连接超时，请点击授权 / 重新连接");
            }, 12_000);
        } catch (RuntimeException e) { disconnected("连接失败：" + e.getMessage()); }
    }

    IShizukuService service() {
        if (!ready()) throw new TaskRunner.Attention("Shizuku 未连接或授权已断开，请重新启动并授权后点击继续");
        return remote;
    }

    private void disconnected(String reason) {
        ServiceConnection old = connection;
        IShizukuService previous = remote;
        remote = null; binding = false; connection = null; detail = reason;
        discardRemote(previous);
        try { if (old != null && Shizuku.pingBinder()) Shizuku.unbindUserService(args, old, false); }
        catch (RuntimeException ignored) { }
        MiaoService service = MiaoService.instance;
        if (service != null && service.running()) service.bridgeDisconnected(reason);
    }

    void disconnect() {
        ServiceConnection old = connection;
        IShizukuService previous = remote;
        remote = null; binding = false; connection = null; detail = "本轮已结束，可重新连接";
        discardRemote(previous);
        try { if (Shizuku.pingBinder()) Shizuku.unbindUserService(args, old, true); }
        catch (RuntimeException ignored) { }
    }

    private void discardRemote(IShizukuService previous) {
        if (previous == null) return;
        cleanup.execute(() -> {
            try { previous.setActive(false); } catch (Exception ignored) { }
            try { previous.destroy(); } catch (Exception ignored) { }
        });
    }
}
