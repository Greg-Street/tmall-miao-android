package cn.local.miaotask;

import android.app.KeyguardManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PathMeasure;
import android.graphics.Rect;
import android.graphics.RectF;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.os.PowerManager;
import android.os.SystemClock;
import android.provider.Settings;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.google.android.gms.tasks.Tasks;
import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.text.Text;
import com.google.mlkit.vision.text.TextRecognition;
import com.google.mlkit.vision.text.TextRecognizer;
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;

import cn.local.miaotask.TaskRules.Node;
import cn.local.miaotask.TaskRunner.Attention;
import cn.local.miaotask.TaskRunner.Control;
import cn.local.miaotask.TaskRunner.Snapshot;

public final class MiaoService extends Service implements TaskRunner.Driver {
    static volatile MiaoService instance;
    private static volatile String lastHistory = "", lastStatus = "等待开始";
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final ExecutorService controls = Executors.newSingleThreadExecutor();
    private final StringBuilder history = new StringBuilder();
    private TextRecognizer recognizer;
    private ShizukuBridge bridge;
    private volatile boolean running;
    private volatile Control control;
    private volatile String status = "等待开始";
    private volatile Snapshot lastScreen;
    private LinearLayout overlay;
    private TextView overlayText;
    private Button pauseButton;
    private WindowManager windows;
    private NotificationManager notifications;
    private WindowManager.LayoutParams overlayParams;
    private long lastScreenshotRequest = -1;
    private static final class WindowChanged extends RuntimeException { }

    @Override public void onCreate() {
        super.onCreate();
        instance = this;
        windows = (WindowManager) getSystemService(WINDOW_SERVICE);
        notifications = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        notifications.createNotificationChannel(new NotificationChannel("runner", "任务运行状态", NotificationManager.IMPORTANCE_LOW));
        recognizer = TextRecognition.getClient(new ChineseTextRecognizerOptions.Builder().build());
        bridge = ShizukuBridge.get(this);
    }

