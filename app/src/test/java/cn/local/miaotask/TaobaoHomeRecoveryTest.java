package cn.local.miaotask;

import org.junit.Test;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import static org.junit.Assert.*;
import cn.local.miaotask.TaskRules.Node;
import cn.local.miaotask.TaskRunner.Snapshot;

public class TaobaoHomeRecoveryTest {
    private Node node(String text, int x, int y, int width) { return new Node(text, x, y, x + width, y + 65); }
    private Snapshot snapshot(List<Node> nodes) { return new Snapshot(nodes, 1440, 3200, TaskRules.TAOBAO); }
    private Snapshot mainHome(boolean scrolled) {
        List<Node> nodes = new ArrayList<>(List.of(node("关注 推荐 闪购外卖 国补 飞猪 双11", 60, 160, 1300),
                node("搜索", 1150, 340, 220), node("精选商品", 100, 1000, 500),
                node("消息", 680, 3100, 150), node("购物车", 970, 3100, 160), node("我的淘宝", 1200, 3100, 220)));
        if (!scrolled) nodes.add(node("领淘金币", 800, 750, 220));
        return snapshot(nodes);
    }

    private final class Driver implements TaskRunner.Driver {
        final AtomicLong clock = new AtomicLong();
        final TaskRunner.Control control = new TaskRunner.Control(clock::get, clock::addAndGet);
        final List<String> logs = new ArrayList<>();
        String phase = "list";
        int taskTaps, backs, homeBacks, homeTaps, homeScrolls, coinTaps, energyTaps, browseSwipes, directOpens;
        boolean returnToMain, nativeHome = true, wrongDestination, missingCoinEntry;
        boolean mergedCoinRow;
        int revealCoinAfter = 2;
        long browseAt = -1, autoReturnAfter = -1;
        Snapshot screen() {
            if (phase.equals("browse") && browseAt >= 0 && autoReturnAfter >= 0 && clock.get() - browseAt >= autoReturnAfter) phase = "main";
            if (phase.equals("main")) {
                Snapshot home = mainHome(missingCoinEntry || homeScrolls < revealCoinAfter);
                if (mergedCoinRow && !missingCoinEntry && homeScrolls >= revealCoinAfter) {
                    List<Node> nodes = new ArrayList<>(home.nodes);
                    nodes.removeIf(n -> n.text.equals("领淘金币"));
                    nodes.add(node("红包签到 芭芭农场 领淘金币 充值中心 试用领取", 70, 750, 1300));
                    nodes.addAll(TaskRules.coinEntries(List.of(node("红包签到", 70, 750, 200),
                            node("芭芭农场", 350, 750, 200), node("领", 650, 750, 50),
                            node("淘金币", 700, 750, 150), node("充值中心", 950, 750, 200))));
                    return snapshot(nodes);
                }
                return home;
            }
            if (phase.equals("activity")) return snapshot(List.of(node("淘金币双11狂享20亿", 100, 180, 1000),
                    node("去赚能量", 1150, 1200, 220)));
            if (phase.equals("browse")) return snapshot(List.of(node("精选好物", 100, 500, 500),
                    node("今日推荐", 100, 900, 500), node("更多商品", 100, 1200, 500)));
            return snapshot(List.of(node("做任务赚能量", 500, 630, 500),
                    node("好物沉浸看(0/1)", 260, 1110, 700), node("浏览5秒+400", 260, 1190, 700),
                    node("去完成", 1150, 1140, 220)));
        }
        public Snapshot readForeground() {
            Snapshot screen = screen();
            return nativeHome && phase.equals("main") ? screen : snapshot(List.of());
        }
        public Snapshot read() { return screen(); }
        public void tap(Node target) {
            if (phase.equals("main")) {
                if (target.text.equals("领淘金币")) { coinTaps++; phase = "activity"; }
                else { assertTrue(target.x() < 1440 * .2 && target.y() > 3200 * .9); homeTaps++; }
            } else if (phase.equals("activity")) { assertEquals("去赚能量", target.text); energyTaps++; phase = "list"; }
            else { assertEquals("list", phase); taskTaps++; phase = wrongDestination ? "main" : "browse"; }
        }
        public void swipe(boolean reverse) {
            assertEquals("main", phase);
            assertTrue("Restore the home feed towards its top", reverse); homeScrolls++;
        }
        public void browseSwipe(boolean reverse) { assertEquals("browse", phase); browseSwipes++; }
        public void back() {
            screen(); backs++;
            if (phase.equals("main")) { homeBacks++; return; }
            assertEquals("browse", phase); phase = returnToMain ? "main" : "list";
        }
        public void launchTaobao() { }
        public boolean openCoinPage() {
            if (!phase.equals("main")) return false;
            directOpens++; if (!missingCoinEntry) phase = "activity"; return true;
        }
        public boolean setSearchText(String text) { fail("The main home feed is not a search task destination"); return false; }
        TaskRunner runner() {
            return new TaskRunner(this, control, message -> {
                logs.add(message);
                if (message.startsWith("浏览「") && browseAt < 0) browseAt = clock.get();
                if (message.startsWith("需要本人处理")) control.stop();
            }, 1, true, true, 2, 0);
        }
    }

