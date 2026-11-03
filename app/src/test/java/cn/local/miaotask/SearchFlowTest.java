package cn.local.miaotask;

import org.junit.Test;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import static org.junit.Assert.*;
import cn.local.miaotask.TaskRules.Node;
import cn.local.miaotask.TaskRunner.Snapshot;

public class SearchFlowTest {
    private Node node(String text, int x, int y, int width) { return new Node(text, x, y, x + width, y + 65); }
    private Snapshot snapshot(List<Node> nodes) { return new Snapshot(nodes, 1440, 3200, TaskRules.TAOBAO); }
    private List<Node> products() {
        return new ArrayList<>(List.of(node("手机壳", 100, 150, 700), node("搜索", 1200, 150, 180),
                node("搜索推荐", 100, 300, 700), node("透明防摔手机壳", 100, 700, 600),
                node("￥9.9 已售1000+", 100, 1000, 600), node("硅胶手机壳", 800, 700, 600),
                node("￥16.8 包邮", 800, 1000, 600)));
    }

    private final class Driver implements TaskRunner.Driver {
        final AtomicLong clock = new AtomicLong();
        final List<String> logs = new ArrayList<>();
        final List<String> queries = new ArrayList<>();
        final List<Long> resultSwipes = new ArrayList<>();
        boolean returned, loadResults = true, returnToMain;
        boolean hideTopSearch, keyboardSearch, useEnter;
        boolean directNavigation;
        int keyboardTaps, enterSubmits;
        int directOpens;
        int phase, searchInputs, searchTaps, backs, mainTaps, mainScrolls, coinTaps, energyTaps;
        long browseAt = -1, returnAt = -1;
        @Override public Snapshot readForeground() { return snapshot(List.of()); }
        @Override public Snapshot read() {
            if (phase == 4) {
                List<Node> nodes = new ArrayList<>(List.of(node("推荐", 200, 160, 200), node("搜索", 1150, 340, 220),
                        node("购物车", 970, 3100, 160), node("我的淘宝", 1200, 3100, 220)));
                nodes.add(node("领淘金币", 800, 750, 220));
                return snapshot(nodes);
            }
            if (phase == 5) return snapshot(List.of(node("淘金币双11狂享20亿", 100, 180, 1000),
                    node("去赚能量", 1150, 1200, 220)));
            if (phase == 0) return snapshot(List.of(node("做任务赚能量", 500, 630, 500),
                    node("搜一搜你喜欢的宝贝(" + (returned ? "3" : "2") + "/5)", 260, 1110, 700),
                    node("浏览5秒+400", 260, 1190, 700), node("去完成", 1150, 1140, 220)));
            if (phase == 2 && loadResults) return snapshot(products());
            List<Node> nodes = new ArrayList<>(List.of(node("手机壳", 100, 150, 700),
                    node("历史搜索", 100, 500, 500), node("搜索发现", 100, 900, 500)));
            if (!hideTopSearch) nodes.add(node("搜索", 1200, 150, 180));
            if (searchInputs > 0 && keyboardSearch) nodes.add(node("搜索", 1200, 2800, 180));
            return snapshot(nodes);
        }
        @Override public void tap(Node target) {
            if (phase == 4) {
                if (target.text.equals("领淘金币")) { coinTaps++; phase = 5; }
                else { assertEquals("首页", target.text); mainTaps++; }
            } else if (phase == 5) { assertEquals("去赚能量", target.text); energyTaps++; phase = 0; }
            else if (phase == 0) phase = 1;
            else {
                assertEquals("搜索", target.text); searchTaps++;
                if (target.y() > 3200 * .75) keyboardTaps++;
                phase = 2;
            }
        }
        @Override public boolean setSearchText(String text) { BridgeRules.searchText(text); queries.add(text); searchInputs++; return true; }
        @Override public boolean submitSearch() {
            if (!useEnter) return false;
            enterSubmits++; phase = 2; return true;
        }
        @Override public void swipe(boolean reverse) {
            if (phase == 4) { assertTrue(reverse); mainScrolls++; }
            else { assertEquals(2, phase); resultSwipes.add(clock.get()); }
        }
        @Override public void back() {
            backs++;
            if (phase == 2) phase = 3;
            else if (phase == 1) phase = 0;
            else { assertEquals(3, phase); phase = returnToMain ? 4 : 0; returned = loadResults; }
        }
        @Override public void launchTaobao() { }
        @Override public boolean openCoinPage() {
            if (phase != 4 && !directNavigation) return false;
            directOpens++; coinTaps++; phase = 5; return true;
        }
        TaskRunner runner() { return runner(1); }
        TaskRunner runner(int limit) {
            return new TaskRunner(this, new TaskRunner.Control(clock::get, clock::addAndGet), message -> {
                logs.add(message);
                if (message.startsWith("浏览「") && browseAt < 0) browseAt = clock.get();
                if (message.startsWith("返回淘宝") && returnAt < 0) returnAt = clock.get();
            }, limit, true, false, 2, 1);
        }
    }

