package cn.local.miaotask;

import org.junit.Test;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import static org.junit.Assert.*;
import cn.local.miaotask.TaskRules.Node;
import cn.local.miaotask.TaskRunner.Snapshot;

public class NavigationRecoveryTest {
    private Node node(String text, int x, int y, int width) { return new Node(text, x, y, x + width, y + 65); }

    private final class Driver implements TaskRunner.Driver {
        final AtomicLong clock = new AtomicLong();
        final List<String> logs = new ArrayList<>();
        boolean panel, opened, returned, hideHeading, partial, closePanelOnReturn, unreadableOnReturn;
        int entryTaps, taskTaps, recoverySwipes, navigationReads, fullHomeReads;
        long recoveryAt;
        @Override public Snapshot readForeground() { return new Snapshot(List.of(), 1440, 3200, TaskRules.TAOBAO); }
        @Override public Snapshot readNavigation() { navigationReads++; return screen(); }
        @Override public Snapshot read() {
            if (!panel && !opened) fullHomeReads++;
            return screen();
        }
        private Snapshot screen() {
            List<Node> nodes = new ArrayList<>();
            if (opened) {
                nodes.add(node("精选好物", 100, 500, 500)); nodes.add(node("今日推荐", 100, 900, 500));
                nodes.add(node("任务已完成", 100, 1200, 500));
            } else if (unreadableOnReturn && returned) {
                nodes.add(node("做任务赚能量", 500, 630, 500));
            } else if (!panel) {
                nodes.add(node("淘金币双11狂享20亿", 100, 180, 1000));
                nodes.add(node("去赚金币 升级赛车拿钱 去赚能量", 20, 1200, 1400));
                nodes.add(node("活动好物推荐", 300, 1800, 700));
                nodes.add(TaskRules.energyEntry(List.of(node("去赚金币", 30, 1200, 200),
                        node("升级赛车拿钱", 350, 1200, 700), node("去赚", 1160, 1200, 100), node("能量", 1265, 1200, 100))));
            } else {
                if (!hideHeading && !partial) nodes.add(node("做任务赚能量", 500, 630, 500));
                nodes.add(node("好物随心逛(" + (returned ? "1" : "0") + "/5)", 260, 1110, 700));
                nodes.add(node("浏览15秒+400", 260, 1190, 700)); nodes.add(node("去完成", 1150, 1140, 220));
                if (!partial) {
                    nodes.add(node("订阅活动通知(0/1)", 260, 1380, 700));
                    nodes.add(node("完成订阅+1000", 260, 1460, 700)); nodes.add(node("去完成", 1150, 1410, 220));
                }
            }
            return new Snapshot(nodes, 1440, 3200, TaskRules.TAOBAO);
        }
        @Override public void tap(Node target) {
            if (target.text.equals("去赚能量")) { assertFalse(panel); entryTaps++; panel = true; }
            else { assertTrue(panel); taskTaps++; opened = true; }
        }
        @Override public void swipe(boolean reverse) {
            assertTrue("A partially recognized panel should be restored upwards", reverse);
            recoverySwipes++; partial = false;
        }
        @Override public void back() {
            if (!opened && unreadableOnReturn && returned) { panel = false; return; }
            assertTrue("Do not dismiss the panel or leave the activity during recovery", opened);
            opened = false; returned = true;
            if (closePanelOnReturn) panel = false;
        }
        @Override public void launchTaobao() { }
        @Override public boolean setSearchText(String text) { return false; }
        TaskRunner runner() {
            TaskRunner.Control control = new TaskRunner.Control(clock::get, clock::addAndGet);
            return new TaskRunner(this, control, message -> {
                logs.add(message);
                if (message.startsWith("返回淘宝")) recoveryAt = clock.get();
                if (message.startsWith("需要本人处理")) { assertTrue(control.paused()); control.stop(); }
            }, 1, true, false, 2, 0);
        }
    }

