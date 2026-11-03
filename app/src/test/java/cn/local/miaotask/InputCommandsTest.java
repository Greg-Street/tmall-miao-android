package cn.local.miaotask;

import org.junit.Test;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import static org.junit.Assert.*;

public class InputCommandsTest {
    @Test public void androidThirteenAndNewerDelegateToTheSystemInputService() {
        for (int sdk : new int[]{33, 34, 35, 36})
            assertArrayEquals(new String[]{"/system/bin/cmd", "input", "keyboard", "keyevent", "KEYCODE_BACK"}, InputCommands.back(sdk));
    }
    @Test public void olderSupportedAndroidUsesItsOwnInputWrapper() {
        for (int sdk : new int[]{30, 31, 32})
            assertArrayEquals(new String[]{"/system/bin/input", "keyboard", "keyevent", "KEYCODE_BACK"}, InputCommands.back(sdk));
    }
    @Test public void stationaryPathsPreserveAShortPressAtTheRequestedCoordinates() {
        assertArrayEquals(new String[]{"/system/bin/cmd", "input", "touchscreen", "swipe", "100.5", "220.0", "100.5", "220.0", "80"},
                InputCommands.gesture(36, new float[]{100.5f, 100.5f}, new float[]{220, 220}, 80));
    }
    @Test public void swipesKeepTheRequestedDirectionAndDurationWithoutCallingHiddenApis() {
        assertArrayEquals(new String[]{"/system/bin/cmd", "input", "touchscreen", "swipe", "100.0", "220.0", "130.0", "600.0", "480"},
                InputCommands.gesture(36, new float[]{100, 120, 130}, new float[]{220, 450, 600}, 480));
        List<String> up = Arrays.asList(InputCommands.gesture(36, new float[]{130, 100}, new float[]{600, 220}, 700));
        assertEquals("700", up.get(up.size() - 1)); assertEquals("220.0", up.get(up.size() - 2));
    }
    @Test public void invalidCoordinatesAndUnboundedDurationsAreRejectedBeforeACommand() {
        assertThrows(IllegalArgumentException.class, () -> InputCommands.gesture(36, new float[]{0, Float.NaN}, new float[]{1, 2}, 80));
        assertThrows(IllegalArgumentException.class, () -> InputCommands.gesture(36, new float[]{0, 1}, new float[]{1, 2}, 3001));
    }
    @Test public void coordinateFormattingDoesNotDependOnThePhonesLocale() {
        Locale previous = Locale.getDefault();
        try {
            Locale.setDefault(Locale.GERMANY);
            var command = Arrays.asList(InputCommands.gesture(36, new float[]{10.5f, 10.5f}, new float[]{20.5f, 20.5f}, 80));
            assertEquals("10.5", command.get(4)); assertEquals("20.5", command.get(5));
        } finally { Locale.setDefault(previous); }
    }
    @Test public void modernSearchSelectsAndDeletesBeforeTypingWithoutClipboardAccess() {
        List<String[]> clear = InputCommands.clearText(36);
        assertEquals(2, clear.size());
        assertArrayEquals(new String[]{"/system/bin/cmd", "input", "keyboard", "keycombination", "KEYCODE_CTRL_LEFT", "KEYCODE_A"}, clear.get(0));
        assertEquals("KEYCODE_DEL", clear.get(1)[clear.get(1).length - 1]);
    }
    @Test public void olderSearchUsesOneBoundedKeyBatchInsteadOfAnUnsupportedCombination() {
        List<String[]> clear = InputCommands.clearText(30);
        assertEquals(1, clear.size());
        assertEquals(80, Arrays.stream(clear.get(0)).filter("KEYCODE_DEL"::equals).count());
        assertFalse(Arrays.asList(clear.get(0)).contains("keycombination"));
        assertTrue(Arrays.asList(clear.get(0)).contains("KEYCODE_MOVE_END"));
    }
    @Test public void textStaysOneArgumentAndCannotBecomeAShellCommand() {
        assertArrayEquals(new String[]{"/system/bin/cmd", "input", "keyboard", "text", "iphone 15"}, InputCommands.text(36, "iphone 15"));
        assertThrows(IllegalArgumentException.class, () -> InputCommands.text(36, "phonecase;id"));
        assertThrows(IllegalArgumentException.class, () -> InputCommands.text(36, "$(id)"));
    }
    @Test public void searchSubmissionSendsEnterThroughTheMatchingSystemEntry() {
        assertArrayEquals(new String[]{"/system/bin/cmd", "input", "keyboard", "keyevent", "KEYCODE_ENTER"}, InputCommands.submitSearch(36));
        assertArrayEquals(new String[]{"/system/bin/input", "keyboard", "keyevent", "KEYCODE_ENTER"}, InputCommands.submitSearch(30));
    }
}