    @Override public IBinder onBind(Intent intent) { return null; }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) { stopSelf(); return START_NOT_STICKY; }
        if (!running) startRun(intent.getIntExtra("limit", 80), intent.getBooleanExtra("autoEntry", true),
                intent.getBooleanExtra("crossApp", true), intent.getIntExtra("visitSeconds", 2), intent.getIntExtra("browseExtraSeconds", 1));
        return START_NOT_STICKY;
    }

    boolean running() { return running; }
    boolean paused() { return control != null && control.paused(); }
    String status() { return status; }
    synchronized String history() { return history.toString(); }
    static String latestHistory() { MiaoService service = instance; return service == null ? lastHistory : service.history(); }
    static String latestStatus() { MiaoService service = instance; return service == null ? lastStatus : service.status(); }

    private void startRun(int limit, boolean autoEntry, boolean crossApp, int visitSeconds, int browseExtraSeconds) {
        if (limit < 1 || limit > 80 || visitSeconds < 0 || visitSeconds > 30 || browseExtraSeconds < 0 || browseExtraSeconds > 30) {
            log("执行设置无效，请重新填写"); finishRun(); return;
        }
        synchronized (this) { history.setLength(0); lastHistory = ""; }
        control = new Control(); running = true;
        try {
            startForeground(1, notification(), Build.VERSION.SDK_INT >= 34 ? ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE : 0);
            if (!Settings.canDrawOverlays(this)) throw new Attention("请先允许本应用显示悬浮窗");
            if (!bridge.ready()) throw new Attention("请先启动并授权 Shizuku，再点击开始");
            showOverlay();
        } catch (RuntimeException e) { log("无法开始：" + e.getMessage()); finishRun(); return; }
        log("开始本轮，上限 " + limit + " 次；Shizuku 执行，离线 OCR 已内置");
        log("时间设置：应用访问停留 " + visitSeconds + " 秒；浏览额外停留 " + browseExtraSeconds + " 秒");
        Control runControl = control;
        worker.execute(() -> {
            TaskRunner runner = new TaskRunner(this, runControl, this::log, limit, autoEntry, crossApp, visitSeconds, browseExtraSeconds);
            try { remote(() -> { bridge.service().setActive(true); return null; }); runner.run(); }
            catch (TaskRunner.Stopped e) { log("已停止；" + runner.summary()); }
            catch (RuntimeException e) { log("运行中断：" + e.getClass().getSimpleName() + " · " + e.getMessage()); }
            finally { main.post(this::finishRun); }
        });
    }

    private void cancelRemote() {
        try {
            IShizukuService service = bridge.service();
            controls.execute(() -> { try { service.setActive(false); } catch (Exception ignored) { } });
        } catch (RuntimeException ignored) { }
    }

    void bridgeDisconnected(String reason) {
        if (!running || control == null) return;
        control.setPaused(true);
        log(reason + "；已暂停，重新连接后点击继续");
    }

    void togglePause() {
        if (!running || control == null) return;
        if (!control.paused()) {
            control.setPaused(true); cancelRemote();
            log("已暂停，可在屏幕上自行操作；继续时重新识别页面");
        } else {
            if (!bridge.ready()) { bridge.connect(); log("请先重新连接 Shizuku，再点击继续"); return; }
            IShizukuService service;
            try { service = bridge.service(); }
            catch (Attention e) { log(e.getMessage()); return; }
            controls.execute(() -> {
                try {
                    service.setActive(true);
                    main.post(() -> { if (running) { control.setPaused(false); if (control.actionable()) log("已继续，准备重新识别"); } });
                } catch (Exception e) { main.post(() -> log("Shizuku 恢复失败，请重新连接后继续")); }
            });
        }
        notifyState();
    }

    void stopRun() {
        Control current = control;
        if (current != null) current.stop();
        if (running) { cancelRemote(); log("正在停止…"); }
    }

    private synchronized void log(String message) {
        status = message; lastStatus = message;
        history.append(new SimpleDateFormat("HH:mm:ss", Locale.CHINA).format(new Date())).append("  ").append(message).append('\n');
        if (history.length() > 40_000) history.delete(0, history.indexOf("\n", history.length() - 35_000) + 1);
        lastHistory = history.toString();
        main.post(() -> {
            if (overlayText != null) overlayText.setText(message);
            if (pauseButton != null) pauseButton.setText(paused() ? "继续" : "暂停");
            if (paused() && running) { cancelRemote(); notifyState(); }
        });
    }

    private <T> T onMain(java.util.concurrent.Callable<T> operation) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            try { return operation.call(); } catch (RuntimeException e) { throw e; }
            catch (Exception e) { throw new Attention(e.getMessage()); }
        }
        FutureTask<T> task = new FutureTask<>(operation); main.post(task);
        try { return task.get(8, TimeUnit.SECONDS); }
        catch (ExecutionException e) {
            if (e.getCause() instanceof RuntimeException) throw (RuntimeException) e.getCause();
            throw new Attention("界面操作失败：" + e.getCause().getMessage());
        } catch (Exception e) { task.cancel(false); throw new Attention("控制面板响应超时"); }
    }

    private <T> T remote(java.util.concurrent.Callable<T> operation) {
        control.check();
        try { T result = operation.call(); control.check(); return result; }
        catch (TaskRunner.Stopped | TaskRunner.Rescan | Attention e) { throw e; }
        catch (Exception e) {
            control.check();
            throw new Attention("Shizuku 操作失败：" + e.getMessage(), bridge.ready());
        }
    }

    private Snapshot foreground() {
        control.check();
        KeyguardManager lock = (KeyguardManager) getSystemService(KEYGUARD_SERVICE);
        PowerManager power = (PowerManager) getSystemService(POWER_SERVICE);
        if (lock.isKeyguardLocked() || !power.isInteractive()) throw new Attention("屏幕已锁定，请解锁后点击继续");
        Rect display = windows.getMaximumWindowMetrics().getBounds();
        String pkg = remote(() -> bridge.service().foreground());
        return new Snapshot(List.of(), display.width(), display.height(), pkg);
    }

    @Override public Snapshot readForeground() {
        Snapshot screen = foreground();
        if (!screen.pkg.isEmpty() && !TaskRules.TAOBAO.equals(screen.pkg) && !getPackageName().equals(screen.pkg))
            return read(false);
        lastScreen = screen;
        return screen;
    }

    @Override public Snapshot read() { return read(false); }
    @Override public Snapshot readNavigation() { return read(true); }

    @Override public Snapshot readBrowse() { return read(false, 720); }

    private Snapshot read(boolean navigation) { return read(navigation, 1080); }

    private Snapshot read(boolean navigation, int maxWidth) {
        for (int attempt = 0; attempt < 3; attempt++) {
            try { return readFrame(navigation, maxWidth); }
            catch (WindowChanged changed) { control.sleep(500); }
        }
        throw new Attention("前台应用持续切换", true);
    }

    private Snapshot readFrame(boolean navigation, int maxWidth) {
        Snapshot before = foreground();
        if (before.pkg.isEmpty()) throw new Attention("暂时无法识别前台应用", true);
        long interval = ScreenshotPolicy.intervalDelay(SystemClock.uptimeMillis(), lastScreenshotRequest);
        if (interval > 0) control.sleep(interval);
        lastScreenshotRequest = SystemClock.uptimeMillis();
        Bitmap original = null;
        try (ParcelFileDescriptor fd = remote(() -> bridge.service().capture());
             var input = new ParcelFileDescriptor.AutoCloseInputStream(fd)) {
            BitmapFactory.Options decoding = new BitmapFactory.Options();
            decoding.inMutable = true; decoding.inPreferredConfig = Bitmap.Config.ARGB_8888;
            original = BitmapFactory.decodeStream(input, null, decoding);
            fd.checkError();
            control.check();
            if (original == null) throw new Attention("Shizuku 截图失败，未获得可识别图像", true);
            if (original.getWidth() >= original.getHeight()) throw new Attention("请竖屏运行");
            Node bounds = new Node("", 0, 0, original.getWidth(), original.getHeight());
            Node covered = onMain(this::overlayBounds);
            if (covered != null) {
                Paint paint = new Paint(); paint.setColor(0xFFF3F3F3);
                new Canvas(original).drawRect(covered.left, covered.top, covered.right, covered.bottom, paint);
            }
            List<Node> nodes = null;
            if (navigation) {
                int height = Math.max(1, Math.round(original.getHeight() * .6f));
                Bitmap top = Bitmap.createBitmap(original, 0, 0, original.getWidth(), height);
                try {
                    List<Node> quick = recognize(top, new Node("", 0, 0, original.getWidth(), height), 720);
                    Snapshot candidate = new Snapshot(quick, original.getWidth(), original.getHeight(), before.pkg);
                    String quickGuard = TaskRules.guardReason(quick);
                    if (!quickGuard.isEmpty()) throw new Attention(quickGuard);
                    if (TaskRunner.energyEntryReady(candidate)) nodes = quick;
                } finally { if (top != original) top.recycle(); }
            }
            if (nodes == null) nodes = recognize(original, bounds, maxWidth);
            String reason = TaskRules.guardReason(nodes);
            if (!reason.isEmpty()) throw new Attention(reason);
            if (!before.pkg.equals(foreground().pkg)) throw new WindowChanged();
            Snapshot screen = new Snapshot(nodes, original.getWidth(), original.getHeight(), before.pkg);
            lastScreen = screen; return screen;
        } catch (TaskRunner.Stopped | TaskRunner.Rescan | Attention | WindowChanged e) { throw e; }
        catch (Exception e) { control.check(); throw new Attention("Shizuku 截图或离线识别失败：" + e.getMessage(), bridge.ready()); }
        finally { if (original != null) original.recycle(); }
    }

    private List<Node> recognize(Bitmap image, Node capture, int maxWidth) throws Exception {
        float scale = Math.min(1f, maxWidth / (float) image.getWidth());
        Bitmap resized = Bitmap.createScaledBitmap(image, Math.round(image.getWidth() * scale), Math.max(1, Math.round(image.getHeight() * scale)), true);
        // Own the OCR input so a timed-out recognition cannot retain a recycled screenshot.
        Bitmap input = resized == image ? image.copy(Bitmap.Config.ARGB_8888, false) : resized;
        com.google.android.gms.tasks.Task<Text> recognition = null;
        try {
            recognition = recognizer.process(InputImage.fromBitmap(input, 0));
            Text text = Tasks.await(recognition, 15, TimeUnit.SECONDS);
            control.check();
            List<Node> nodes = new ArrayList<>();
            for (Text.TextBlock block : text.getTextBlocks()) for (Text.Line line : block.getLines()) {
                Rect box = line.getBoundingBox();
                if (box != null) nodes.add(ScreenGeometry.mapText(new Node(line.getText(), box.left, box.top, box.right, box.bottom),
                        capture, image.getWidth(), image.getHeight(), scale));
                List<Node> fragments = new ArrayList<>();
                for (Text.Element element : line.getElements()) {
                    Rect bounds = element.getBoundingBox();
                    if (bounds == null) continue;
                    Node fragment = new Node(element.getText(), bounds.left, bounds.top, bounds.right, bounds.bottom);
                    fragments.add(fragment);
                    if (TaskRules.actionLabel(element.getText()) || TaskRules.matches(element.getText(), "^搜索$")) nodes.add(ScreenGeometry.mapText(fragment,
                            capture, image.getWidth(), image.getHeight(), scale));
                }
                Node entry = TaskRules.energyEntry(fragments);
                if (entry != null) nodes.add(ScreenGeometry.mapText(entry, capture, image.getWidth(), image.getHeight(), scale));
                for (Node card : TaskRules.coinEntries(fragments)) nodes.add(ScreenGeometry.mapText(card,
                        capture, image.getWidth(), image.getHeight(), scale));
            }
            return nodes;
        } finally {
            if (recognition != null && !recognition.isComplete()) recognition.addOnCompleteListener(getMainExecutor(), ignored -> input.recycle());
            else input.recycle();
        }
    }

    @Override public void tap(Node node) {
        var random = java.util.concurrent.ThreadLocalRandom.current();
        Node point = ScreenGeometry.tapPoint(node, random.nextDouble(), random.nextDouble());
        Path path = new Path(); path.moveTo(point.x(), point.y()); gesture(path, random.nextInt(60, 121));
    }

    @Override public void swipe(boolean reverse) {
        control.check();
        Snapshot screen = lastScreen;
        if (screen == null) throw new Attention("没有可用的页面尺寸", true);
        Path path = new Path();
        path.moveTo(screen.width * .5f, screen.height * (reverse ? .48f : .76f));
        path.lineTo(screen.width * .5f, screen.height * (reverse ? .76f : .48f));
        gesture(path, 480);
    }

    @Override public void browseSwipe(boolean reverse, boolean horizontal) {
        control.check();
        Snapshot screen = lastScreen;
        if (screen == null) throw new Attention("没有可用的页面尺寸", true);
        var random = java.util.concurrent.ThreadLocalRandom.current();
        float[] points = ScreenGeometry.browsePath(screen.width, screen.height, reverse, horizontal,
                random.nextDouble(), random.nextDouble(), random.nextDouble());
        Path path = new Path(); path.moveTo(points[0], points[1]); path.lineTo(points[2], points[3]);
        gesture(path, random.nextInt(300, 551));
    }

    private void gesture(Path path, long duration) {
        control.check();
        Snapshot expected = lastScreen;
        if (expected == null || expected.pkg.isEmpty()) throw new Attention("没有可用的前台应用信息", true);
        onMain(() -> {
            RectF bounds = new RectF(); path.computeBounds(bounds, true);
            avoidOverlay(new Node("", (int) bounds.left, (int) bounds.top, (int) Math.ceil(bounds.right + 1), (int) Math.ceil(bounds.bottom + 1)));
            return null;
        });
        control.sleep(80);
        PathMeasure measure = new PathMeasure(path, false);
        int count = Math.max(2, Math.min(128, (int) duration / 16 + 1));
        float[] xs = new float[count], ys = new float[count], point = new float[2];
        RectF bounds = new RectF(); path.computeBounds(bounds, true);
        for (int i = 0; i < count; i++) {
            if (measure.getLength() > 0) measure.getPosTan(measure.getLength() * i / (count - 1), point, null);
            else { point[0] = bounds.left; point[1] = bounds.top; }
            xs[i] = Math.max(0, Math.min(expected.width - 1, point[0]));
            ys[i] = Math.max(0, Math.min(expected.height - 1, point[1]));
        }
        remote(() -> { bridge.service().gesture(xs, ys, duration, expected.pkg); return null; });
    }

    @Override public void back() {
        control.check();
        Snapshot expected = lastScreen, current = foreground();
        if (expected != null && !TaskRules.TAOBAO.equals(expected.pkg) && TaskRules.TAOBAO.equals(current.pkg)) {
            lastScreen = current; return;
        }
        if (current.pkg.isEmpty()) throw new Attention("无法确认返回操作的前台应用", true);
        lastScreen = current;
        remote(() -> { bridge.service().back(current.pkg); return null; });
    }

    @Override public void launchTaobao() {
        Snapshot current = foreground();
        if (TaskRules.TAOBAO.equals(current.pkg)) { lastScreen = current; return; }
        String component = onMain(() -> {
            Intent launch = getPackageManager().getLaunchIntentForPackage(TaskRules.TAOBAO);
            if (launch == null || launch.getComponent() == null) throw new Attention("未找到本用户下的淘宝启动入口，请确认已安装淘宝正式版");
            return launch.getComponent().flattenToShortString();
        });
        int userId = android.os.Process.myUid() / 100000;
        remote(() -> { bridge.service().launchTaobao(component, userId); return null; }); lastScreen = null;
    }

    @Override public boolean openCoinPage() {
        foreground();
        int userId = android.os.Process.myUid() / 100000;
        remote(() -> { bridge.service().openCoinPage(userId); return null; });
        lastScreen = null; return true;
    }

    @Override public boolean setSearchText(String text) {
        control.check();
        Snapshot screen = lastScreen;
        if (screen == null || !TaskRules.TAOBAO.equals(screen.pkg)) return false;
        Node header = new Node("搜索栏", 0, 0, screen.width, (int) (screen.height * .25));
        if (onMain(() -> avoidOverlay(header))) { control.sleep(150); screen = read(); }
        if (!TaskRules.TAOBAO.equals(screen.pkg)) return false;
        Snapshot inputScreen = screen;
        Node search = screen.nodes.stream().filter(n -> n.top >= 0 && n.y() < inputScreen.height * .25
                && n.x() > inputScreen.width * .65 && TaskRules.matches(n.text, "^搜索$")).findFirst().orElse(null);
        if (search == null) return false;
        int x = (int) (screen.width * .35);
        tap(new Node("搜索输入框", x - 8, search.y() - 8, x + 8, search.y() + 8));
        control.sleep(250);
        BridgeRules.searchText(text);
        if (text.matches("[A-Za-z0-9 ]+")) remote(() -> { bridge.service().inputText(text, inputScreen.pkg); return null; });
        else inputChinese(text);
        control.sleep(100); return true;
    }

    private void inputChinese(String text) {
        IShizukuService service = bridge.service();
        String component = new android.content.ComponentName(this, SearchInputMethod.class).flattenToShortString();
        try {
            remote(() -> { service.beginChineseInput(component, android.os.Process.myUid() / 100000); return null; });
            long deadline = control.now() + 4000;
            while (control.now() < deadline) {
                control.check();
                if (onMain(() -> control.actionable() && SearchInputMethod.commitSearch(text))) return;
                control.sleep(100);
            }
            throw new Attention("临时中文输入未连接淘宝搜索框，请检查系统输入法限制");
        } finally {
            try { service.endChineseInput(); }
            catch (Exception e) { throw new Attention("恢复原键盘失败，请在系统中切回常用键盘：" + e.getMessage()); }
        }
    }

    @Override public boolean submitSearch() {
        Snapshot screen = lastScreen;
        if (screen == null || !TaskRules.TAOBAO.equals(screen.pkg)) return false;
        remote(() -> { bridge.service().submitSearch(screen.pkg); return null; });
        return true;
    }

    private Node overlayBounds() {
        if (overlay == null || !overlay.isAttachedToWindow()) return null;
        int[] location = new int[2]; overlay.getLocationOnScreen(location);
        return new Node("", location[0], location[1], location[0] + overlay.getWidth(), location[1] + overlay.getHeight());
    }

    private boolean avoidOverlay(Node path) {
        Node before = overlayBounds();
        if (before == null || lastScreen == null) return false;
        Node after = ScreenGeometry.avoidGesture(before, path, lastScreen.width, lastScreen.height,
                Math.round(12 * getResources().getDisplayMetrics().density));
        if (after == null) throw new Attention("悬浮面板遮挡操作区域，请先拖动面板后继续");
        if (after == before) return false;
        overlayParams.x += after.left - before.left;
        overlayParams.y += after.top - before.top;
        windows.updateViewLayout(overlay, overlayParams);
        return true;
    }

    private void showOverlay() {
        overlay = new LinearLayout(this);
        overlay.setOrientation(LinearLayout.VERTICAL);
        overlay.setPadding(16, 8, 16, 8);
        overlay.setBackgroundColor(0xF2332925);
        TextView handle = new TextView(this);
        handle.setText("淘宝活动助手 · 拖动这里移动"); handle.setTextColor(0xFFFFD6B0); handle.setTextSize(12);
        overlay.addView(handle);
        overlayText = new TextView(this);
        overlayText.setTextColor(0xFFFFFFFF); overlayText.setTextSize(11); overlayText.setLines(2);
        overlay.addView(overlayText);
        LinearLayout buttons = new LinearLayout(this);
        pauseButton = new Button(this); pauseButton.setText("暂停"); pauseButton.setTextSize(12);
        pauseButton.setOnClickListener(v -> togglePause());
        Button stop = new Button(this); stop.setText("停止"); stop.setTextSize(12); stop.setOnClickListener(v -> stopRun());
        int buttonHeight = Math.round(48 * getResources().getDisplayMetrics().density);
        buttons.addView(pauseButton, new LinearLayout.LayoutParams(0, buttonHeight, 1));
        buttons.addView(stop, new LinearLayout.LayoutParams(0, buttonHeight, 1));
        overlay.addView(buttons);
        WindowManager.LayoutParams params = new WindowManager.LayoutParams((int) (210 * getResources().getDisplayMetrics().density), WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL | WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON,
                android.graphics.PixelFormat.TRANSLUCENT);
        params.gravity = Gravity.TOP | Gravity.START; params.x = 10; params.y = 170;
        overlayParams = params;
        handle.setOnTouchListener(new View.OnTouchListener() {
            float x, y; int startX, startY;
            @Override public boolean onTouch(View view, MotionEvent event) {
                if (event.getAction() == MotionEvent.ACTION_DOWN) { x = event.getRawX(); y = event.getRawY(); startX = params.x; startY = params.y; return true; }
                if (event.getAction() == MotionEvent.ACTION_MOVE) {
                    params.x = Math.max(0, Math.min(getResources().getDisplayMetrics().widthPixels - params.width, startX + Math.round(event.getRawX() - x)));
                    params.y = Math.max(0, startY + Math.round(event.getRawY() - y));
                    if (overlay != null) windows.updateViewLayout(overlay, params);
                    return true;
                }
                if (event.getAction() == MotionEvent.ACTION_UP) { view.performClick(); return true; }
                return false;
            }
        });
        windows.addView(overlay, params);
    }

    private void hideOverlay() {
        if (overlay != null) windows.removeView(overlay);
        overlay = null; overlayText = null; pauseButton = null;
        overlayParams = null;
    }

    private Notification notification() {
        Intent open = new Intent(this, MainActivity.class);
        Intent stop = new Intent(this, ControlReceiver.class).setAction("cn.local.miaotask.STOP");
        return new Notification.Builder(this, "runner").setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(paused() ? "淘宝活动助手已暂停" : "淘宝活动助手 · Shizuku 运行中")
                .setContentText(paused() ? "处理当前页面后点击悬浮窗“继续”" : "保持手机解锁；点击停止可结束本轮")
                .setContentIntent(PendingIntent.getActivity(this, 0, open, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT))
                .addAction(new Notification.Action.Builder(null, "停止", PendingIntent.getBroadcast(this, 1, stop, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT)).build())
                .setOngoing(true).build();
    }

    private void notifyState() { if (running && notifications.areNotificationsEnabled()) notifications.notify(1, notification()); }

    private void finishRun() {
        running = false;
        hideOverlay();
        stopForeground(STOP_FOREGROUND_REMOVE);
        stopSelf();
    }

    @Override public void onTaskRemoved(Intent rootIntent) { stopRun(); super.onTaskRemoved(rootIntent); }

    @Override public void onDestroy() {
        if (control != null) control.stop();
        running = false;
        if (instance == this) instance = null;
        hideOverlay();
        if (bridge != null) bridge.disconnect();
        worker.shutdownNow(); controls.shutdownNow();
        if (recognizer != null) recognizer.close();
        if (notifications != null) notifications.cancel(1);
        super.onDestroy();
    }
}
