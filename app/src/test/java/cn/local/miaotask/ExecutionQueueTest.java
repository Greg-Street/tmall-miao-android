package cn.local.miaotask;

import org.junit.Test;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import static org.junit.Assert.*;
import cn.local.miaotask.TaskRules.Node;
import cn.local.miaotask.TaskRunner.Snapshot;

public class ExecutionQueueTest {
    private Node node(String text, int x, int y, int width) { return new Node(text, x, y, x + width, y + 65); }
    private final class Driver implements TaskRunner.Driver {
        final AtomicLong clock = new AtomicLong();
        final List<Integer> taps = new ArrayList<>();
        final List<String> logs = new ArrayList<>();
        int current, firstCurrent, firstTotal = 2, secondCurrent, limit = 80;
        boolean opened, advances, claim;
        Snapshot list() {
            List<Node> nodes = new ArrayList<>(List.of(node("做任务赚能量", 500, 630, 500)));
            nodes.add(node((claim ? "领取任务奖励" : "好物随心逛") + "(" + firstCurrent + "/" + firstTotal + ")", 260, 1110, 700));
            nodes.add(node(claim ? "点击领取" : "浏览5秒+400", 260, 1190, 700));
            nodes.add(node(claim ? "立即领取" : "去完成", 1150, 1140, 220));
            nodes.add(node("好物沉浸看(" + secondCurrent + "/1)", 260, 1380, 700));
            nodes.add(node("浏览5秒+400", 260, 1460, 700)); nodes.add(node("去完成", 1150, 1410, 220));
            return new Snapshot(nodes, 1440, 3200, TaskRules.TAOBAO);
        }
        public Snapshot readForeground() { return new Snapshot(List.of(), 1440, 3200, TaskRules.TAOBAO); }
        public Snapshot read() {
            if (!opened) return list();
            return new Snapshot(List.of(node("精选好物", 100, 500, 500), node("今日推荐", 100, 900, 500),
                    node("任务已完成", 100, 1200, 500)), 1440, 3200, TaskRules.TAOBAO);
        }
        public void tap(Node target) { current = target.top > 1300 ? 1 : 0; taps.add(current); opened = !(claim && current == 0); }
        public void swipe(boolean reverse) { }
        public void back() {
            opened = false;
            if (advances) { if (current == 0) firstCurrent++; else secondCurrent++; }
        }
        public void launchTaobao() { }
        public boolean setSearchText(String text) { return false; }
        TaskRunner runner() {
            return new TaskRunner(this, new TaskRunner.Control(clock::get, clock::addAndGet), logs::add, limit, true, false, 2, 1);
        }
    }
    @Test public void unchangedProgressMovesToTheNextTaskAndStopsAtTheInitialRemainingCount() {
        Driver driver = new Driver(); TaskRunner runner = driver.runner(); runner.run();
        assertEquals(List.of(0, 0, 1), driver.taps);
        assertTrue(runner.summary().contains("已执行 3")); assertTrue(runner.summary().contains("异常 0"));
        assertFalse(driver.logs.stream().anyMatch(message -> message.contains("核验")));
    }
    @Test public void advancingServerProgressDoesNotDoubleCountAgainstTheLocalBudget() {
        Driver driver = new Driver(); driver.firstCurrent = 2; driver.firstTotal = 5;
        driver.secondCurrent = 1; driver.advances = true;
        TaskRunner runner = driver.runner(); runner.run();
        assertEquals(List.of(0, 0, 0), driver.taps); assertTrue(runner.summary().contains("已执行 3"));
    }
    @Test public void anUnchangedClaimButtonIsClickedOnceWithoutWaitingForReceivedState() {
        Driver driver = new Driver(); driver.claim = true; driver.firstTotal = 5; driver.secondCurrent = 1;
        TaskRunner runner = driver.runner(); runner.run();
        assertEquals(List.of(0), driver.taps); assertTrue(runner.summary().contains("领取点击 1"));
    }
    @Test public void theRoundLimitStillStopsTheExecutionQueue() {
        Driver driver = new Driver(); driver.limit = 2; TaskRunner runner = driver.runner(); runner.run();
        assertEquals(List.of(0, 0), driver.taps); assertTrue(runner.summary().contains("尝试 2 次"));
    }
}
