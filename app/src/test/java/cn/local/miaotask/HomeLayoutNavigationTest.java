package cn.local.miaotask;

import org.junit.Test;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import static org.junit.Assert.*;
import cn.local.miaotask.TaskRules.Node;
import cn.local.miaotask.TaskRunner.Snapshot;

public class HomeLayoutNavigationTest {
    private Node node(String text, int x, int y, int width) { return new Node(text, x, y, x + width, y + 65); }

    private final class Driver implements TaskRunner.Driver {
        final AtomicLong clock = new AtomicLong();
        final TaskRunner.Control control = new TaskRunner.Control(clock::get, clock::addAndGet);
        final List<String> logs = new ArrayList<>();
        final int rows;
        String phase = "main";
        boolean coinVisible, linkWorks = true, captcha, hiddenNavigation;
        int reads, coinTaps, directOpens, homeSwipes, homeTaps, taskTaps;
        Driver(int rows) { this.rows = rows; }
        Snapshot screen(List<Node> nodes) { return new Snapshot(nodes, 1440, 3200, TaskRules.TAOBAO); }
        public Snapshot readForeground() { return screen(List.of()); }
        public Snapshot read() {
            if (phase.equals("main")) {
                List<Node> nodes = new ArrayList<>(List.of(node("关注 推荐 闪购外卖 双11", 60, 160, 1300),
                        node("搜索", 1150, 340, 220), node("购物车", 970, 3100, 160), node("我的淘宝", 1200, 3100, 220),
                        node("优惠剩余" + (++reads) + "秒", 100, 1600, 700)));
                for (int row = 0; row < rows; row++) {
                    int y = 800 + row * 240;
                    nodes.add(node("充值中心 红包签到 淘工厂 芭芭农场", 70, y, 1200));
                }
                if (hiddenNavigation) nodes.removeIf(n -> n.text.equals("购物车") || n.text.equals("我的淘宝"));
                if (coinVisible) nodes.add(node("领淘金币", 610, 800 + (rows - 1) * 240, 220));
                return screen(nodes);
            }
            if (captcha) return screen(List.of(node("请完成安全验证", 100, 1000, 900)));
            if (phase.equals("activity")) return screen(List.of(node("淘金币双11狂享20亿", 100, 180, 1000),
                    node("去赚能量", 1150, 1200, 220)));
            if (phase.equals("browse")) return screen(List.of(node("精选好物", 100, 500, 500),
                    node("今日推荐", 100, 900, 500), node("更多商品", 100, 1200, 500)));
            return screen(List.of(node("做任务赚能量", 500, 630, 500), node("好物随心逛(0/1)", 260, 1110, 700),
                    node("浏览5秒+400", 260, 1190, 700), node("去完成", 1150, 1140, 220)));
        }
        public boolean openCoinPage() { directOpens++; if (linkWorks) phase = "activity"; return true; }
        public void tap(Node target) {
            if (phase.equals("main")) {
                if (target.text.equals("领淘金币")) {
                    assertEquals(800 + (rows - 1) * 240 + 32, target.y());
                    coinTaps++; phase = "activity";
                } else { assertEquals("首页", target.text); homeTaps++; }
            } else if (phase.equals("activity")) phase = "list";
            else { taskTaps++; phase = "browse"; }
        }
        public void swipe(boolean reverse) { assertEquals("main", phase); homeSwipes++; }
        public void browseSwipe(boolean reverse) { assertEquals("browse", phase); }
        public void back() { assertEquals("browse", phase); phase = "activity"; }
        public void launchTaobao() { }
        public boolean setSearchText(String text) { return false; }
        TaskRunner runner() {
            return new TaskRunner(this, control, message -> {
                logs.add(message);
                if (message.startsWith("需要本人处理")) control.stop();
            }, 1, true, true, 2, 0);
        }
    }

    @Test public void changingPromotionsInASingleServiceRowDoNotCausePullToRefreshLoops() {
        Driver driver = new Driver(1); TaskRunner runner = driver.runner(); runner.run();
        assertEquals(1, driver.directOpens); assertEquals(0, driver.homeSwipes); assertEquals(0, driver.homeTaps);
        assertEquals(1, driver.taskTaps); assertTrue(runner.summary().contains("已执行 1"));
    }
    @Test public void aTwoRowHomeWithoutACoinShortcutAlsoUsesTheChannelLink() {
        Driver driver = new Driver(2); driver.runner().run();
        assertEquals(1, driver.directOpens); assertEquals(0, driver.homeSwipes); assertEquals(1, driver.taskTaps);
    }
    @Test public void theChannelLinkIsUsedWithoutLocatingEvenAVisibleHomeIcon() {
        for (int rows : new int[]{1, 2}) {
            Driver driver = new Driver(rows); driver.coinVisible = true; driver.runner().run();
            assertEquals(0, driver.coinTaps); assertEquals(1, driver.directOpens); assertEquals(0, driver.homeSwipes);
            assertEquals(1, driver.taskTaps);
        }
    }
    @Test public void aLinkThatStaysOnHomeDoesNotTriggerMoreScrollingOrUnboundedLaunches() {
        Driver driver = new Driver(1); driver.linkWorks = false;
        assertThrows(TaskRunner.Stopped.class, () -> driver.runner().run());
        assertEquals(3, driver.directOpens); assertEquals(0, driver.homeSwipes); assertEquals(0, driver.taskTaps);
    }
    @Test public void aVerificationPageAfterTheLinkStillPausesForTheUser() {
        Driver driver = new Driver(1); driver.captcha = true;
        assertThrows(TaskRunner.Stopped.class, () -> driver.runner().run());
        assertEquals(1, driver.directOpens); assertEquals(0, driver.taskTaps);
        assertTrue(driver.logs.stream().anyMatch(s -> s.contains("检测到「安全验证」")));
    }
    @Test public void hiddenBottomNavigationDoesNotLeadToBlindBacksAfterTheLink() {
        Driver driver = new Driver(1); driver.linkWorks = false; driver.hiddenNavigation = true;
        assertThrows(TaskRunner.Stopped.class, () -> driver.runner().run());
        assertEquals(3, driver.directOpens); assertEquals(0, driver.homeSwipes); assertEquals(0, driver.taskTaps);
    }
}
