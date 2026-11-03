package cn.local.miaotask;

import org.junit.Test;
import java.util.Arrays;
import java.util.Base64;
import static org.junit.Assert.*;

public class BridgeRulesTest {
    @Test public void theTopResumedAppWinsOverAnotherResumedOrHistoricalActivity() {
        String dump = "Hist #0: ActivityRecord{a u0 com.old.app/.Home t1}\n"
                + "mResumedActivity: ActivityRecord{b u0 com.other.app/.Home t2}\n"
                + "topResumedActivity=ActivityRecord{c u0 com.taobao.taobao/.TBMainActivity t3}";
        assertEquals(TaskRules.TAOBAO, BridgeRules.foreground(dump));
    }
    @Test public void androidVariantsAndMultiUserIdsAreAccepted() {
        for (String field : new String[]{"topResumedActivity", "mTopResumedActivity", "mResumedActivity", "ResumedActivity"})
            assertEquals("com.eg.android.AlipayGphone", BridgeRules.foreground("  " + field + ": ActivityRecord{x u10 com.eg.android.AlipayGphone/.Home t2}"));
    }
    @Test public void activityHistoryAndUnrelatedWindowTextCannotSelectTheForeground() {
        assertEquals("", BridgeRules.foreground("Hist #0: ActivityRecord{a u0 com.taobao.taobao/.Home t1}"));
        assertEquals("", BridgeRules.foreground("mCurrentFocus=Window{a u0 cn.local.miaotask.preview/.Overlay}"));
        assertEquals("", BridgeRules.foreground("topResumedActivity=null\nHist #0: ActivityRecord{a u0 com.old.app/.Home t1}"));
    }
    @Test public void asciiSearchNeedsNoClipboardOrShellExpansion() {
        BridgeRules.text("phonecase"); BridgeRules.text("iphone 15");
    }
    @Test public void taobaoLaunchUsesAnExplicitResolvedComponentAndTheAppsUser() {
        for (int user : new int[]{0, 10, 999}) {
            var command = Arrays.asList(BridgeRules.launchCommand("com.taobao.taobao/.Launcher", user));
            assertEquals("/system/bin/cmd", command.get(0)); assertEquals("activity", command.get(1));
            assertEquals("start-activity", command.get(2));
            assertEquals(Integer.toString(user), command.get(command.indexOf("--user") + 1));
            assertEquals("com.taobao.taobao/.Launcher", command.get(command.indexOf("-n") + 1));
            assertFalse(command.contains("/system/bin/am")); assertFalse(command.contains("-p"));
        }
        BridgeRules.launchCommand("com.taobao.taobao/com.taobao.launcher.Main$Alias", 0);
    }
    @Test public void anUnresolvedOrUnrelatedLaunchTargetIsRejected() {
        for (String component : new String[]{"com.taobao.taobao", "com.other.app/.Main", "com.taobao.taobao/", "com.taobao.taobao/.Main;id", "com.taobao.taobao/.Main\n"})
            assertThrows(IllegalArgumentException.class, () -> BridgeRules.launchCommand(component, 0));
        assertThrows(IllegalArgumentException.class, () -> BridgeRules.launchCommand(null, 0));
        assertThrows(IllegalArgumentException.class, () -> BridgeRules.launchCommand("com.taobao.taobao/.Main", -1));
    }
    @Test public void coinNavigationOnlyOpensTheFixedOfficialPageInsideTaobaoForTheAppsUser() throws Exception {
        for (int user : new int[]{0, 10, 999}) {
            var args = Arrays.asList(BridgeRules.openCoinsCommand(user));
            assertEquals(TaskRules.TAOBAO, args.get(args.indexOf("-p") + 1));
            assertEquals("android.intent.action.VIEW", args.get(args.indexOf("-a") + 1));
            assertEquals(Integer.toString(user), args.get(args.indexOf("--user") + 1));
            String uri = args.get(args.indexOf("-d") + 1);
            assertTrue(uri.startsWith("taobao://m.taobao.com/tbopen/index.html?h5Url="));
            assertEquals("https://pages-fast.m.taobao.com/wow/z/tmtjb/town/home?disableNav=YES",
                    java.net.URLDecoder.decode(uri.substring(uri.indexOf("h5Url=") + 6), "UTF-8"));
        }
    }
    @Test public void coinNavigationRejectsInvalidUserIds() {
        for (int user : new int[]{-1, 21475, Integer.MAX_VALUE})
            assertThrows(IllegalArgumentException.class, () -> BridgeRules.openCoinsCommand(user));
    }
    @Test public void arbitraryOrUnrepresentableTextIsRejected() {
        for (String text : new String[]{"", "手机壳", "phonecase;id", "$(id)", "hello\nworld", "x".repeat(81)})
            assertThrows(text, IllegalArgumentException.class, () -> BridgeRules.text(text));
        assertThrows(IllegalArgumentException.class, () -> BridgeRules.text(null));
    }
    @Test public void validTouchPathsKeepTheirCoordinateAndTimeBounds() {
        BridgeRules.gesture(new float[]{100, 120, 140}, new float[]{500, 600, 700}, 480);
        BridgeRules.gesture(new float[]{100, 100}, new float[]{500, 500}, 80);
    }
    @Test public void malformedPathsAreRejectedBeforeInjection() {
        assertThrows(IllegalArgumentException.class, () -> BridgeRules.gesture(null, null, 80));
        assertThrows(IllegalArgumentException.class, () -> BridgeRules.gesture(new float[]{1}, new float[]{1}, 80));
        assertThrows(IllegalArgumentException.class, () -> BridgeRules.gesture(new float[]{1, 2}, new float[]{1}, 80));
        assertThrows(IllegalArgumentException.class, () -> BridgeRules.gesture(new float[129], new float[129], 80));
    }
    @Test public void nonFiniteOffScreenOrUnboundedTouchPathsAreRejected() {
        for (float value : new float[]{Float.NaN, Float.POSITIVE_INFINITY, -1, 16385})
            assertThrows(IllegalArgumentException.class, () -> BridgeRules.gesture(new float[]{0, value}, new float[]{0, 1}, 80));
        for (long duration : new long[]{-1, 0, 9, 3001, Long.MAX_VALUE})
            assertThrows(IllegalArgumentException.class, () -> BridgeRules.gesture(new float[]{0, 1}, new float[]{0, 1}, duration));
    }
    @Test public void aPausedOrStoppedSessionRejectsEveryOldGestureToken() {
        BridgeRules.Gate gate = new BridgeRules.Gate();
        assertThrows(IllegalStateException.class, gate::token);
        gate.setActive(true); long token = gate.token(); gate.check(token);
        gate.setActive(false);
        assertThrows(IllegalStateException.class, () -> gate.check(token));
        gate.setActive(true); gate.check(gate.token());
        assertThrows(IllegalStateException.class, () -> gate.check(token));
    }
    @Test public void restartingAServiceSessionAlsoInvalidatesPendingActions() {
        BridgeRules.Gate gate = new BridgeRules.Gate(); gate.setActive(true); long old = gate.token();
        gate.setActive(true);
        assertThrows(IllegalStateException.class, () -> gate.check(old));
    }
    @Test public void screenshotPngMustBeCompleteInsteadOfCommandErrorText() {
        byte[] png = Base64.getDecoder().decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+aM1cAAAAASUVORK5CYII=");
        assertTrue(BridgeRules.png(png));
        assertFalse(BridgeRules.png(Arrays.copyOf(png, png.length - 4)));
        assertFalse(BridgeRules.png("Permission denied".getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        png[0] = 0; assertFalse(BridgeRules.png(png));
    }
}
