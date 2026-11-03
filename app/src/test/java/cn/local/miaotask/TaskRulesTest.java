package cn.local.miaotask;

import org.junit.Test;
import java.util.ArrayList;
import java.util.List;
import static org.junit.Assert.*;
import cn.local.miaotask.TaskRules.Kind;
import cn.local.miaotask.TaskRules.Node;
import cn.local.miaotask.TaskRules.Task;

public class TaskRulesTest {
    private Node node(String text, int x, int y, int width) { return new Node(text, x, y, x + width, y + 65); }
    private List<Node> screen(String title, String detail, String action) {
        return new ArrayList<>(List.of(node("做任务赚能量", 500, 630, 440), node("累计任务奖励", 60, 800, 400),
                node(title, 260, 1110, 700), node(detail, 260, 1190, 700), node(action, 1150, 1140, 220)));
    }
    private List<Task> parse(List<Node> nodes) { return TaskRules.parse(nodes, 1440, 3200); }
    private Task task(String title, String detail) { return parse(screen(title, detail, "去完成")).get(0); }

    @Test public void readsProgressDurationAndPanel() {
        List<Node> nodes = screen("好物随心逛（3/10）", "浏览15秒 +400", "去完成");
        Task task = parse(nodes).get(0);
        assertEquals("好物随心逛", task.title);
        assertEquals(3, task.current); assertEquals(10, task.total); assertEquals(15, task.seconds);
        assertEquals(Kind.BROWSE, task.kind);
        assertTrue(TaskRules.isList(nodes, 1440, 3200));
    }
    @Test public void normalizesUnicode() { assertEquals("浏览5秒(1/10)", TaskRules.normalize(" 浏览 ５ 秒（１／１０） ")); }
    @Test public void reportedSearchOcrTyposRemainSearchTasksWithOneIdentity() {
        for (String suffix : new String[]{"你喜欢的宝贝", "你心仪的宝贝"}) {
            String expected = "搜一搜" + suffix;
            for (String typo : new String[]{"搜", "機", "机", "擅"}) {
                Task task = task("搜一" + typo + suffix + "(2/5)", "浏览5秒+400");
                assertEquals(Kind.SEARCH, task.kind); assertEquals(5, task.seconds);
                assertEquals(expected, task.title); assertEquals(expected, task.key);
            }
        }
    }
    @Test public void aReportedSearchTypoStillClassifiesWithoutARecognizedDuration() {
        assertEquals(Kind.SEARCH, task("搜一機你喜欢的宝贝(0/5)", "+400").kind);
    }
    @Test public void correctingTheSearchTitleDoesNotChangeOtherTextOrAllowManualActions() {
        assertEquals(Kind.MANUAL, task("搜一擅你喜欢的宝贝(0/5)", "搜索后关注店铺").kind);
        assertEquals("查看機械键盘", task("查看機械键盘(0/1)", "浏览5秒").title);
    }
    @Test public void unlabelledButtonIsNotATask() { assertTrue(parse(List.of(node("去完成", 1150, 1100, 220))).isEmpty()); }
    @Test public void toastDoesNotBecomeATask() { assertTrue(parse(screen("任务已完成", "+400", "去完成")).isEmpty()); }
    @Test public void malformedProgressIsIgnored() { assertTrue(parse(screen("浏览(99999999999/1)", "浏览15秒", "去完成")).isEmpty()); }
    @Test public void titleIdentitySurvivesProgressAndAggregation() {
        Task one = task("好物随心逛(0/10)", "浏览15秒+400");
        Task two = task("好物随心逛(1/10)浏览15秒+400", "");
        assertEquals(one.key, two.key); assertEquals(one.seconds, two.seconds);
    }
    @Test public void aggregatedRowExcludesBackground() {
        Task task = task("好物沉浸看(0/10)浏览15秒+400", "淘宝闪购下单得500游戏鼠标");
        assertEquals(Kind.BROWSE, task.kind); assertFalse(task.detail.contains("下单"));
    }
    @Test public void adjacentDescriptionsDoNotMix() {
        List<Node> nodes = screen("好物随心逛(0/10)", "浏览15秒+400", "去完成");
        nodes.add(node("逛一逛蚂蚁庄园(0/1)", 260, 1380, 700));
        nodes.add(node("点击去逛+600", 260, 1460, 700));
        nodes.add(node("去完成", 1150, 1410, 220));
        assertEquals(2, parse(nodes).size());
        assertEquals(Kind.BROWSE, parse(nodes).get(0).kind);
        assertEquals(Kind.VISIT, parse(nodes).get(1).kind);
    }
    @Test public void interactiveTasksAreManual() {
        for (String title : List.of("下单购买", "充值话费", "关注店铺", "邀请好友", "答题考试", "下载应用", "收藏商品", "开通会员", "趣味小游戏")) {
            assertEquals(title, Kind.MANUAL, task(title + "(0/1)", "").kind);
        }
    }
    @Test public void allDiantaoTasksAreSkippedWithoutBlockingOtherLiveTasks() {
        for (String detail : List.of("点击去逛+600", "浏览15秒+600")) {
            Task task = task("去点淘看直播领红包(0/1)", detail);
            assertEquals(Kind.MANUAL, task.kind);
            assertTrue(task.reason.contains("跳过点淘任务"));
        }
        assertEquals(Kind.MANUAL, task("去点淘逛好物(0/1)", "点击去逛+600").kind);
        assertEquals(Kind.BROWSE, task("看看直播好物(0/5)", "浏览15秒+400").kind);
    }
    @Test public void userReportedDianhaiOcrVariantIsSkippedWithoutRelyingOnTheLiveWord() {
        for (String title : List.of("去点海看真播领红包", "去点海看直播领红包", "去点海逛好物")) {
            Task task = task(title + "(0/1)", "点击去逛+600");
            assertEquals(title, Kind.MANUAL, task.kind);
            assertTrue(task.reason.contains("跳过点淘任务"));
        }
        assertEquals(Kind.BROWSE, task("看看直播好物(0/5)", "浏览15秒+400").kind);
    }
    @Test public void baiduAppTasksAreExcludedRegardlessOfDurationAndAction() {
        for (String title : List.of("去百度App领现金", "去百度ＡＰＰ浏览", "去百度app看看", "去百渡App领现金")) {
            for (String action : List.of("去完成", "立即领取")) {
                Task task = parse(screen(title + "(0/1)", "浏览5秒+400", action)).get(0);
                assertEquals(title, Kind.MANUAL, task.kind);
                assertTrue(task.reason.contains("跳过百度 App"));
            }
        }
    }
    @Test public void excludedAppNamesCanBeSplitFromTheProgressTitleAndContainOcrTypos() {
        for (String brand : List.of("点淘", "点陶", "点掏", "点海", "百度App")) {
            List<Node> nodes = screen("看直搔领红包(0/1)点击去逛+600", "", "去完成");
            String first = brand.substring(0, 1), rest = brand.substring(1);
            nodes.add(node(first, 60, 1110, 75)); nodes.add(node(rest, 135, 1110, 125));
            assertEquals(brand, Kind.MANUAL, parse(nodes).get(0).kind);
        }
    }
    @Test public void screenshotRowsKeepExclusionsWithCompletedAndManualNeighbors() {
        List<Node> nodes = new ArrayList<>(List.of(node("做任务赚能量", 500, 630, 440)));
        String[] titles = {"去点淘看直播领红包(0/1)", "好物沉浸看(10/10)", "去百度App领现金(0/1)",
                "搜一搜你喜欢的宝贝(0/5)", "每拉1人最高2000能量(0/10)", "去玩趣味小游戏(0/3)"};
        for (int i = 0; i < titles.length; i++) {
            int y = 1110 + 270 * i;
            nodes.add(node(titles[i], 260, y, 700));
            nodes.add(node(i == 3 ? "浏览5秒+400" : "点击去逛+600", 260, y + 80, 700));
            nodes.add(node(i == 1 ? "已完成" : "去完成", 1150, y + 30, 220));
        }
        List<Task> tasks = parse(nodes);
        assertEquals(6, tasks.size());
        assertEquals(Kind.MANUAL, tasks.get(0).kind); assertEquals(Kind.DONE, tasks.get(1).kind);
        assertEquals(Kind.MANUAL, tasks.get(2).kind); assertEquals(Kind.SEARCH, tasks.get(3).kind);
    }
    @Test public void flashCouponVisitNeedsNoOrder() {
        assertEquals(Kind.VISIT, task("领闪购红包下单直接省(0/1)", "点击去逛+600").kind);
        assertEquals(Kind.MANUAL, task("领闪购红包下单直接省(0/1)", "完成下单后领奖").kind);
    }
    @Test public void flashVisitLabelsDoNotOverrideTimedOrPurchaseRequirements() {
        for (String title : List.of("逛逛闪购", "逛一逛闪购", "逛闪购", "淘宝闪购")) {
            assertEquals(Kind.VISIT, task(title + "(0/1)", "点击去逛+600").kind);
            assertEquals(Kind.BROWSE, task(title + "(0/1)", "浏览5秒+600").kind);
            assertEquals(Kind.MANUAL, task(title + "(0/1)", "完成下单后领奖").kind);
        }
    }
    @Test public void passiveAlipayVisitDoesNotDraw() {
        assertEquals(Kind.VISIT, task("去支付宝抽1000元红包(0/1)跳转得+400", "").kind);
        assertEquals(Kind.MANUAL, task("去支付宝抽1000元红包(0/1)", "完成抽奖一次").kind);
        assertEquals("com.eg.android.AlipayGphone", task("逛一逛蚂蚁庄园(0/1)", "点击去逛").external);
    }
    @Test public void reportedFirstRowIsAJumpVisitEvenInsideTaobao() {
        Task task = task("去抽最高1万淘金币(0/1)", "跳转得+400");
        assertEquals(Kind.VISIT, task.kind); assertEquals(0, task.seconds);
        assertEquals(Kind.MANUAL, task("去抽最高1万淘金币(0/1)", "完成抽奖一次").kind);
    }
    @Test public void theCoinJumpTitleSurvivesMissingDetailsAndTheReportedOcrTypo() {
        for (String title : List.of("去抽最高1万淘金币", "去抽最高1万海金币")) {
            assertEquals(Kind.VISIT, task(title + "(0/1)", "+400").kind);
            assertEquals(Kind.BROWSE, task(title + "(0/1)", "浏览5秒+400").kind);
            assertEquals(Kind.MANUAL, task(title + "(0/1)", "完成抽奖一次").kind);
            assertEquals(Kind.MANUAL, task(title + "(0/1)", "完成下单后领奖").kind);
        }
    }
    @Test public void browseRequirementsTakePriorityOverPromotionalTitles() {
        for (String title : List.of("你关注的商品都可抵", "去组队可加快升级", "看看趣味小游戏", "去逛金豆夺宝")) {
            Task task = task(title + "(0/1)", "浏览5秒+400");
            assertEquals(title, Kind.BROWSE, task.kind); assertEquals(5, task.seconds);
        }
        assertEquals(Kind.SEARCH, task("搜一搜你心仪的宝贝(2/10)", "浏览15秒+400").kind);
    }
    @Test public void browseWordsDoNotOverridePurchaseRequirementsOrExcludedApps() {
        for (String title : List.of("下单购买好物", "开通88VIP", "去点淘浏览", "去百度App浏览")) {
            assertEquals(title, Kind.MANUAL, task(title + "(0/1)", "浏览15秒+400").kind);
        }
        for (String detail : List.of("浏览15秒并关注", "浏览后下单", "浏览5个商品")) {
            assertEquals(detail, Kind.MANUAL, task("浏览好物(0/1)", detail).kind);
        }
    }
    @Test public void userReportedWeiboOcrTypoStillIdentifiesTheExplicitTarget() {
        for (String title : List.of("去微博看看热搜", "去微傅看者热搜")) {
            Task task = task(title + "(0/1)", "点击去逛+400");
            assertEquals("com.sina.weibo", task.external); assertEquals(Kind.VISIT, task.kind);
        }
        assertEquals("", TaskRules.externalPackage("去不认识的应用看看热搜"));
    }
    @Test public void searchIsRecognized() { assertEquals(Kind.SEARCH, task("搜索好物(0/5)", "浏览15秒").kind); }
    @Test public void completedTaskIsNotExecuted() { assertEquals(Kind.DONE, task("好物随心逛(10/10)", "浏览15秒").kind); }
    @Test public void claimButtonsAndAlreadyReceivedRowsAreClassified() {
        Task before = parse(screen("浏览领奖(1/2)", "", "领取奖励")).get(0);
        assertEquals(Kind.CLAIM, before.kind);
        assertEquals(Kind.DONE, parse(screen("浏览领奖(1/2)", "", "已领取")).get(0).kind);
    }
    @Test public void milestonesAreScopedToRewardHeader() {
        List<Node> nodes = screen("好物随心逛(0/10)", "浏览15秒", "去完成");
        nodes.add(node("立即领取", 410, 850, 280));
        nodes.add(node("立即领取", 300, 2400, 260));
        assertEquals(1, parse(nodes).stream().filter(t -> t.key.startsWith("milestone-")).count());
    }
    @Test public void securityLoginAndPaymentRequireAttention() {
        for (String text : List.of("请拖动滑块完成验证", "将满足描述的所有图片拖放到指定区域", "请先登录", "确认付款", "同意并授权")) {
            assertFalse(text, TaskRules.guardReason(List.of(node(text, 50, 50, 500))).isEmpty());
        }
        assertTrue(TaskRules.guardReason(screen("逛一逛支付宝农场(0/1)", "点击去逛", "去完成")).isEmpty());
    }
    @Test public void searchSuggestionsAreNotResults() {
        List<Node> nodes = screen("综合", "销量", "筛选"); nodes.add(node("历史搜索", 20, 50, 500));
        assertFalse(TaskRunner.resultsReady(new TaskRunner.Snapshot(nodes, 1440, 3200, TaskRules.TAOBAO)));
    }
    @Test public void energyEntryHasItsOwnBoundsInsideAMergedOcrLine() {
        Node entry = TaskRules.energyEntry(List.of(node("去赚金币", 30, 1200, 200),
                node("升级赛车拿钱", 350, 1200, 700), node("去赚能量", 1160, 1200, 205)));
        assertNotNull(entry); assertEquals("去赚能量", entry.text);
        assertEquals(1160, entry.left); assertEquals(1365, entry.right);
    }
    @Test public void splitEnergyEntryCharactersCanBeJoinedAtAdjacentCoordinates() {
        Node entry = TaskRules.energyEntry(List.of(node("去赚", 1160, 1200, 100), node("能量", 1265, 1200, 100)));
        assertNotNull(entry); assertEquals("去赚能量", entry.text);
        assertEquals(1160, entry.left); assertEquals(1365, entry.right);
    }
    @Test public void entryFragmentsCannotJoinAcrossRowsOrWideGapsOrPromotionalText() {
        assertNull(TaskRules.energyEntry(List.of(node("去赚", 100, 1200, 100), node("能量", 100, 1500, 100))));
        assertNull(TaskRules.energyEntry(List.of(node("去赚", 100, 1200, 100), node("能量", 1000, 1200, 100))));
        assertNull(TaskRules.energyEntry(List.of(node("浏览15秒赚600能量", 300, 1800, 700))));
        assertNull(TaskRules.energyEntry(List.of(node("去赚金币", 100, 1200, 200))));
    }
}
