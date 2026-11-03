package cn.local.miaotask;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class BridgeRules {
    private BridgeRules() { }
    static final String COIN_URI = "taobao://m.taobao.com/tbopen/index.html?h5Url="
            + "https%3A%2F%2Fpages-fast.m.taobao.com%2Fwow%2Fz%2Ftmtjb%2Ftown%2Fhome%3FdisableNav%3DYES";

    static String foreground(String activities) {
        for (String field : new String[]{"topResumedActivity", "mTopResumedActivity", "mResumedActivity", "ResumedActivity"}) {
            Matcher found = Pattern.compile("(?m)^.*\\b" + field + "\\b[^\\r\\n]*?\\bu\\d+\\s+([A-Za-z0-9_]+(?:\\.[A-Za-z0-9_]+)+)/").matcher(activities);
            if (found.find()) return found.group(1);
        }
        return "";
    }

    static void text(String text) {
        if (text == null || !text.matches("[A-Za-z0-9 ]{1,80}")) throw new IllegalArgumentException("仅支持 1～80 位英文、数字和空格");
    }

    static void searchText(String text) {
        if (text == null || !text.matches("[\\u3400-\\u9FFFA-Za-z0-9 ]{1,40}"))
            throw new IllegalArgumentException("搜索词仅支持中文、英文、数字及空格");
    }

    static boolean searchEditor(String pkg, int inputType) {
        int variation = inputType & 0xff0;
        return TaskRules.TAOBAO.equals(pkg) && (inputType & 0xf) == 1
                && variation != 0x80 && variation != 0x90 && variation != 0xe0;
    }

    static String[] launchCommand(String component, int userId) {
        if (component == null || !component.matches("com\\.taobao\\.taobao/\\.?[A-Za-z0-9_$]+(?:\\.[A-Za-z0-9_$]+)*")
                || userId < 0 || userId > 21474) throw new IllegalArgumentException("淘宝启动入口或用户无效");
        return new String[]{"/system/bin/cmd", "activity", "start-activity", "--user", Integer.toString(userId),
                "-n", component, "-a", "android.intent.action.MAIN", "-c", "android.intent.category.LAUNCHER", "--activity-reorder-to-front"};
    }

    static String[] openCoinsCommand(int userId) {
        if (userId < 0 || userId > 21474) throw new IllegalArgumentException("用户无效");
        return new String[]{"/system/bin/cmd", "activity", "start-activity", "--user", Integer.toString(userId),
                "-a", "android.intent.action.VIEW", "-d", COIN_URI, "-p", TaskRules.TAOBAO, "-f", "0x10000000"};
    }

    static void gesture(float[] xs, float[] ys, long duration) {
        if (xs == null || ys == null || xs.length != ys.length || xs.length < 2 || xs.length > 128
                || duration < 10 || duration > 3000) throw new IllegalArgumentException("手势参数无效");
        for (int i = 0; i < xs.length; i++) {
            if (!Float.isFinite(xs[i]) || !Float.isFinite(ys[i]) || xs[i] < 0 || ys[i] < 0 || xs[i] > 16384 || ys[i] > 16384)
                throw new IllegalArgumentException("手势坐标无效");
        }
    }

    static boolean png(byte[] bytes) {
        byte[] signature = {(byte) 137, 80, 78, 71, 13, 10, 26, 10};
        if (bytes.length < 32) return false;
        for (int i = 0; i < signature.length; i++) if (bytes[i] != signature[i]) return false;
        int end = bytes.length - 8;
        return bytes[end] == 'I' && bytes[end + 1] == 'E' && bytes[end + 2] == 'N' && bytes[end + 3] == 'D';
    }

    static final class Gate {
        private boolean active;
        private long generation;
        synchronized void setActive(boolean value) { active = value; generation++; }
        synchronized long token() {
            if (!active) throw new IllegalStateException("执行已暂停或停止");
            return generation;
        }
        synchronized void check(long token) {
            if (!active || token != generation) throw new IllegalStateException("执行已暂停或停止");
        }
    }
}
