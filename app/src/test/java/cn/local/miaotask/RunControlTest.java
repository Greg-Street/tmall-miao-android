package cn.local.miaotask;

import org.junit.Test;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import static org.junit.Assert.*;

public class RunControlTest {
    @Test public void pauseDoesNotCountAsBrowseTimeAndResumeRescans() throws Exception {
        TaskRunner.Control control = new TaskRunner.Control();
        control.setPaused(true);
        long before = control.now();
        Thread.sleep(80);
        assertTrue(control.now() - before < 15);
        control.setPaused(false);
        assertThrows(TaskRunner.Rescan.class, control::check);
        control.check();
    }
    @Test public void stopUnblocksAPausedWorker() throws Exception {
        TaskRunner.Control control = new TaskRunner.Control(); control.setPaused(true);
        var pool = Executors.newSingleThreadExecutor();
        try {
            var future = pool.submit(() -> assertThrows(TaskRunner.Stopped.class, control::check));
            control.stop(); future.get(2, TimeUnit.SECONDS);
        } finally { pool.shutdownNow(); }
        assertFalse(control.actionable());
    }
    @Test public void safetyPagePausesBeforeAnyTapAndCanBeStopped() {
        AtomicInteger taps = new AtomicInteger();
        TaskRunner.Control control = new TaskRunner.Control();
        var runner = new TaskRunner(securityDriver(taps), control, text -> {
            if (text.startsWith("需要本人处理")) { assertTrue(control.paused()); control.stop(); }
        }, 2, true, false, 2, 0);
        assertThrows(TaskRunner.Stopped.class, runner::run);
        assertEquals(0, taps.get());
    }
    @Test public void resumeDuringStartupRescansInsteadOfEndingRun() {
        AtomicInteger taps = new AtomicInteger();
        TaskRunner.Control control = new TaskRunner.Control();
        control.setPaused(true); control.setPaused(false);
        var runner = new TaskRunner(securityDriver(taps), control, text -> {
            if (text.startsWith("需要本人处理")) { assertTrue(control.paused()); control.stop(); }
        }, 2, true, false, 2, 0);
        assertThrows(TaskRunner.Stopped.class, runner::run);
        assertEquals(0, taps.get());
    }
    @Test public void attentionKeepsTheWorkerAliveUntilTheUserResumesAndRescans() throws Exception {
        AtomicInteger taps = new AtomicInteger(), reads = new AtomicInteger(), launches = new AtomicInteger(), backs = new AtomicInteger();
        CountDownLatch waiting = new CountDownLatch(1);
        AtomicBoolean blocked = new AtomicBoolean(true);
        TaskRunner.Driver driver = new TaskRunner.Driver() {
            public TaskRunner.Snapshot read() {
                reads.incrementAndGet();
                return new TaskRunner.Snapshot(List.of(new TaskRules.Node(blocked.get() ? "请完成安全验证" : "做任务赚能量", 0, 100, 600, 200),
                        new TaskRules.Node("好物随心逛(1/1)", 260, 1110, 960, 1175),
                        new TaskRules.Node("已完成", 1150, 1140, 1370, 1205)), 1440, 3200, TaskRules.TAOBAO);
            }
            public void tap(TaskRules.Node node) { taps.incrementAndGet(); }
            public void swipe(boolean reverse) { }
            public void back() { backs.incrementAndGet(); }
            public void launchTaobao() { launches.incrementAndGet(); }
            public boolean setSearchText(String text) { taps.incrementAndGet(); return false; }
        };
        AtomicLong clock = new AtomicLong();
        TaskRunner.Control control = new TaskRunner.Control(clock::get, clock::addAndGet);
        var runner = new TaskRunner(driver, control, text -> {
            if (text.startsWith("需要本人处理")) waiting.countDown();
        }, 80, true, true, 2, 0);
        var pool = Executors.newSingleThreadExecutor();
        try {
            var future = pool.submit(runner::run);
            assertTrue(waiting.await(2, TimeUnit.SECONDS));
            assertTrue(control.paused()); assertFalse(future.isDone()); assertEquals(0, taps.get());
            int before = reads.get(); Thread.sleep(80); assertEquals(before, reads.get());
            blocked.set(false); control.setPaused(false);
            future.get(2, TimeUnit.SECONDS);
            assertTrue(reads.get() > before); assertEquals(0, taps.get()); assertEquals(1, launches.get());
            assertEquals("The completed task list stays open when the run ends", 0, backs.get());
            assertTrue(runner.summary().contains("尝试 0 次"));
        } finally { control.stop(); pool.shutdownNow(); }
    }
    @Test public void lastAttemptStillWaitsForTheUserAndStopUnblocksTheRun() throws Exception {
        AtomicLong clock = new AtomicLong();
        TaskRunner.Control control = new TaskRunner.Control(clock::get, clock::addAndGet);
        CountDownLatch waiting = new CountDownLatch(1);
        AtomicInteger taps = new AtomicInteger();
        TaskRunner.Driver driver = new TaskRunner.Driver() {
            public TaskRunner.Snapshot read() {
                return new TaskRunner.Snapshot(List.of(new TaskRules.Node("做任务赚能量", 500, 630, 1000, 695),
                        new TaskRules.Node("好物随心逛(0/1)", 260, 1110, 960, 1175),
                        new TaskRules.Node("浏览5秒", 260, 1190, 960, 1255),
                        new TaskRules.Node("去完成", 1150, 1140, 1370, 1205)), 1440, 3200, TaskRules.TAOBAO);
            }
            public void tap(TaskRules.Node node) { taps.incrementAndGet(); throw new TaskRunner.Attention("请本人处理"); }
            public void swipe(boolean reverse) { fail("No gesture after attention"); }
            public void back() { fail("No Back after attention"); }
            public void launchTaobao() { }
            public boolean setSearchText(String text) { return false; }
        };
        var runner = new TaskRunner(driver, control, text -> {
            if (text.startsWith("需要本人处理")) waiting.countDown();
        }, 1, true, true, 2, 0);
        var pool = Executors.newSingleThreadExecutor();
        try {
            var future = pool.submit(() -> assertThrows(TaskRunner.Stopped.class, runner::run));
            assertTrue(waiting.await(2, TimeUnit.SECONDS));
            assertTrue(control.paused()); assertFalse(future.isDone()); assertEquals(1, taps.get());
            control.stop(); future.get(2, TimeUnit.SECONDS);
        } finally { control.stop(); pool.shutdownNow(); }
    }
    @Test public void continuingWithoutResolvingThePromptPausesAgainBeforeAnyAction() {
        AtomicLong clock = new AtomicLong();
        TaskRunner.Control control = new TaskRunner.Control(clock::get, clock::addAndGet);
        AtomicInteger taps = new AtomicInteger(), prompts = new AtomicInteger();
        var runner = new TaskRunner(securityDriver(taps), control, text -> {
            if (text.startsWith("需要本人处理")) {
                assertTrue(control.paused());
                if (prompts.incrementAndGet() == 1) control.setPaused(false);
                else control.stop();
            }
        }, 80, true, true, 2, 1);
        assertThrows(TaskRunner.Stopped.class, runner::run);
        assertEquals(2, prompts.get()); assertEquals(0, taps.get());
    }
    private TaskRunner.Driver securityDriver(AtomicInteger taps) {
        return new TaskRunner.Driver() {
            public TaskRunner.Snapshot read() { return new TaskRunner.Snapshot(List.of(new TaskRules.Node("请完成安全验证", 0, 0, 500, 300)), 1440, 3200, TaskRules.TAOBAO); }
            public void tap(TaskRules.Node node) { taps.incrementAndGet(); }
            public void swipe(boolean reverse) { taps.incrementAndGet(); }
            public void back() { taps.incrementAndGet(); }
            public void launchTaobao() { }
            public boolean setSearchText(String value) { taps.incrementAndGet(); return true; }
        };
    }
}
