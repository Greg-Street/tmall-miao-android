package cn.local.miaotask;

import org.junit.Test;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import static org.junit.Assert.*;
import cn.local.miaotask.TaskRules.Node;
import cn.local.miaotask.TaskRunner.Snapshot;

public class QuickVisitFlowTest {
    private static final String ALIPAY = "com.eg.android.AlipayGphone";
    private Node node(String text, int x, int y, int width) { return new Node(text, x, y, x + width, y + 65); }

    private final class Driver implements TaskRunner.Driver {
        final AtomicLong clock = new AtomicLong();
        boolean opened, returned, timed, noLaunch, login, delayedReturn, redirect;
        String target = ALIPAY;
        String title = "去蚂蚁森林收能量";
        String detail;
        int taps, foregroundReads, externalForegroundReads, externalOcr, externalSwipes, backRequests;
        int visitSeconds = 2;
        boolean directReturn;
        int directReturns;
        long tappedAt, returnedAt;
        Snapshot list() {
            List<Node> nodes = List.of(node("做任务赚能量", 500, 630, 500),
                    node(title + "(" + (returned ? "1" : "0") + "/1)", 260, 1110, 700),
                    node(detail != null ? detail : timed ? "浏览5秒+600" : "点击去逛+600", 260, 1190, 700),
                    node(returned ? "已完成" : "去完成", 1150, 1140, 220));
            return new Snapshot(nodes, 1440, 3200, TaskRules.TAOBAO);
        }
        @Override public Snapshot read() {
            if (!opened || returned || noLaunch) return list();
            externalOcr++;
            return new Snapshot(List.of(node(title.contains("闪购") ? "淘宝闪购" : "蚂蚁森林", 100, 500, 500),
                    node("精选推荐", 100, 900, 500), node("更多内容", 100, 1200, 500)), 1440, 3200, target);
        }
        @Override public Snapshot readForeground() {
            foregroundReads++;
            if (!opened || noLaunch || returned) return list();
            externalForegroundReads++;
            if (redirect && externalForegroundReads > 1) target = "com.other.redirect";
            return new Snapshot(login ? List.of(node("请先登录", 100, 800, 500)) : List.of(), 1440, 3200, target);
        }
        @Override public void tap(Node node) { opened = true; taps++; tappedAt = clock.get(); }
        @Override public void swipe(boolean reverse) { if (opened && !returned) externalSwipes++; }
        @Override public void back() {
            backRequests++;
            if (delayedReturn && backRequests == 1) return;
            if (opened) { returned = true; returnedAt = clock.get(); }
        }
        @Override public void launchTaobao() { }
        @Override public boolean openCoinPage() {
            if (!directReturn) return false;
            directReturns++; returned = true; returnedAt = clock.get(); return true;
        }
        @Override public boolean setSearchText(String text) { return false; }
        TaskRunner runner(List<String> logs, boolean crossApp) {
            TaskRunner.Control control = new TaskRunner.Control(clock::get, clock::addAndGet);
            return new TaskRunner(this, control, message -> {
                logs.add(message);
                if (message.startsWith("需要本人处理")) { assertTrue(control.paused()); control.stop(); }
            }, 1, true, crossApp, visitSeconds, 0);
        }
    }

