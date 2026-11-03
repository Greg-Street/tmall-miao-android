package cn.local.miaotask;

import org.junit.Test;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import static org.junit.Assert.*;
import cn.local.miaotask.TaskRules.Node;
import cn.local.miaotask.TaskRunner.Snapshot;

public class TaskRunnerFlowTest {
    private Node node(String text, int x, int y, int width) { return new Node(text, x, y, x + width, y + 65); }
    private void row(List<Node> nodes, String title, int y) {
        nodes.add(node(title, 260, y, 700)); nodes.add(node("浏览15秒+400", 260, y + 80, 700));
        nodes.add(node("去完成", 1150, y + 30, 220));
    }
    private Snapshot list(boolean completed) {
        List<Node> nodes = new ArrayList<>(List.of(node("做任务赚能量", 500, 630, 500)));
        if (!completed) row(nodes, "查看双11活动攻略(0/1)", 1110);
        row(nodes, "好物随心逛(0/10)", completed ? 1110 : 1380);
        row(nodes, "好物沉浸看(0/5)", completed ? 1380 : 1650);
        return new Snapshot(nodes, 1440, 3200, TaskRules.TAOBAO);
    }

    private final class FakeDriver implements TaskRunner.Driver {
        boolean opened, returned, receipt = true, ready = true;
        int reads, taps, swipes;
        @Override public Snapshot readForeground() { return new Snapshot(List.of(), 1440, 3200, TaskRules.TAOBAO); }
        @Override public Snapshot read() {
            if (!opened) return list(false);
            if (returned) return list(receipt);
            reads++;
            if (reads <= 2 || !ready) return new Snapshot(List.of(node("加载中…", 300, 1000, 500)), 1440, 3200, TaskRules.TAOBAO);
            List<Node> nodes = new ArrayList<>(List.of(node("双11攻略", 300, 500, 500), node("活动规则", 100, 900, 500), node("精选活动", 100, 1200, 500)));
            if (reads >= 6) nodes.add(node("任务已完成", 100, 1500, 600));
            return new Snapshot(nodes, 1440, 3200, TaskRules.TAOBAO);
        }
        @Override public void tap(Node node) { opened = true; taps++; }
        @Override public void swipe(boolean reverse) { swipes++; }
        @Override public void back() { returned = true; }
        @Override public void launchTaobao() { }
        @Override public boolean setSearchText(String text) { return false; }
    }

    @Test public void oneOffTaskRunsThroughLoadingBrowseAndReturnWithoutVerification() {
        AtomicLong clock = new AtomicLong();
        FakeDriver driver = new FakeDriver();
        List<String> logs = new ArrayList<>();
        AtomicLong browseAt = new AtomicLong();
        TaskRunner runner = new TaskRunner(driver, new TaskRunner.Control(clock::get, clock::addAndGet), message -> {
            logs.add(message);
            if (message.startsWith("浏览「") && browseAt.get() == 0) browseAt.set(clock.get());
        }, 1, true, false, 2, 0);
        runner.run();
        assertEquals(1, driver.taps);
        assertTrue("Blank frames and two readable destination frames must precede browsing", browseAt.get() >= 5400);
        assertFalse(logs.stream().anyMatch(m -> m.contains("核验")));
        assertTrue(runner.summary().contains("已执行 1"));
    }
    @Test public void unchangedServerProgressDoesNotPreventRecordingTheAction() {
        AtomicLong clock = new AtomicLong();
        AtomicLong recoveryAt = new AtomicLong();
        FakeDriver driver = new FakeDriver(); driver.receipt = false;
        List<String> logs = new ArrayList<>();
        TaskRunner runner = new TaskRunner(driver, new TaskRunner.Control(clock::get, clock::addAndGet), message -> {
            logs.add(message);
            if (message.startsWith("返回淘宝")) recoveryAt.set(clock.get());
        }, 1, true, false, 2, 0);
        runner.run();
        assertTrue(runner.summary().contains("已执行 1"));
        assertTrue(runner.summary().contains("异常 0"));
        assertFalse(logs.stream().anyMatch(m -> m.contains("核验")));
        assertTrue("No separate eight-second settlement wait after returning", clock.get() - recoveryAt.get() < 2000);
    }
    @Test public void blankTransitionIsNeverTreatedAsABrowsePage() {
        AtomicLong clock = new AtomicLong();
        FakeDriver driver = new FakeDriver(); driver.ready = false; driver.receipt = false;
        List<String> logs = new ArrayList<>();
        TaskRunner runner = new TaskRunner(driver, new TaskRunner.Control(clock::get, clock::addAndGet), logs::add, 1, true, false, 2, 0);
        runner.run();
        assertFalse(logs.stream().anyMatch(m -> m.startsWith("浏览「")));
        assertTrue(logs.stream().anyMatch(m -> m.contains("任务跳转未确认")));
        assertTrue(runner.summary().contains("已执行 0"));
    }
    @Test public void listIsRecheckedUpwardsBeforeDeclaringOnlySkippedTasksRemain() {
        AtomicLong clock = new AtomicLong();
        List<Node> nodes = new ArrayList<>(List.of(node("做任务赚能量", 500, 630, 500)));
        row(nodes, "订阅活动通知(0/1)", 1110);
        nodes.removeIf(n -> n.text.equals("浏览15秒+400"));
        nodes.add(node("完成订阅+1000", 260, 1190, 700));
        List<Boolean> directions = new ArrayList<>();
        TaskRunner.Driver driver = new TaskRunner.Driver() {
            public Snapshot read() { return new Snapshot(nodes, 1440, 3200, TaskRules.TAOBAO); }
            public void tap(Node node) { fail("A subscription must remain manual"); }
            public void swipe(boolean reverse) { directions.add(reverse); }
            public void back() { fail("The exhausted task list must stay open"); }
            public void launchTaobao() { }
            public boolean setSearchText(String text) { return false; }
        };
        TaskRunner runner = new TaskRunner(driver, new TaskRunner.Control(clock::get, clock::addAndGet), message -> {}, 2, true, false, 2, 0);
        runner.run();
        assertTrue(directions.contains(false)); assertTrue(directions.contains(true));
        assertTrue(directions.size() < 16);
        assertTrue(runner.summary().contains("尝试 0 次"));
    }
    @Test public void excludedAppsAreNeverTappedEvenWithCrossAppEnabled() {
        AtomicLong clock = new AtomicLong();
        List<Node> nodes = new ArrayList<>(List.of(node("做任务赚能量", 500, 630, 500)));
        row(nodes, "去点淘看直播领红包(0/1)", 1110);
        row(nodes, "去点海看真播领红包(0/1)", 1380);
        row(nodes, "去百度App领现金(0/1)", 1650);
        List<String> logs = new ArrayList<>();
        TaskRunner.Driver driver = new TaskRunner.Driver() {
            public Snapshot read() { return new Snapshot(nodes, 1440, 3200, TaskRules.TAOBAO); }
            public void tap(Node node) { fail("The user's excluded live task must never be opened"); }
            public void swipe(boolean reverse) { }
            public void back() { fail("The exhausted task list must stay open"); }
            public void launchTaobao() { }
            public boolean setSearchText(String text) { return false; }
        };
        TaskRunner runner = new TaskRunner(driver, new TaskRunner.Control(clock::get, clock::addAndGet), logs::add, 1, true, true, 2, 0);
        runner.run();
        assertTrue(logs.stream().anyMatch(m -> m.contains("跳过点淘任务")));
        assertTrue(logs.stream().anyMatch(m -> m.contains("跳过百度 App")));
        assertTrue(runner.summary().contains("尝试 0 次"));
    }
}
