package cn.local.miaotask;

import java.io.IOException;
import java.util.Arrays;

final class ImeSession {
    interface Commands { String run(String... args) throws IOException; }
    private final Commands commands;
    private String previous, own;
    private int user;
    private boolean wasEnabled;

    ImeSession(Commands commands) { this.commands = commands; }

    synchronized void begin(String component, int userId) throws IOException {
        if (component == null || !component.matches("cn\\.local\\.miaotask(?:\\.preview)?/(?:cn\\.local\\.miaotask\\.SearchInputMethod|\\.SearchInputMethod)")
                || userId < 0 || userId > 21474) throw new IllegalArgumentException("临时输入服务无效");
        restore();
        String before = setting(userId, "default_input_method").trim();
        if (!before.matches("[A-Za-z0-9_]+(?:\\.[A-Za-z0-9_]+)+/\\.?[A-Za-z0-9_$]+(?:\\.[A-Za-z0-9_$]+)*")
                || before.equals(component)) throw new IOException("无法确认原输入法，请先在系统中切回常用键盘");
        String enabled = setting(userId, "enabled_input_methods");
        previous = before; own = component; user = userId;
        wasEnabled = Arrays.stream(enabled.trim().split(":"))
                .map(item -> item.split(";", 2)[0]).anyMatch(component::equals);
        try {
            if (!wasEnabled) ime("enable", own);
            ime("set", own);
            if (!setting(user, "default_input_method").trim().equals(own)) throw new IOException("系统未允许临时中文输入");
        } catch (IOException failure) {
            try { restore(); } catch (IOException cleanup) { failure.addSuppressed(cleanup); }
            throw failure;
        }
    }

    synchronized void restore() throws IOException {
        if (previous == null) return;
        ime("set", previous);
        if (!wasEnabled) ime("disable", own);
        previous = null; own = null;
    }

    private String setting(int userId, String name) throws IOException {
        return commands.run("/system/bin/cmd", "settings", "--user", Integer.toString(userId), "get", "secure", name);
    }

    private void ime(String action, String component) throws IOException {
        commands.run("/system/bin/cmd", "input_method", "ime", action, "--user", Integer.toString(user), component);
    }
}
