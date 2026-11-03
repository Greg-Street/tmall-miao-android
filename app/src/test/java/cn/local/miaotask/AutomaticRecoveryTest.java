package cn.local.miaotask;

import org.junit.Test;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import static org.junit.Assert.*;
import cn.local.miaotask.TaskRules.Node;
import cn.local.miaotask.TaskRunner.Snapshot;

public class AutomaticRecoveryTest {
    private Node node(String text, int x, int y, int width) { return new Node(text, x, y, x + width, y + 65); }

    private final class Driver implements TaskRunner.Driver {
        final AtomicLong clock = new AtomicLong();
        final TaskRunner.Control control = new TaskRunner.Control(clock::get, clock::addAndGet);
        final List<String> logs = new ArrayList<>();
        final int[] tapFailures = {0, 0};
        final int[] totals = {1, 1};
        final boolean[] done = {false, false};
        String phase = "list";
        int readFailures, taskTaps, entryTaps, backs, launches, current, limit = 80;
        boolean guardAfterError, stopOnRecovery, failBack;
        Snapshot snapshot(List<Node> nodes) { return new Snapshot(nodes, 1440, 3200, TaskRules.TAOBAO); }
        Snapshot home() {
            return snapshot(List.of(node("淘金币", 100, 180, 500), node("去赚能量", 1150, 1200, 220)));
        }
        @Override public Snapshot readForeground() {
            if (guardAfterError && phase.equals("error")) return snapshot(List.of(node("请先登录", 100, 800, 500)));
            return phase.equals("home") ? home() : snapshot(List.of());
        }
        @Override public Snapshot read() {
            if (readFailures > 0) {
                readFailures--; phase = "error";
                throw new TaskRunner.Attention("离线页面识别失败", true);
            }
            if (phase.equals("home")) return home();
            if (phase.equals("browse")) return snapshot(List.of(node("精选好物", 100, 500, 500),
                    node("今日推荐", 100, 900, 500), node("任务已完成", 100, 1200, 500)));
            if (phase.equals("error")) return snapshot(List.of());
            List<Node> nodes = new ArrayList<>(List.of(node("做任务赚能量", 500, 630, 500)));
            String[] titles = {"好物随心逛", "好物沉浸看"};
            for (int i = 0; i < titles.length; i++) {
                int y = 1110 + i * 270;
                nodes.add(node(titles[i] + "(" + (done[i] ? totals[i] : 0) + "/" + totals[i] + ")", 260, y, 700));
                nodes.add(node("浏览5秒+400", 260, y + 80, 700));
                nodes.add(node(done[i] ? "已完成" : "去完成", 1150, y + 30, 220));
            }
            return snapshot(nodes);
        }
        @Override public void tap(Node target) {
            assertTrue(control.actionable());
            if (target.text.equals("去赚能量")) { entryTaps++; phase = "list"; return; }
            current = target.top > 1300 ? 1 : 0;
            taskTaps++;
            if (tapFailures[current] > 0) {
                tapFailures[current]--; phase = "error";
                throw new TaskRunner.Attention("任务跳转失败", true);
            }
            phase = "browse";
        }
        @Override public void back() {
            assertTrue(control.actionable()); backs++;
            if (failBack) { failBack = false; throw new TaskRunner.Attention("系统返回操作失败", true); }
            if (phase.equals("browse")) done[current] = true;
            phase = "home";
        }
        @Override public void launchTaobao() {
            assertTrue(control.actionable()); launches++;
            if (launches > 1) phase = "home";
        }
        @Override public void swipe(boolean reverse) { assertTrue(control.actionable()); }
        @Override public boolean setSearchText(String text) { return false; }
        TaskRunner runner() {
            return new TaskRunner(this, control, message -> {
                logs.add(message);
                if (stopOnRecovery && message.startsWith("自动恢复")) control.stop();
                if (message.startsWith("需要本人处理")) { assertTrue(control.paused()); control.stop(); }
            }, limit, true, true, 2, 1);
        }
        long recoveries() { return logs.stream().filter(message -> message.startsWith("自动恢复")).count(); }
    }

