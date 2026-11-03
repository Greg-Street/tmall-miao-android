package cn.local.miaotask;

import org.junit.Test;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import static org.junit.Assert.*;

public class ImeSessionTest {
    private static final String OWN = "cn.local.miaotask.preview/cn.local.miaotask.SearchInputMethod";
    private static final String OLD = "com.keyboard/.KeyboardIme";

    private static final class Commands implements ImeSession.Commands {
        final List<List<String>> calls = new ArrayList<>();
        String current = OLD, enabled = OLD + ";123";
        String failAction = "", failComponent = "";
        boolean denySwitch;
        @Override public String run(String... args) throws IOException {
            calls.add(List.of(args));
            assertEquals("/system/bin/cmd", args[0]);
            if (args[1].equals("settings")) {
                assertEquals(List.of("--user", "10", "get", "secure"), List.of(args).subList(2, 6));
                return (args[6].equals("default_input_method") ? current : enabled) + "\n";
            }
            assertEquals(List.of("input_method", "ime"), List.of(args).subList(1, 3));
            assertEquals(List.of("--user", "10"), List.of(args).subList(4, 6));
            if (args[3].equals(failAction) && args[6].equals(failComponent)) {
                failAction = ""; throw new IOException("command failed");
            }
            if (args[3].equals("set") && !(denySwitch && args[6].equals(OWN))) current = args[6];
            return "Success";
        }
        boolean called(String action, String component) {
            return calls.stream().anyMatch(c -> c.get(1).equals("input_method") && c.get(3).equals(action) && c.get(6).equals(component));
        }
    }

    @Test public void aChineseSearchRestoresTheOriginalKeyboardAndDisablesTheTemporaryOne() throws Exception {
        Commands commands = new Commands(); ImeSession session = new ImeSession(commands);
        session.begin(OWN, 10); assertEquals(OWN, commands.current);
        assertTrue(commands.called("enable", OWN));
        session.restore(); assertEquals(OLD, commands.current);
        assertTrue(commands.called("disable", OWN));
        int count = commands.calls.size(); session.restore(); assertEquals(count, commands.calls.size());
    }
    @Test public void aPreviouslyEnabledTemporaryKeyboardIsNotDisabled() throws Exception {
        Commands commands = new Commands(); commands.enabled += ":" + OWN + ";456";
        ImeSession session = new ImeSession(commands); session.begin(OWN, 10); session.restore();
        assertEquals(OLD, commands.current); assertFalse(commands.called("enable", OWN));
        assertFalse(commands.called("disable", OWN));
    }
    @Test public void aFailedSwitchStillRestoresTheOriginalKeyboard() {
        Commands commands = new Commands(); commands.failAction = "set"; commands.failComponent = OWN;
        ImeSession session = new ImeSession(commands);
        assertThrows(IOException.class, () -> session.begin(OWN, 10));
        assertEquals(OLD, commands.current); assertTrue(commands.called("set", OLD));
        assertTrue(commands.called("disable", OWN));
    }
    @Test public void aSilentlyDeniedSystemSwitchDoesNotReportSuccess() {
        Commands commands = new Commands(); commands.denySwitch = true;
        assertThrows(IOException.class, () -> new ImeSession(commands).begin(OWN, 10));
        assertEquals(OLD, commands.current); assertTrue(commands.called("disable", OWN));
    }
    @Test public void restorationCanBeRetriedAfterACommandFailure() throws Exception {
        Commands commands = new Commands(); ImeSession session = new ImeSession(commands);
        session.begin(OWN, 10); commands.failAction = "set"; commands.failComponent = OLD;
        assertThrows(IOException.class, session::restore); assertEquals(OWN, commands.current);
        session.restore(); assertEquals(OLD, commands.current); assertTrue(commands.called("disable", OWN));
    }
    @Test public void invalidComponentsUsersAndMissingOriginalKeyboardsAreRejected() {
        Commands commands = new Commands(); ImeSession session = new ImeSession(commands);
        assertThrows(IllegalArgumentException.class, () -> session.begin("com.other/.Ime", 10));
        assertThrows(IllegalArgumentException.class, () -> session.begin(OWN, -1));
        assertTrue(commands.calls.isEmpty()); commands.current = "null";
        assertThrows(IOException.class, () -> session.begin(OWN, 10));
        assertFalse(commands.called("enable", OWN));
    }
    @Test public void searchTextAcceptsChineseProductsAndRejectsControlCharacters() {
        BridgeRules.searchText("手机壳"); BridgeRules.searchText("USB 数据线 2");
        for (String text : new String[]{"", "手机壳\n确认", "手机壳;input", "x".repeat(41)})
            assertThrows(IllegalArgumentException.class, () -> BridgeRules.searchText(text));
        assertThrows(IllegalArgumentException.class, () -> BridgeRules.searchText(null));
    }
    @Test public void theTemporaryInputOnlyCommitsToTaobaoTextFields() {
        assertTrue(BridgeRules.searchEditor(TaskRules.TAOBAO, 1));
        assertTrue(BridgeRules.searchEditor(TaskRules.TAOBAO, 0x200a1));
        for (int type : new int[]{0, 2, 0x81, 0x91, 0xe1}) assertFalse(BridgeRules.searchEditor(TaskRules.TAOBAO, type));
        assertFalse(BridgeRules.searchEditor("com.other.app", 1));
    }
}