    @Test public void aScrolledMainHomeOpensCoinsWithoutPullingTheFeedToItsTop() {
        Driver driver = new Driver(); driver.phase = "main";
        TaskRunner runner = driver.runner(); runner.run();
        assertEquals(0, driver.homeTaps); assertEquals(0, driver.homeScrolls);
        assertEquals(1, driver.directOpens); assertEquals(1, driver.energyTaps); assertEquals(1, driver.taskTaps);
        assertEquals(0, driver.homeBacks); assertTrue(runner.summary().contains("已执行 1"));
    }
    @Test public void aVisibleCoinEntryStillUsesTheDirectChannelWithoutHomeTapOrScroll() {
        Driver driver = new Driver(); driver.phase = "main"; driver.revealCoinAfter = 0;
        driver.runner().run();
        assertEquals(1, driver.directOpens); assertEquals(0, driver.coinTaps); assertEquals(0, driver.homeTaps); assertEquals(0, driver.homeScrolls);
        assertEquals(1, driver.energyTaps); assertEquals(1, driver.taskTaps);
    }
    @Test public void theCoinEntryRemainsClickableWhenOcrMergesItsLabelWithNeighbouringServices() {
        Driver driver = new Driver(); driver.phase = "main"; driver.revealCoinAfter = 0; driver.mergedCoinRow = true;
        driver.runner().run();
        assertEquals(1, driver.directOpens); assertEquals(0, driver.coinTaps); assertEquals(0, driver.homeTaps); assertEquals(0, driver.homeScrolls);
        assertEquals(1, driver.energyTaps); assertEquals(1, driver.taskTaps);
    }
    @Test public void returningFromBrowsingToMainHomeUsesItsVisibleEntryWithoutDeepLink() {
        Driver driver = new Driver(); driver.returnToMain = true; driver.nativeHome = false; driver.revealCoinAfter = 0;
        TaskRunner runner = driver.runner(); runner.run();
        assertEquals(1, driver.backs); assertEquals(0, driver.homeBacks);
        assertEquals(0, driver.directOpens); assertEquals(1, driver.coinTaps); assertEquals(1, driver.energyTaps); assertEquals("list", driver.phase);
        assertTrue(runner.summary().contains("已执行 1"));
    }
    @Test public void anAutomaticReturnJustBeforeTheTimerEndsDoesNotGetAnotherBlindBack() {
        Driver driver = new Driver(); driver.autoReturnAfter = 5000; driver.revealCoinAfter = 0;
        TaskRunner runner = driver.runner(); runner.run();
        assertEquals(0, driver.backs); assertEquals(0, driver.homeBacks);
        assertEquals(0, driver.directOpens); assertEquals(1, driver.coinTaps); assertEquals("list", driver.phase);
        assertTrue(runner.summary().contains("已执行 1"));
    }
    @Test public void landingOnTheMainHomeInsteadOfATaskRecoversWithoutBrowsingIt() {
        Driver driver = new Driver(); driver.wrongDestination = true; driver.nativeHome = false; driver.revealCoinAfter = 0;
        TaskRunner runner = driver.runner(); runner.run();
        assertEquals(0, driver.browseSwipes); assertEquals(0, driver.homeBacks);
        assertEquals(0, driver.directOpens); assertEquals(1, driver.coinTaps); assertEquals("list", driver.phase);
        assertTrue(runner.summary().contains("已执行 0")); assertTrue(runner.summary().contains("异常 1"));
    }
    @Test public void anEarlyReturnDuringBrowsingStopsSwipesAndRestoresTheTaskList() {
        Driver driver = new Driver(); driver.autoReturnAfter = 1000; driver.revealCoinAfter = 0;
        TaskRunner runner = driver.runner(); runner.run();
        assertTrue(driver.browseSwipes > 0); assertEquals(0, driver.homeBacks);
        assertEquals(0, driver.directOpens); assertEquals(1, driver.coinTaps); assertEquals("list", driver.phase);
        assertTrue(runner.summary().contains("异常 1"));
    }
    @Test public void aMissingCoinEntryHasBoundedRecoveryAndNeverBacksOutOfTheHomePage() {
        Driver driver = new Driver(); driver.phase = "main"; driver.missingCoinEntry = true;
        assertThrows(TaskRunner.Stopped.class, () -> driver.runner().run());
        assertEquals(0, driver.homeBacks); assertEquals(0, driver.taskTaps); assertEquals(0, driver.browseSwipes);
        assertEquals(0, driver.homeTaps); assertEquals(0, driver.homeScrolls); assertEquals(3, driver.directOpens);
        assertTrue(driver.logs.stream().anyMatch(message -> message.startsWith("需要本人处理")));
    }
    @Test public void aNormalTaskThatLosesTheHomeEntryPausesWithoutChangingToTheDirectReturnPolicy() {
        Driver driver = new Driver(); driver.returnToMain = true; driver.missingCoinEntry = true;
        TaskRunner runner = driver.runner();
        assertThrows(TaskRunner.Stopped.class, runner::run);
        assertEquals(0, driver.directOpens); assertEquals(0, driver.homeBacks); assertEquals(0, driver.homeScrolls);
        assertTrue(runner.summary().contains("已执行 1"));
    }
}
