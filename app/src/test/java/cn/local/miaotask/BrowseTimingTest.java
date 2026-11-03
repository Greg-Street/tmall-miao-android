package cn.local.miaotask;

import org.junit.Test;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import static org.junit.Assert.*;
import cn.local.miaotask.TaskRules.Node;
import cn.local.miaotask.TaskRunner.Snapshot;

public class BrowseTimingTest {
    private Node node(String text, int x, int y, int width) { return new Node(text, x, y, x + width, y + 65); }

    private final class Driver implements TaskRunner.Driver {
        final AtomicLong clock = new AtomicLong();
        final int seconds;
        boolean opened, returned, selfReturn, completionHint;
        long browseAt = -1, recoveryAt = -1;
        int listReads, lateBrowseReads, extraSeconds;
        String countdown = "";
        boolean explicitHint;
        int destinationReads, horizontalSwipes;
        boolean directAvailable;
        int directOpens, backs;
        int ocrMillis = 700;
        int browseReads;
        boolean guardOnBrowse, frozenCountdown;
        final List<Long> swipeTimes = new ArrayList<>();
        Driver(int seconds) { this.seconds = seconds; }
        Snapshot list() {
            listReads++;
            return new Snapshot(List.of(node("做任务赚能量", 500, 630, 500),
                    node("好物随心逛(" + (returned ? "1" : "0") + "/10)", 260, 1110, 700),
                    node("浏览" + seconds + "秒+400", 260, 1190, 700),
                    node("去完成", 1150, 1140, 220)), 1440, 3200, TaskRules.TAOBAO);
        }
        @Override public Snapshot read() {
            if (selfReturn && opened) returned = true;
            if (!opened || returned) return list();
            if (browseAt >= 0 && recoveryAt < 0) browseReads++;
            if (browseAt < 0) destinationReads++;
            if (browseAt >= 0 && recoveryAt < 0 && clock.get() - browseAt >= (seconds + extraSeconds) * 1000L) lateBrowseReads++;
            clock.addAndGet(ocrMillis); // Model screenshot and OCR latency separately from gestures.
            List<Node> nodes = new ArrayList<>(List.of(node("精选好物", 100, 500, 500),
                    node("今日推荐", 100, 900, 500), node("更多商品", 100, 1200, 500)));
            if (browseAt < 0 && !countdown.isEmpty()) nodes.add(node(countdown, 100, 1500, 500));
            if (explicitHint) nodes.add(node("浏览15秒可领400", 100, 350, 900));
            if (browseAt >= 0 && completionHint) nodes.add(node("任务已完成", 100, 1500, 500));
            if (browseAt >= 0 && frozenCountdown) nodes.add(node("还需浏览5秒", 100, 1500, 500));
            if (browseAt >= 0 && guardOnBrowse) {
                nodes.add(node("将满足描述的所有图片拖到指定区域后提交", 100, 800, 1100));
                nodes.add(node("反馈码", 100, 2600, 600));
            }
            return new Snapshot(nodes, 1440, 3200, TaskRules.TAOBAO);
        }
        @Override public Snapshot readForeground() { return new Snapshot(List.of(), 1440, 3200, TaskRules.TAOBAO); }
        @Override public void tap(Node node) { opened = true; }
        @Override public void swipe(boolean reverse) { swipeTimes.add(clock.get()); clock.addAndGet(480); }
        @Override public void browseSwipe(boolean reverse, boolean horizontal) {
            if (swipeTimes.isEmpty()) assertFalse(horizontal);
            if (horizontal) horizontalSwipes++;
            swipe(reverse);
        }
        @Override public void back() { backs++; returned = true; }
        @Override public void launchTaobao() { }
        @Override public boolean openCoinPage() {
            if (!directAvailable) return false;
            directOpens++; returned = true; return true;
        }
        @Override public boolean setSearchText(String text) { return false; }
        TaskRunner runner() {
            return new TaskRunner(this, new TaskRunner.Control(clock::get, clock::addAndGet), message -> {
                if (message.startsWith("浏览「") && browseAt < 0) browseAt = clock.get();
                if (message.startsWith("返回淘宝")) recoveryAt = clock.get();
            }, 1, true, false, 2, extraSeconds);
        }
    }

