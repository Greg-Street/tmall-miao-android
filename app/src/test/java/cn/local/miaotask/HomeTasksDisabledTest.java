package cn.local.miaotask;

import org.junit.Test;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import static org.junit.Assert.*;
import cn.local.miaotask.TaskRules.Node;
import cn.local.miaotask.TaskRunner.Snapshot;

public class HomeTasksDisabledTest {
    static Node node(String text, int x, int y, int width) { return new Node(text, x, y, x + width, y + 65); }
    static List<Node> homeNodes() {
        return new ArrayList<>(List.of(node("淘金币双11狂享20亿", 100, 180, 1000),
                node("升级赛车拿钱", 350, 1180, 650), node("去赚能量", 1150, 1250, 220),
                node("足迹加抵", 50, 1500, 260), node("600", 350, 1465, 100),
                node("大牌抵扣", 500, 1500, 280), node("600", 800, 1465, 100),
                node("600", 1290, 1750, 100), node("搜索", 1050, 1780, 300),
                node("浏览15s赚600能量", 400, 2000, 650)));
    }
    private static final class Driver implements TaskRunner.Driver {
        final AtomicLong clock = new AtomicLong();
        final List<String> logs = new ArrayList<>();
        String phase = "home";
        boolean completed, coveredTitle;
        int taskTaps, entryTaps, taskReturns;
        Snapshot snapshot(List<Node> nodes) { return new Snapshot(nodes, 1440, 3200, TaskRules.TAOBAO); }
        public Snapshot read() {
            if (phase.equals("home")) {
                List<Node> nodes = homeNodes(); if (coveredTitle) nodes.removeIf(n -> n.text.startsWith("淘金币"));
                return snapshot(nodes);
            }
            if (phase.equals("browse")) return snapshot(List.of(node("精选好物", 100, 500, 500),
                    node("今日推荐", 100, 900, 500), node("任务已完成", 100, 1200, 500)));
            return snapshot(List.of(node("做任务赚能量", 500, 630, 500),
                    node("好物随心逛(" + (completed ? "1" : "0") + "/1)", 260, 1110, 700),
                    node("浏览5秒+400", 260, 1190, 700), node(completed ? "已完成" : "去完成", 1150, 1140, 220)));
        }
        public void tap(Node target) {
            if (phase.equals("home")) { assertEquals("去赚能量", target.text); entryTaps++; phase = "panel"; }
            else { assertEquals("panel", phase); assertEquals("去完成", target.text); taskTaps++; phase = "browse"; }
        }
        public void swipe(boolean reverse) { assertEquals("panel", phase); }
        public void back() {
            assertEquals("The completed main list must not be closed to inspect home tasks", "browse", phase);
            taskReturns++; completed = true; phase = "panel";
        }
        public void launchTaobao() { }
        public boolean setSearchText(String text) { fail("No home search task should be opened"); return false; }
        TaskRunner runner() { return new TaskRunner(this, new TaskRunner.Control(clock::get, clock::addAndGet), logs::add, 80, true, true, 2, 1); }
    }

    @Test public void markedHomeTasksAreIgnoredAndTheRunEndsAfterTheMainList() {
        Driver driver = new Driver(); TaskRunner runner = driver.runner(); runner.run();
        assertEquals(1, driver.entryTaps); assertEquals(1, driver.taskTaps); assertEquals(1, driver.taskReturns);
        assertEquals("panel", driver.phase); assertTrue(runner.summary().contains("已执行 1"));
        assertFalse(driver.logs.stream().anyMatch(s -> s.contains("首页任务") || s.contains("查看首页")));
    }
    @Test public void anAlreadyCompletedListEndsWithoutReturningToTheHome() {
        Driver driver = new Driver(); driver.phase = "panel"; driver.completed = true;
        driver.runner().run();
        assertEquals(0, driver.entryTaps); assertEquals(0, driver.taskTaps); assertEquals(0, driver.taskReturns);
        assertEquals("panel", driver.phase);
    }
    @Test public void aCoveredActivityTitleDoesNotPreventOpeningTheEnergyTaskList() {
        Driver driver = new Driver(); driver.coveredTitle = true; driver.runner().run();
        assertEquals(1, driver.entryTaps); assertEquals(1, driver.taskTaps); assertEquals("panel", driver.phase);
    }
    @Test public void homeEnergyBadgesDoNotBecomeTaskListRows() {
        assertTrue(TaskRules.parse(homeNodes(), 1440, 3200).isEmpty());
    }
    @Test public void fragmentedCoinEntryNavigationIsKeptWithoutHomeTaskCardLabels() {
        List<Node> entries = TaskRules.coinEntries(List.of(node("足迹加抵", 30, 500, 260),
                node("大牌抵扣", 400, 500, 260), node("领", 750, 500, 60), node("淘金币", 815, 500, 170)));
        assertEquals(2, entries.size()); assertEquals("领淘金币", entries.get(0).text);
        assertEquals("淘金币", entries.get(1).text);
        assertEquals(750, entries.get(0).left); assertEquals(985, entries.get(0).right);
    }
}