    @Test public void externalVisitWaitsTwoSecondsAfterTheFirstConfirmedPackageWithoutOcr() {
        Driver driver = new Driver(); List<String> logs = new ArrayList<>();
        TaskRunner runner = driver.runner(logs, true); runner.run();
        assertEquals("The authorized return link needs no extra external OCR before leaving", 1, driver.externalForegroundReads);
        assertEquals(0, driver.externalOcr); assertEquals(0, driver.externalSwipes);
        assertEquals(2250, driver.returnedAt - driver.tappedAt);
        assertTrue(logs.stream().anyMatch(m -> m.contains("停留 2 秒后返回")));
        assertFalse(logs.stream().anyMatch(m -> m.startsWith("浏览「")));
        assertTrue(runner.summary().contains("已执行 1"));
    }
    @Test public void explicitBrowseSecondsStillRequireTheTimedVisit() {
        Driver driver = new Driver(); driver.timed = true; driver.visitSeconds = 30;
        TaskRunner runner = driver.runner(new ArrayList<>(), true); runner.run();
        assertEquals(0, driver.externalOcr); assertEquals(0, driver.externalSwipes);
        assertTrue(driver.returnedAt - driver.tappedAt >= 5250);
        assertTrue("Native jump detection should not add OCR loading or old padding", driver.returnedAt - driver.tappedAt < 6000);
        assertTrue(runner.summary().contains("已执行 1"));
    }
    @Test public void untimedFlashVisitUsesConfiguredTimeAfterConfirmingThePage() {
        for (int seconds : new int[]{1, 3, 30}) {
            Driver driver = new Driver(); driver.title = "逛逛闪购"; driver.target = TaskRules.TAOBAO;
            AtomicLong confirmedAt = new AtomicLong(-1);
            TaskRunner runner = new TaskRunner(driver, new TaskRunner.Control(driver.clock::get, driver.clock::addAndGet), message -> {
                if (message.contains("停留 " + seconds + " 秒后返回")) confirmedAt.set(driver.clock.get());
                assertFalse(message.startsWith("浏览「"));
            }, 1, true, false, seconds, 0);
            runner.run();
            assertEquals(driver.tappedAt + 900, confirmedAt.get());
            assertEquals(seconds * 1000L, driver.returnedAt - confirmedAt.get());
            assertTrue(runner.summary().contains("已执行 1"));
        }
    }
    @Test public void taobaoFirstRowJumpRewardRunsAsAVisitWithoutOtherPageInteractions() {
        Driver driver = new Driver(); driver.title = "去抽最高1万淘金币";
        driver.detail = "跳转得+400"; driver.target = TaskRules.TAOBAO;
        List<String> logs = new ArrayList<>(); TaskRunner runner = driver.runner(logs, false); runner.run();
        assertEquals(1, driver.taps); assertTrue(driver.returned);
        assertFalse(logs.stream().anyMatch(message -> message.startsWith("浏览「")));
        assertTrue(logs.stream().anyMatch(message -> message.contains("停留 2 秒后返回")));
        assertTrue(runner.summary().contains("已执行 1"));
    }
    @Test public void zeroVisitSecondsKeepsTheImmediateReturnOption() {
        Driver driver = new Driver(); driver.visitSeconds = 0;
        List<String> logs = new ArrayList<>(); TaskRunner runner = driver.runner(logs, true); runner.run();
        assertEquals(250, driver.returnedAt - driver.tappedAt);
        assertEquals(0, driver.externalOcr);
        assertTrue(logs.stream().anyMatch(m -> m.contains("立即返回")));
        assertTrue(runner.summary().contains("已执行 1"));
    }
    @Test public void stoppingDuringConfiguredVisitDelayDoesNotWaitForItToEnd() {
        Driver driver = new Driver(); AtomicLong confirmedAt = new AtomicLong(-1);
        TaskRunner.Control[] control = new TaskRunner.Control[1];
        control[0] = new TaskRunner.Control(driver.clock::get, ms -> {
            driver.clock.addAndGet(ms);
            if (confirmedAt.get() >= 0 && driver.clock.get() - confirmedAt.get() >= 500) control[0].stop();
        });
        TaskRunner runner = new TaskRunner(driver, control[0], message -> {
            if (message.contains("停留 30 秒后返回")) confirmedAt.set(driver.clock.get());
        }, 1, true, true, 30, 0);
        assertThrows(TaskRunner.Stopped.class, runner::run);
        assertEquals(500, driver.clock.get() - confirmedAt.get());
        assertFalse(driver.returned); assertEquals(0, driver.externalOcr);
    }
    @Test public void flashWithExplicitSecondsStillUsesTheRequiredBrowseTime() {
        Driver driver = new Driver(); driver.title = "逛逛闪购"; driver.target = TaskRules.TAOBAO; driver.timed = true;
        List<String> logs = new ArrayList<>(); TaskRunner runner = driver.runner(logs, false); runner.run();
        assertTrue(driver.returnedAt - driver.tappedAt >= 6800);
        assertTrue(driver.returnedAt - driver.tappedAt < 7500);
        assertTrue(driver.externalSwipes > 0);
        assertFalse(logs.stream().anyMatch(m -> m.contains("停留 1 秒后返回")));
        assertTrue(runner.summary().contains("已执行 1"));
    }
    @Test public void anUnconfirmedLaunchDoesNotCountAsCompleted() {
        Driver driver = new Driver(); driver.noLaunch = true;
        List<String> logs = new ArrayList<>(); TaskRunner runner = driver.runner(logs, true); runner.run();
        assertFalse(driver.returned);
        assertTrue(runner.summary().contains("已执行 1"));
        assertTrue(runner.summary().contains("异常 0"));
        assertTrue(logs.stream().anyMatch(m -> m.contains("页面已在任务列表")));
    }
    @Test public void aDifferentPackageIsAcceptedAsTheVisitWhenCrossAppIsEnabled() {
        Driver driver = new Driver(); driver.target = "com.unexpected.app";
        TaskRunner runner = driver.runner(new ArrayList<>(), true); runner.run();
        assertTrue(driver.returned); assertEquals(0, driver.externalOcr);
        assertEquals(2250, driver.returnedAt - driver.tappedAt);
        assertTrue(runner.summary().contains("已执行 1"));
    }
    @Test public void anUnmappedTitleUsesVisitDelayAfterAnyApplicationJump() {
        for (String title : List.of("去芝麻信用涨分攒进度", "查看神秘平台内容")) {
            Driver driver = new Driver(); driver.title = title;
            if (title.contains("神秘")) driver.detail = "点击进入+400";
            assertEquals("", TaskRules.externalPackage(title));
            List<String> logs = new ArrayList<>(); TaskRunner runner = driver.runner(logs, true); runner.run();
            assertEquals(2250, driver.returnedAt - driver.tappedAt);
            assertEquals(0, driver.externalOcr); assertEquals(0, driver.externalSwipes);
            assertFalse(logs.stream().anyMatch(m -> m.startsWith("浏览「")));
            assertTrue(runner.summary().contains("已执行 1"));
        }
    }
    @Test public void crossApplicationOffPausesAnUnexpectedJumpBeforeWaiting() {
        Driver driver = new Driver(); driver.title = "去芝麻信用涨分攒进度";
        List<String> logs = new ArrayList<>();
        assertThrows(TaskRunner.Stopped.class, () -> driver.runner(logs, false).run());
        assertTrue(logs.stream().anyMatch(message -> message.contains("未开启跨应用访问")));
        assertFalse(driver.returned); assertEquals(0, driver.externalOcr);
    }
    @Test public void aTimedVisitCanRedirectAgainWithoutPackageMatchingOrExternalOcr() {
        Driver driver = new Driver(); driver.timed = true; driver.redirect = true;
        TaskRunner runner = driver.runner(new ArrayList<>(), true); runner.run();
        assertEquals("com.other.redirect", driver.target);
        assertEquals(0, driver.externalOcr); assertEquals(0, driver.externalSwipes);
        assertTrue(driver.returnedAt - driver.tappedAt >= 5250);
        assertTrue(runner.summary().contains("已执行 1"));
    }
    @Test public void reportedWeiboTypoRunsOnlyWhenCrossApplicationVisitsAreEnabled() {
        for (boolean allowed : new boolean[]{false, true}) {
            Driver driver = new Driver(); driver.title = "去微傅看者热搜"; driver.target = "com.sina.weibo";
            TaskRunner runner = driver.runner(new ArrayList<>(), allowed); runner.run();
            assertEquals(allowed ? 1 : 0, driver.taps); assertEquals(allowed, driver.returned);
            assertEquals(0, driver.externalOcr);
            assertTrue(runner.summary().contains("已执行 " + (allowed ? "1" : "0")));
        }
    }
    @Test public void nativeLoginPromptStillRequiresTheUser() {
        Driver driver = new Driver(); driver.login = true;
        assertThrows(TaskRunner.Stopped.class, () -> driver.runner(new ArrayList<>(), true).run());
        assertFalse(driver.returned); assertEquals(0, driver.externalOcr);
    }
    @Test public void crossApplicationSwitchStillControlsWhetherVisitRuns() {
        Driver driver = new Driver(); TaskRunner runner = driver.runner(new ArrayList<>(), false); runner.run();
        assertEquals(0, driver.taps); assertEquals(0, driver.externalForegroundReads);
        assertTrue(runner.summary().contains("尝试 0 次"));
    }
    @Test public void recoveryDoesNotScreenshotTheExternalAppDuringAReturnTransition() {
        Driver driver = new Driver(); driver.delayedReturn = true;
        TaskRunner runner = driver.runner(new ArrayList<>(), true); runner.run();
        assertTrue(driver.backRequests >= 2);
        assertEquals(0, driver.externalOcr);
        assertTrue(runner.summary().contains("已执行 1"));
    }
    @Test public void xianyuReturnsByTheFixedChannelLinkWithoutWaitingForDoubleBackExit() {
        Driver driver = new Driver(); driver.title = "去闲鱼逛逛"; driver.target = "com.taobao.idlefish";
        driver.directReturn = true;
        TaskRunner runner = driver.runner(new ArrayList<>(), true); runner.run();
        assertEquals(1, driver.directReturns); assertEquals(0, driver.backRequests);
        assertEquals(2250, driver.returnedAt - driver.tappedAt);
        assertTrue(runner.summary().contains("已执行 1"));
    }
    @Test public void anInAppFlashVisitReturnsWithBackInsteadOfTheChannelLink() {
        Driver driver = new Driver(); driver.title = "逛逛闪购"; driver.target = TaskRules.TAOBAO;
        driver.directReturn = true;
        TaskRunner runner = driver.runner(new ArrayList<>(), true); runner.run();
        assertEquals(0, driver.directReturns); assertEquals(1, driver.backRequests);
        assertTrue(runner.summary().contains("已执行 1"));
    }
}
