package cn.local.miaotask;

import android.os.ParcelFileDescriptor;
import android.os.Build;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class ShizukuService extends IShizukuService.Stub {
    private final BoundedCommand commands = new BoundedCommand();
    private final BoundedCommand imeCommands = new BoundedCommand();
    private final ImeSession ime = new ImeSession(args -> new String(imeCommands.run(4000, 8192, args), StandardCharsets.UTF_8));
    private final BridgeRules.Gate gate = new BridgeRules.Gate();
    private final ExecutorService images = Executors.newSingleThreadExecutor();
    private final Object inputLock = new Object();

    public ShizukuService() { }

    @Override public int protocolVersion() { return 5; }

    @Override public int executionUid() { return android.os.Process.myUid(); }

    @Override public void setActive(boolean active) {
        gate.setActive(active);
        if (!active) { commands.cancel(); endChineseInput(); }
    }

    @Override public String foreground() {
        long token = gate.token();
        try {
            String result = BridgeRules.foreground(new String(commands.run(4000, 2 * 1024 * 1024,
                    "/system/bin/dumpsys", "activity", "activities"), StandardCharsets.UTF_8));
            gate.check(token); return result;
        }
        catch (IOException e) { throw new IllegalStateException("无法读取前台应用：" + e.getMessage()); }
    }

    @Override public ParcelFileDescriptor capture() {
        long token = gate.token();
        try {
            ParcelFileDescriptor[] pipe = ParcelFileDescriptor.createReliablePipe();
            images.execute(() -> {
                try {
                    gate.check(token);
                    byte[] png = commands.run(8000, 32 * 1024 * 1024, "/system/bin/screencap", "-p");
                    gate.check(token);
                    if (!BridgeRules.png(png)) throw new IOException("截图未返回完整 PNG");
                    try (var output = new ParcelFileDescriptor.AutoCloseOutputStream(pipe[1])) { output.write(png); }
                } catch (Exception e) {
                    try { pipe[1].closeWithError("Shizuku 截图失败：" + e.getMessage()); } catch (IOException ignored) { }
                }
            });
            return pipe[0];
        } catch (IOException e) { throw new IllegalStateException("无法创建截图管道：" + e.getMessage()); }
    }

    private void requireForeground(String expected, long token) {
        gate.check(token);
        String current = foreground();
        if (expected == null || expected.isEmpty() || !expected.equals(current))
            throw new IllegalStateException("前台应用已变化，取消操作");
        gate.check(token);
    }

    private void runInput(String[] command, String expectedPackage, long token) {
        requireForeground(expectedPackage, token);
        try {
            commands.run(6000, 128 * 1024, command);
            gate.check(token);
        } catch (IOException e) { throw new IllegalStateException("系统输入命令失败：" + e.getMessage()); }
    }

    @Override public void gesture(float[] xs, float[] ys, long duration, String expectedPackage) {
        String[] command = InputCommands.gesture(Build.VERSION.SDK_INT, xs, ys, duration);
        synchronized (inputLock) {
            long token = gate.token(); runInput(command, expectedPackage, token);
        }
    }

    @Override public void back(String expectedPackage) {
        synchronized (inputLock) {
            long token = gate.token(); runInput(InputCommands.back(Build.VERSION.SDK_INT), expectedPackage, token);
        }
    }

    @Override public void inputText(String text, String expectedPackage) {
        BridgeRules.text(text);
        synchronized (inputLock) {
            long token = gate.token();
            for (String[] command : InputCommands.clearText(Build.VERSION.SDK_INT)) runInput(command, expectedPackage, token);
            runInput(InputCommands.text(Build.VERSION.SDK_INT, text), expectedPackage, token);
        }
    }

    @Override public void beginChineseInput(String component, int userId) {
        synchronized (inputLock) {
            long token = gate.token(); requireForeground(TaskRules.TAOBAO, token);
            try { ime.begin(component, userId); gate.check(token); }
            catch (IOException e) { throw new IllegalStateException("启用临时中文输入失败：" + e.getMessage()); }
        }
    }

    @Override public void endChineseInput() {
        try { ime.restore(); }
        catch (IOException e) { throw new IllegalStateException("恢复原键盘失败：" + e.getMessage()); }
    }

    @Override public void submitSearch(String expectedPackage) {
        synchronized (inputLock) {
            long token = gate.token(); runInput(InputCommands.submitSearch(Build.VERSION.SDK_INT), expectedPackage, token);
        }
    }

    @Override public void launchTaobao(String component, int userId) {
        long token = gate.token();
        try {
            commands.run(6000, 128 * 1024, BridgeRules.launchCommand(component, userId));
            gate.check(token);
        } catch (IOException e) { throw new IllegalStateException("打开淘宝失败：" + e.getMessage()); }
    }

    @Override public void openCoinPage(int userId) {
        long token = gate.token();
        try {
            commands.run(6000, 128 * 1024, BridgeRules.openCoinsCommand(userId));
            gate.check(token);
        } catch (IOException e) { throw new IllegalStateException("打开淘金币失败：" + e.getMessage()); }
    }

    @Override public void destroy() {
        gate.setActive(false); commands.close();
        try { endChineseInput(); }
        finally {
            imeCommands.close(); images.shutdownNow();
            synchronized (inputLock) { System.exit(0); }
        }
    }
}
