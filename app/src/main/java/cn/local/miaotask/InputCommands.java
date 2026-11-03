package cn.local.miaotask;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

final class InputCommands {
    private InputCommands() { }

    private static String[] command(int sdk, String... arguments) {
        List<String> command = new ArrayList<>();
        if (sdk >= 33) { command.add("/system/bin/cmd"); command.add("input"); }
        else command.add("/system/bin/input");
        Collections.addAll(command, arguments);
        return command.toArray(new String[0]);
    }

    static String[] gesture(int sdk, float[] xs, float[] ys, long duration) {
        BridgeRules.gesture(xs, ys, duration);
        int last = xs.length - 1;
        // A stationary swipe preserves a short press; moving paths are straight system swipes.
        return command(sdk, "touchscreen", "swipe", Float.toString(xs[0]), Float.toString(ys[0]),
                Float.toString(xs[last]), Float.toString(ys[last]), Long.toString(duration));
    }

    static String[] back(int sdk) { return command(sdk, "keyboard", "keyevent", "KEYCODE_BACK"); }

    static String[] submitSearch(int sdk) { return command(sdk, "keyboard", "keyevent", "KEYCODE_ENTER"); }

    static List<String[]> clearText(int sdk) {
        if (sdk >= 33) return List.of(command(sdk, "keyboard", "keycombination", "KEYCODE_CTRL_LEFT", "KEYCODE_A"),
                command(sdk, "keyboard", "keyevent", "KEYCODE_DEL"));
        List<String> keys = new ArrayList<>(List.of("keyboard", "keyevent", "KEYCODE_MOVE_END"));
        for (int i = 0; i < 80; i++) keys.add("KEYCODE_DEL");
        return Collections.singletonList(command(sdk, keys.toArray(new String[0])));
    }

    static String[] text(int sdk, String text) {
        BridgeRules.text(text);
        return command(sdk, "keyboard", "text", text);
    }
}