    @Test public void transientReadFailureBacksOutAndReopensTheEnergyListWithoutPausing() {
        Driver driver = new Driver(); driver.readFailures = 1; driver.limit = 1;
        TaskRunner runner = driver.runner(); runner.run();
        assertEquals(1, driver.recoveries()); assertTrue(driver.entryTaps >= 1);
        assertEquals(1, driver.launches); assertTrue(driver.done[0]);
        assertTrue(runner.summary().contains("已执行 1"));
    }
    @Test public void interruptedOneOffTaskMovesOnAfterRecoveryWithoutResettingCounters() {
        Driver driver = new Driver(); driver.tapFailures[0] = 1; driver.limit = 2;
        TaskRunner runner = driver.runner(); runner.run();
        assertEquals(1, driver.recoveries()); assertEquals(2, driver.taskTaps);
        assertFalse(driver.done[0]); assertTrue(driver.done[1]);
        assertTrue(runner.summary().contains("尝试 2 次"));
        assertTrue(runner.summary().contains("已执行 1")); assertTrue(runner.summary().contains("异常 1"));
    }
    @Test public void aBadOneOffTaskDoesNotConsumeOtherTasksOrRepeatItsClick() {
        Driver driver = new Driver(); driver.tapFailures[0] = 99; driver.limit = 3;
        TaskRunner runner = driver.runner(); runner.run();
        assertEquals(1, driver.recoveries()); assertEquals(1, driver.launches);
        assertFalse(driver.done[0]); assertTrue(driver.done[1]); assertEquals(2, driver.taskTaps);
        assertTrue(runner.summary().contains("已执行 1")); assertTrue(runner.summary().contains("异常 1"));
    }
    @Test public void persistentReadFailurePausesAfterTwoRecoveryAttemptsEvenBeforeAnyTask() {
        Driver driver = new Driver(); driver.readFailures = 99;
        TaskRunner runner = driver.runner(); assertThrows(TaskRunner.Stopped.class, runner::run);
        assertEquals(2, driver.recoveries()); assertEquals(2, driver.launches);
        assertEquals(0, driver.taskTaps); assertTrue(runner.summary().contains("尝试 0 次"));
    }
    @Test public void aGuardFoundDuringRecoveryPausesBeforeBackOrAnotherLaunch() {
        Driver driver = new Driver(); driver.readFailures = 1; driver.guardAfterError = true;
        assertThrows(TaskRunner.Stopped.class, () -> driver.runner().run());
        assertEquals(1, driver.recoveries()); assertEquals(0, driver.backs);
        assertEquals(1, driver.launches); assertEquals(0, driver.taskTaps);
    }
    @Test public void userStopPreventsAnyRecoveryGesture() {
        Driver driver = new Driver(); driver.readFailures = 1; driver.stopOnRecovery = true;
        assertThrows(TaskRunner.Stopped.class, () -> driver.runner().run());
        assertEquals(1, driver.recoveries()); assertEquals(0, driver.backs); assertEquals(1, driver.launches);
    }
    @Test public void theLastFailedAttemptStillRestoresTheListWithoutExceedingTheLimit() {
        Driver driver = new Driver(); driver.tapFailures[0] = 1; driver.limit = 1;
        TaskRunner runner = driver.runner(); runner.run();
        assertEquals(1, driver.recoveries()); assertEquals(1, driver.taskTaps); assertEquals("list", driver.phase);
        assertTrue(runner.summary().contains("已执行 0")); assertTrue(runner.summary().contains("异常 1"));
    }
    @Test public void anErrorDuringRecoveryEscalatesInsteadOfEndingTheRun() {
        Driver driver = new Driver(); driver.readFailures = 1; driver.failBack = true; driver.limit = 1;
        TaskRunner runner = driver.runner(); runner.run();
        assertEquals(2, driver.recoveries()); assertEquals(2, driver.launches);
        assertTrue(runner.summary().contains("已执行 1"));
    }
    @Test public void aCompletedTaskResetsTheConsecutiveRecoveryBudgetForTheNextTask() {
        Driver driver = new Driver(); driver.tapFailures[0] = 1; driver.tapFailures[1] = 99;
        driver.totals[0] = 2; driver.totals[1] = 2;
        TaskRunner runner = driver.runner(); runner.run();
        assertEquals(3, driver.recoveries()); assertEquals(2, driver.launches);
        assertTrue(driver.done[0]); assertFalse(driver.done[1]); assertEquals(4, driver.taskTaps);
        assertTrue(runner.summary().contains("已执行 1")); assertTrue(runner.summary().contains("异常 3"));
    }
}