    @Test public void outsideActivityEntryOpensAndReopensTheTaskPanel() {
        Driver driver = new Driver(); driver.closePanelOnReturn = true;
        TaskRunner runner = driver.runner(); runner.run();
        assertEquals(2, driver.entryTaps); assertEquals(1, driver.taskTaps);
        assertTrue(driver.navigationReads >= 2); assertEquals(0, driver.fullHomeReads);
        assertTrue(driver.logs.contains("已找到「去赚能量」，打开任务列表"));
        assertFalse(driver.logs.stream().anyMatch(message -> message.contains("定位用时")));
        assertTrue(driver.clock.get() - driver.recoveryAt < 3000);
        assertTrue(runner.summary().contains("已执行 1"));
    }
    @Test public void scrolledRowsExecuteWithoutBackingOutToFindTheHeading() {
        Driver driver = new Driver(); driver.panel = true; driver.hideHeading = true;
        TaskRunner runner = driver.runner(); runner.run();
        assertEquals(0, driver.entryTaps); assertEquals(0, driver.recoverySwipes); assertEquals(1, driver.taskTaps);
        assertTrue(runner.summary().contains("已执行 1"));
    }
    @Test public void aSinglePartialRowIsRestoredUpwardsBeforeAnyBackAction() {
        Driver driver = new Driver(); driver.panel = true; driver.partial = true;
        TaskRunner runner = driver.runner(); runner.run();
        assertEquals(1, driver.recoverySwipes); assertEquals(0, driver.entryTaps); assertEquals(1, driver.taskTaps);
        assertTrue(runner.summary().contains("已执行 1"));
    }
    @Test public void unreadableReturnDoesNotWaitFiftySeconds() {
        Driver driver = new Driver(); driver.panel = true; driver.unreadableOnReturn = true;
        TaskRunner runner = driver.runner();
        assertThrows(TaskRunner.Stopped.class, runner::run);
        assertTrue(driver.clock.get() - driver.recoveryAt >= 20000);
        assertTrue(driver.clock.get() - driver.recoveryAt < 22000);
        assertEquals(2, driver.logs.stream().filter(message -> message.startsWith("自动恢复")).count());
        assertTrue(runner.summary().contains("已执行 1"));
    }
    @Test public void nativeEntryRequiresTheActivityPageAndDoesNotClickBehindTheTaskPanel() {
        List<Node> home = List.of(node("淘金币双11狂享20亿", 100, 180, 1000), node("去赚能量", 1150, 1200, 220));
        assertTrue(TaskRunner.energyEntryReady(new Snapshot(home, 1440, 3200, TaskRules.TAOBAO)));
        assertFalse(TaskRunner.energyEntryReady(new Snapshot(home, 1440, 3200, "com.other.app")));
        List<Node> modal = new ArrayList<>(home); modal.add(node("做任务赚能量", 500, 630, 500));
        assertFalse(TaskRunner.energyEntryReady(new Snapshot(modal, 1440, 3200, TaskRules.TAOBAO)));
        List<Node> oversized = List.of(home.get(0), node("去赚能量", 100, 1200, 1200));
        assertFalse(TaskRunner.energyEntryReady(new Snapshot(oversized, 1440, 3200, TaskRules.TAOBAO)));
        List<Node> partialPanel = new ArrayList<>(home);
        partialPanel.add(node("好物随心逛(0/5)", 260, 1610, 700));
        partialPanel.add(node("去完成", 1150, 1640, 220));
        assertFalse(TaskRunner.energyEntryReady(new Snapshot(partialPanel, 1440, 3200, TaskRules.TAOBAO)));
    }
    @Test public void theNativeEntryOpensTheMainListWithoutAnInitialHomeTaskScan() {
        AtomicLong clock = new AtomicLong();
        class FastDriver implements TaskRunner.Driver {
            boolean panel, opened, returned;
            int homeReads;
            long returnedAt;
            final List<Long> entryTimes = new ArrayList<>();
            Snapshot list() {
                return new Snapshot(List.of(node("做任务赚能量", 500, 630, 500),
                        node("逛逛闪购(" + (returned ? "1" : "0") + "/1)", 260, 1110, 700),
                        node("点击去逛+600", 260, 1190, 700), node(returned ? "已完成" : "去完成", 1150, 1140, 220)), 1440, 3200, TaskRules.TAOBAO);
            }
            public Snapshot readForeground() {
                if (!panel) return new Snapshot(List.of(node("淘金币双11狂享20亿", 100, 180, 1000),
                        node("去赚能量", 1150, 1200, 220)), 1440, 3200, TaskRules.TAOBAO);
                return opened ? new Snapshot(List.of(), 1440, 3200, TaskRules.TAOBAO) : list();
            }
            public Snapshot read() {
                if (!panel) { homeReads++; return readForeground(); }
                if (!opened) return list();
                return new Snapshot(List.of(node("淘宝闪购", 100, 500, 500), node("附近美食", 100, 900, 500),
                        node("更多好物", 100, 1200, 500)), 1440, 3200, TaskRules.TAOBAO);
            }
            public void tap(Node target) {
                if (target.text.equals("去赚能量")) { assertFalse(panel); entryTimes.add(clock.get()); panel = true; }
                else { assertTrue(panel); opened = true; }
            }
            public void swipe(boolean reverse) { fail("No scrolling is needed in this flow"); }
            public void back() { assertTrue(opened); returnedAt = clock.get(); opened = false; returned = true; panel = false; }
            public void launchTaobao() { }
            public boolean setSearchText(String text) { return false; }
        }
        FastDriver driver = new FastDriver();
        TaskRunner runner = new TaskRunner(driver, new TaskRunner.Control(clock::get, clock::addAndGet), message -> {}, 1, true, false, 0, 0);
        runner.run();
        assertEquals(2, driver.entryTimes.size());
        assertEquals(0, driver.homeReads);
        assertEquals(1800L, (long) driver.entryTimes.get(0));
        assertEquals(driver.returnedAt, (long) driver.entryTimes.get(1));
        assertTrue(runner.summary().contains("已执行 1"));
    }
}