    @Test public void fiveSecondsIncludeGestureAndOcrCostsWithoutExtraPadding() { checkDuration(5); }
    @Test public void fifteenSecondsIncludeGestureAndOcrCostsWithoutExtraPadding() { checkDuration(15); }
    @Test public void browseDoesNotInsertLongStationaryGapsAndStillFinishesAtTheRequestedTime() {
        for (int seconds : new int[]{5, 15}) {
            Driver driver = new Driver(seconds); driver.runner().run();
            assertFalse(driver.swipeTimes.isEmpty());
            for (int i = 1; i < driver.swipeTimes.size(); i++)
                assertTrue(driver.swipeTimes.get(i) - driver.swipeTimes.get(i - 1) <= 2000);
            assertTrue(driver.swipeTimes.size() >= (seconds == 5 ? 3 : 8));
            assertEquals(seconds * 1000L, driver.recoveryAt - driver.browseAt);
        }
    }
    @Test public void aLongBrowseMixesHorizontalShortSwipesWithVerticalSwipes() {
        Driver driver = new Driver(15); driver.runner().run();
        assertTrue(driver.horizontalSwipes > 0); assertTrue(driver.horizontalSwipes < driver.swipeTimes.size());
    }
    @Test public void anExplicitBrowsePageStartsMovingAfterTheFirstReadyFrame() {
        Driver driver = new Driver(15); driver.explicitHint = true; driver.runner().run();
        assertEquals(1, driver.destinationReads); assertFalse(driver.swipeTimes.isEmpty());
    }
    @Test public void aNormalTaobaoBrowseReturnsWithBackEvenWhenTheChannelLinkIsAvailable() {
        Driver driver = new Driver(5); driver.directAvailable = true;
        TaskRunner runner = driver.runner(); runner.run();
        assertEquals(1, driver.backs); assertEquals(0, driver.directOpens);
        assertTrue(runner.summary().contains("已执行 1"));
    }
    @Test public void slowRecognitionIsNotAllCountedAsActiveBrowsing() {
        Driver driver = new Driver(5); driver.ocrMillis = 2600;
        driver.runner().run();
        assertTrue(driver.recoveryAt - driver.browseAt > 5000);
        assertTrue(driver.swipeTimes.size() >= 3);
    }
    @Test public void aFastDeviceDoesNotGenerateRapidSwipeAndOcrBursts() {
        Driver driver = new Driver(5); driver.ocrMillis = 0; driver.runner().run();
        assertTrue(driver.swipeTimes.size() >= 3); assertTrue(driver.browseReads <= 4);
        for (int i = 1; i < driver.swipeTimes.size(); i++)
            assertTrue(driver.swipeTimes.get(i) - driver.swipeTimes.get(i - 1) >= 1500);
        assertEquals(5000, driver.recoveryAt - driver.browseAt);
    }
    @Test public void imageVerificationPausesBeforeAnotherBrowseGesture() {
        Driver driver = new Driver(15); driver.guardOnBrowse = true;
        TaskRunner.Control control = new TaskRunner.Control(driver.clock::get, driver.clock::addAndGet);
        TaskRunner runner = new TaskRunner(driver, control, message -> {
            if (message.startsWith("浏览「") && driver.browseAt < 0) driver.browseAt = driver.clock.get();
            if (message.startsWith("需要本人处理")) { assertTrue(control.paused()); control.stop(); }
        }, 1, true, false, 2, 0);
        assertThrows(TaskRunner.Stopped.class, runner::run);
        assertEquals(1, driver.swipeTimes.size()); assertEquals(0, driver.backs);
        assertTrue(runner.summary().contains("已执行 0"));
    }
    @Test public void aFrozenVisibleCountdownTimesOutWithoutRecordingExecution() {
        Driver driver = new Driver(5); driver.frozenCountdown = true;
        TaskRunner runner = driver.runner(); runner.run();
        assertTrue(driver.recoveryAt - driver.browseAt >= 90000);
        assertTrue(runner.summary().contains("已执行 0"));
        assertTrue(runner.summary().contains("异常 1"));
    }

    private void checkDuration(int seconds) {
        Driver driver = new Driver(seconds); TaskRunner runner = driver.runner(); runner.run();
        assertTrue(driver.browseAt >= 0);
        assertEquals(seconds * 1000L, driver.recoveryAt - driver.browseAt);
        assertEquals("No final browse screenshot once the timer has expired", 0, driver.lateBrowseReads);
        assertTrue(runner.summary().contains("已执行 1"));
    }

    @Test public void actualPageCountdownIsStillRespectedWithoutTwoMoreSeconds() {
        Driver driver = new Driver(5); driver.countdown = "还需浏览7秒";
        TaskRunner runner = driver.runner(); runner.run();
        assertEquals(7000, driver.recoveryAt - driver.browseAt);
        assertTrue(runner.summary().contains("已执行 1"));
    }

    @Test public void userPaddingAddsToFiveAndFifteenSecondsInsteadOfReplacingThem() {
        for (int seconds : new int[]{5, 15}) for (int extra : new int[]{2, 30}) {
            Driver driver = new Driver(seconds); driver.extraSeconds = extra;
            TaskRunner runner = driver.runner(); runner.run();
            assertEquals((seconds + extra) * 1000L, driver.recoveryAt - driver.browseAt);
            assertEquals(0, driver.lateBrowseReads);
            assertTrue(runner.summary().contains("已执行 1"));
        }
    }
    @Test public void userPaddingAlsoAppliesToTheActualPageCountdown() {
        Driver driver = new Driver(5); driver.extraSeconds = 2; driver.countdown = "还需浏览7秒";
        TaskRunner runner = driver.runner(); runner.run();
        assertEquals(9000, driver.recoveryAt - driver.browseAt);
        assertTrue(runner.summary().contains("已执行 1"));
    }
    @Test public void anExplicitCompletionHintCanStillEndBrowsingBeforeThePadding() {
        Driver driver = new Driver(5); driver.extraSeconds = 2; driver.completionHint = true;
        TaskRunner runner = driver.runner(); runner.run();
        assertTrue(driver.recoveryAt - driver.browseAt < 7000);
        assertTrue(runner.summary().contains("已执行 1"));
    }

    @Test public void aTaskThatAlreadyReturnedReusesTheListForVerification() {
        Driver driver = new Driver(5); driver.selfReturn = true;
        TaskRunner runner = driver.runner(); runner.run();
        assertEquals("Reuse the returned task list without another OCR or settlement poll", 2, driver.listReads);
        assertEquals(-1, driver.browseAt);
        assertEquals(-1, driver.recoveryAt);
        assertTrue(runner.summary().contains("已执行 1"));
    }
}
