package cn.local.miaotask;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.View;
import android.view.WindowInsets;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

public final class MainActivity extends Activity {
    private final Handler handler = new Handler(Looper.getMainLooper());
    private TextView serviceState, runtimeState, logs;
    private Button start, pause, stop;
    private EditText maxTasks, visitSeconds, browseExtraSeconds;
    private CheckBox autoEntry, crossApp;
    private ShizukuBridge bridge;
    private String lastLog = "";
    private final Runnable refresh = new Runnable() {
        @Override public void run() { update(); handler.postDelayed(this, 1000); }
    };

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        bridge = ShizukuBridge.get(this);
        var settings = getSharedPreferences("miao", MODE_PRIVATE);
        var defaults = settings.edit().remove("log");
        if (!settings.getBoolean("defaults019", false)) {
            defaults.putBoolean("defaults019", true).putInt("limit", 80).putBoolean("crossApp", true)
                    .putInt("visitSeconds", 2).putInt("browseExtraSeconds", 1);
        }
        defaults.apply();
        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(Color.rgb(250, 246, 240));
        scroll.setOnApplyWindowInsetsListener((view, insets) -> {
            android.graphics.Insets bars = insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom);
            return insets;
        });
        LinearLayout layout = new LinearLayout(this);
        layout.setPadding(dp(22), dp(20), dp(22), dp(30));
        layout.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(layout);
        addText(layout, getString(R.string.app_name), 30, 0xFF38251D);
        addText(layout, "Shizuku 版 · " + BuildConfig.VERSION_NAME, 14, 0xFF956042);
        addText(layout, getString(R.string.app_author), 14, 0xFF956042);
        addText(layout, getString(R.string.app_description), 14, 0xFF49413B);
        addText(layout, "首次使用", 20, 0xFF38251D);
        addText(layout, "1. 安装 Shizuku，在手机上通过无线调试启动。\n2. 回到本助手，授权 Shizuku 并允许悬浮窗。\n3. 淘宝登录后保持竖屏、解锁，点击开始。", 15, 0xFF49413B);
        addText(layout, "无需开启无障碍。手机重启后需重新启动 Shizuku。中文搜索会临时切换输入服务，完成后恢复原键盘。", 13, 0xFF77716B);
        serviceState = addText(layout, "", 14, 0xFF956042);
        button(layout, "打开 Shizuku", v -> {
            Intent intent = getPackageManager().getLaunchIntentForPackage("moe.shizuku.privileged.api");
            if (intent != null) startActivity(intent);
            else startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse("https://shizuku.rikka.app/download/")));
        });
        button(layout, "授权 Shizuku / 重新连接", v -> { bridge.requestPermission(); update(); });
        button(layout, "允许显示悬浮窗", v -> startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + getPackageName()))));
        button(layout, "打开本应用设置", v -> startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + getPackageName()))));
        addText(layout, "执行设置", 20, 0xFF38251D);
        autoEntry = new CheckBox(this); autoEntry.setText("自动打开淘宝并进入活动");
        autoEntry.setChecked(getSharedPreferences("miao", MODE_PRIVATE).getBoolean("autoEntry", true));
        layout.addView(autoEntry);
        crossApp = new CheckBox(this); crossApp.setText("允许跨应用访问");
        crossApp.setChecked(getSharedPreferences("miao", MODE_PRIVATE).getBoolean("crossApp", true));
        layout.addView(crossApp);
        addText(layout, "本轮尝试次数上限（1～80）", 14, 0xFF49413B);
        maxTasks = new EditText(this);
        maxTasks.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
        maxTasks.setSingleLine(true);
        maxTasks.setText(String.valueOf(getSharedPreferences("miao", MODE_PRIVATE).getInt("limit", 80)));
        layout.addView(maxTasks, new LinearLayout.LayoutParams(-1, dp(54)));
        addText(layout, "应用访问停留（秒，0～30）", 14, 0xFF49413B);
        visitSeconds = new EditText(this);
        visitSeconds.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
        visitSeconds.setSingleLine(true);
        visitSeconds.setContentDescription("应用访问停留秒数");
        visitSeconds.setText(String.valueOf(getSharedPreferences("miao", MODE_PRIVATE).getInt("visitSeconds", 2)));
        layout.addView(visitSeconds, new LinearLayout.LayoutParams(-1, dp(54)));
        addText(layout, "无浏览时长的访问任务使用此设置；填 0 立即返回。", 13, 0xFF77716B);
        addText(layout, "浏览额外停留（秒，0～30）", 14, 0xFF49413B);
        browseExtraSeconds = new EditText(this);
        browseExtraSeconds.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
        browseExtraSeconds.setSingleLine(true);
        browseExtraSeconds.setContentDescription("浏览额外停留秒数");
        browseExtraSeconds.setText(String.valueOf(getSharedPreferences("miao", MODE_PRIVATE).getInt("browseExtraSeconds", 1)));
        layout.addView(browseExtraSeconds, new LinearLayout.LayoutParams(-1, dp(54)));
        addText(layout, "在任务要求上补时；默认 1 秒，填 0 不补时。", 13, 0xFF77716B);
        start = button(layout, "开始执行", v -> begin());
        start.setTextColor(Color.WHITE); start.setBackgroundTintList(android.content.res.ColorStateList.valueOf(0xFFE35B27));
        LinearLayout controls = new LinearLayout(this);
        pause = new Button(this); pause.setText("暂停"); pause.setOnClickListener(v -> { if (MiaoService.instance != null) MiaoService.instance.togglePause(); update(); });
        stop = new Button(this); stop.setText("停止"); stop.setOnClickListener(v -> { if (MiaoService.instance != null) MiaoService.instance.stopRun(); update(); });
        controls.addView(pause, new LinearLayout.LayoutParams(0, dp(54), 1));
        controls.addView(stop, new LinearLayout.LayoutParams(0, dp(54), 1)); layout.addView(controls);
        runtimeState = addText(layout, "等待开始", 15, 0xFF956042);
        addText(layout, "异常时自动返回重试；需手动处理时保留悬浮窗，处理后点击“继续”。", 14, 0xFF49413B);
        addText(layout, "截图仅在手机内识别，不保存、不上传。操作次数不代表奖励到账，自动化操作仍可能触发平台限制。", 12, 0xFF77716B);
        addText(layout, "本轮运行记录", 20, 0xFF38251D);
        addText(layout, "开始新一轮时重置。", 13, 0xFF77716B);
        logs = addText(layout, "", 12, 0xFF5E5750);
        logs.setTextIsSelectable(true);
        setContentView(scroll);
        if (Build.VERSION.SDK_INT >= 33 && !getSharedPreferences("miao", MODE_PRIVATE).getBoolean("askedNotification", false)) {
            getSharedPreferences("miao", MODE_PRIVATE).edit().putBoolean("askedNotification", true).apply();
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 8);
        }
    }

    private void begin() {
        if (!bridge.ready()) { bridge.connect(); Toast.makeText(this, "请先启动、授权并连接 Shizuku", Toast.LENGTH_LONG).show(); return; }
        if (!Settings.canDrawOverlays(this)) { Toast.makeText(this, "请先允许本应用显示悬浮窗", Toast.LENGTH_LONG).show(); return; }
        int limit;
        try { limit = Integer.parseInt(maxTasks.getText().toString()); }
        catch (NumberFormatException e) { maxTasks.setError("请输入 1～80 的整数"); return; }
        if (limit < 1 || limit > 80) { maxTasks.setError("请输入 1～80 的整数"); return; }
        int visit = readSeconds(visitSeconds), extra = readSeconds(browseExtraSeconds);
        if (visit < 0 || extra < 0) return;
        getSharedPreferences("miao", MODE_PRIVATE).edit().putInt("limit", limit)
                .putBoolean("autoEntry", autoEntry.isChecked()).putBoolean("crossApp", crossApp.isChecked())
                .putInt("visitSeconds", visit).putInt("browseExtraSeconds", extra).apply();
        Intent intent = new Intent(this, MiaoService.class).putExtra("limit", limit)
                .putExtra("autoEntry", autoEntry.isChecked()).putExtra("crossApp", crossApp.isChecked())
                .putExtra("visitSeconds", visit).putExtra("browseExtraSeconds", extra);
        try { startForegroundService(intent); }
        catch (RuntimeException e) { Toast.makeText(this, "无法启动执行服务：" + e.getMessage(), Toast.LENGTH_LONG).show(); }
        update();
    }

    private int readSeconds(EditText field) {
        try {
            int value = Integer.parseInt(field.getText().toString().trim());
            if (value >= 0 && value <= 30) return value;
        } catch (NumberFormatException ignored) { }
        field.setError("请输入 0～30 的整数");
        return -1;
    }

    private void update() {
        MiaoService service = MiaoService.instance;
        boolean active = service != null && service.running();
        serviceState.setText(getString(R.string.connection_state, bridge.state(), Settings.canDrawOverlays(this) ? "已允许" : "未允许"));
        start.setEnabled(bridge.ready() && Settings.canDrawOverlays(this) && !active);
        pause.setEnabled(active); stop.setEnabled(active);
        pause.setText(service != null && service.paused() ? "继续" : "暂停");
        autoEntry.setEnabled(!active); crossApp.setEnabled(!active); maxTasks.setEnabled(!active);
        visitSeconds.setEnabled(!active); browseExtraSeconds.setEnabled(!active);
        runtimeState.setText(MiaoService.latestStatus());
        String text = MiaoService.latestHistory();
        if (!lastLog.equals(text)) { lastLog = text; logs.setText(text); }
    }

    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
    private TextView addText(LinearLayout parent, String value, int size, int color) {
        TextView text = new TextView(this); text.setText(value); text.setTextSize(size); text.setTextColor(color);
        text.setPadding(0, dp(9), 0, dp(6)); text.setLineSpacing(dp(3), 1);
        parent.addView(text); return text;
    }
    private Button button(LinearLayout parent, String text, View.OnClickListener click) {
        Button button = new Button(this); button.setText(text); button.setOnClickListener(click);
        parent.addView(button, new LinearLayout.LayoutParams(-1, dp(54))); return button;
    }
    @Override protected void onResume() { super.onResume(); bridge.connect(); handler.post(refresh); }
    @Override protected void onPause() { handler.removeCallbacks(refresh); super.onPause(); }
}