    @Test public void productsWithoutTheOldFilterLabelsBrowseFiveSecondsAndReturnThroughSearch() {
        Driver driver = new Driver(); TaskRunner runner = driver.runner(); runner.run();
        assertEquals(1, driver.searchInputs); assertEquals(1, driver.searchTaps);
        assertTrue(driver.browseAt >= 0); assertEquals(6000, driver.returnAt - driver.browseAt);
        assertEquals(2, driver.backs); assertEquals(0, driver.phase);
        assertTrue(runner.summary().contains("已执行 1"));
    }
    @Test public void unconfirmedSearchReturnsToTheListWithoutBrowsingOrCountingCompletion() {
        Driver driver = new Driver(); driver.loadResults = false;
        TaskRunner runner = driver.runner(); runner.run();
        assertEquals(-1, driver.browseAt); assertEquals(2, driver.backs); assertEquals(0, driver.phase);
        assertTrue(driver.logs.stream().anyMatch(message -> message.contains("未确认搜索结果页")));
        assertTrue(runner.summary().contains("已执行 0"));
        assertTrue(runner.summary().contains("异常 1"));
    }
    @Test public void keyboardSearchSubmitsWhenTheOverlayHidesTheTopButton() {
        Driver driver = new Driver(); driver.hideTopSearch = true; driver.keyboardSearch = true;
        TaskRunner runner = driver.runner(); runner.run();
        assertEquals(1, driver.searchInputs); assertEquals(1, driver.keyboardTaps); assertEquals(0, driver.enterSubmits);
        assertEquals(6000, driver.returnAt - driver.browseAt); assertEquals(2, driver.backs);
        assertTrue(runner.summary().contains("已执行 1"));
    }
    @Test public void enterSubmitsWhenNeitherSearchButtonCanBeRead() {
        Driver driver = new Driver(); driver.hideTopSearch = true; driver.useEnter = true;
        TaskRunner runner = driver.runner(); runner.run();
        assertEquals(1, driver.enterSubmits); assertEquals(0, driver.searchTaps);
        assertEquals(6000, driver.returnAt - driver.browseAt); assertEquals(2, driver.backs);
        assertTrue(runner.summary().contains("已执行 1"));
    }
    @Test public void typingAloneNeverStartsTheBrowseTimerOrCountsExecution() {
        Driver driver = new Driver(); driver.hideTopSearch = true;
        TaskRunner runner = driver.runner(); runner.run();
        assertEquals(1, driver.searchInputs); assertEquals(0, driver.searchTaps); assertEquals(-1, driver.browseAt);
        assertTrue(runner.summary().contains("已执行 0"));
        assertTrue(driver.logs.stream().anyMatch(message -> message.contains("未确认搜索结果页")));
    }
    @Test public void searchBackThroughSuggestionsToMainHomeUsesItsVisibleEntryWithoutDeepLink() {
        Driver driver = new Driver(); driver.returnToMain = true;
        TaskRunner runner = driver.runner(); runner.run();
        assertEquals(1, driver.searchInputs); assertEquals(1, driver.searchTaps);
        assertEquals(2, driver.backs); assertEquals(0, driver.mainTaps); assertEquals(0, driver.mainScrolls);
        assertEquals(1, driver.coinTaps); assertEquals(1, driver.energyTaps); assertEquals(0, driver.phase);
        assertEquals(0, driver.directOpens);
        assertTrue(runner.summary().contains("已执行 1"));
        assertFalse(driver.logs.stream().anyMatch(message -> message.startsWith("自动恢复")));
    }
    @Test public void productEvidenceRequiresSearchContextAndDoesNotTreatHistoryAsResults() {
        assertTrue(TaskRunner.resultsReady(snapshot(products())));
        List<Node> history = products(); history.add(node("历史搜索", 100, 500, 500));
        assertFalse(TaskRunner.resultsReady(snapshot(history)));
        List<Node> singleProduct = products(); singleProduct.remove(singleProduct.size() - 1);
        assertFalse(TaskRunner.resultsReady(snapshot(singleProduct)));
        List<Node> noSearch = products(); noSearch.removeIf(n -> n.text.equals("搜索"));
        assertFalse(TaskRunner.resultsReady(snapshot(noSearch)));
    }
    @Test public void searchSubmissionPrefersTheKeyboardAndIgnoresUnrelatedPageText() {
        Node keyboard = node("搜索", 1200, 2800, 180), top = node("搜索", 1200, 150, 180);
        assertSame(keyboard, TaskRunner.searchButton(snapshot(List.of(top, node("搜索", 1200, 1300, 180), keyboard))));
        assertSame(top, TaskRunner.searchButton(snapshot(List.of(top))));
        assertNull(TaskRunner.searchButton(snapshot(List.of(node("搜索", 1200, 1300, 180), node("搜索发现", 100, 900, 500)))));
    }
    @Test public void successiveSearchTasksUseDifferentProductQueries() {
        Driver driver = new Driver(); driver.runner(3).run();
        assertEquals(3, driver.queries.size()); assertEquals(3, new java.util.HashSet<>(driver.queries).size());
    }
    @Test public void searchQueryPoolCoversTheWholeRunWithoutRepeatingAProduct() {
        List<String> queries = TaskRunner.newSearchQueries();
        assertEquals(80, queries.size()); assertEquals(80, new java.util.HashSet<>(queries).size());
        for (String query : queries) { BridgeRules.searchText(query); assertFalse(query.isBlank()); }
    }
    @Test public void completedSearchUsesBackEvenWhenTheChannelLinkIsAvailable() {
        Driver driver = new Driver(); driver.directNavigation = true;
        TaskRunner runner = driver.runner(); runner.run();
        assertEquals(2, driver.backs); assertEquals(0, driver.coinTaps); assertEquals(0, driver.energyTaps);
        assertTrue(runner.summary().contains("已执行 1"));
    }
    @Test public void searchResultsMoveFrequentlyUntilTheBrowseTimeEnds() {
        Driver driver = new Driver(); driver.runner().run();
        assertTrue(driver.resultSwipes.size() >= 3 && driver.resultSwipes.size() <= 4);
        for (int i = 1; i < driver.resultSwipes.size(); i++)
            assertTrue(driver.resultSwipes.get(i) - driver.resultSwipes.get(i - 1) >= 1500
                    && driver.resultSwipes.get(i) - driver.resultSwipes.get(i - 1) <= 2000);
        assertEquals(6000, driver.returnAt - driver.browseAt);
    }
}
