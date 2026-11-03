package cn.local.miaotask;

import org.junit.Test;
import java.util.ArrayList;
import java.util.List;
import static org.junit.Assert.*;
import cn.local.miaotask.TaskRules.Node;

public class TaskPageRulesTest {
    private Node node(String text, int x, int y, int width) { return new Node(text, x, y, x + width, y + 65); }
    private List<Node> panel() { return new ArrayList<>(List.of(node("做任务赚能量", 500, 630, 500))); }
    private void row(List<Node> nodes, String title, int y) {
        nodes.add(node(title, 260, y, 700)); nodes.add(node("浏览15秒+400", 260, y + 80, 700));
        nodes.add(node("去完成", 1150, y + 30, 220));
    }
    @Test public void completedRowIdentifiesThePanelWhenItsActionButtonDisappears() {
        List<Node> nodes = panel(); nodes.add(node("查看双11活动攻略(1/1)", 260, 1110, 700));
        assertTrue(TaskRules.isList(nodes, 1440, 3200));
    }
    @Test public void headingAloneIsStillALoadingPanel() { assertFalse(TaskRules.isList(panel(), 1440, 3200)); }
    @Test public void scrolledTaskRowsRemainAListWithoutTheHeadingOrRewardBar() {
        List<Node> nodes = new ArrayList<>(); row(nodes, "好物随心逛(0/5)", 700); row(nodes, "拍立淘逛感兴趣的宝贝(0/5)", 970);
        assertTrue(TaskRules.isList(nodes, 1440, 3200));
    }
    @Test public void bottomNoteAndOneTaskStillIdentifyTheScrolledPanel() {
        List<Node> nodes = new ArrayList<>(); row(nodes, "去玩趣味小游戏(0/3)", 2400);
        nodes.add(node("注：以上奖励均为最高奖励，以实际获得奖励为准", 100, 2900, 1200));
        assertTrue(TaskRules.isList(nodes, 1440, 3200));
    }
    @Test public void oneIsolatedTaskWithoutPanelEvidenceIsNotACompleteList() {
        List<Node> nodes = new ArrayList<>(); row(nodes, "好物随心逛(0/5)", 700);
        assertFalse(TaskRules.isList(nodes, 1440, 3200));
    }
    @Test public void completionHintIsPerVisibleTextNotAnArticleSubstring() {
        assertTrue(TaskRules.completionHint(List.of(node("任务已完成，快去领奖吧", 100, 1000, 600)), 3200));
        assertFalse(TaskRules.completionHint(List.of(node("查看任务完成攻略，学习如何完成浏览", 100, 1000, 600)), 3200));
    }
    @Test public void loadingAndEmptyPageAreNotBrowseDestinations() {
        assertFalse(TaskRunner.destinationReady(new TaskRunner.Snapshot(List.of(node("加载中…", 100, 1000, 500)), 1440, 3200, TaskRules.TAOBAO)));
        assertFalse(TaskRunner.destinationReady(new TaskRunner.Snapshot(panel(), 1440, 3200, TaskRules.TAOBAO)));
    }
    @Test public void currentActivityScreenshotTaskNamesAreClassified() {
        String[][] cases = {{"去蚂蚁森林收能量(0/1)", "点击去逛+600", "VISIT"}, {"好物随心逛(0/10)", "浏览15秒+400", "BROWSE"},
                {"逛一逛支付宝农场(0/1)", "点击去逛+600", "VISIT"}, {"看看#长袖(0/10)", "浏览15秒+400", "BROWSE"},
                {"淘金币省钱等级考试(0/1)", "去答题最高得+1000", "MANUAL"}, {"去芭芭农场领水果(0/1)", "浏览5秒+600", "BROWSE"},
                {"好物沉浸看(0/5)", "浏览15秒+400", "BROWSE"}, {"拍立淘逛感兴趣的宝贝(0/5)", "浏览5秒+400", "BROWSE"},
                {"订阅活动通知(0/1)", "完成订阅+1000", "MANUAL"}, {"每拉1人最高2000能量(0/10)", "邀请好友成功助力最高得+2000", "MANUAL"},
                {"去玩趣味小游戏(0/3)", "玩1款游戏得+600", "MANUAL"}};
        for (String[] item : cases) {
            List<Node> nodes = panel(); nodes.add(node(item[0], 260, 1110, 700));
            nodes.add(node(item[1], 260, 1190, 700)); nodes.add(node("去完成", 1150, 1140, 220));
            assertEquals(item[0], item[2], TaskRules.parse(nodes, 1440, 3200).get(0).kind.name());
        }
    }
}
