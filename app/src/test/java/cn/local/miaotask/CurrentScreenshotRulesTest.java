package cn.local.miaotask;

import org.junit.Test;
import java.util.List;
import static org.junit.Assert.*;
import cn.local.miaotask.TaskRules.Node;

public class CurrentScreenshotRulesTest {
    private Node node(String text, int y) { return new Node(text, 80, y, 1320, y + 55); }
    @Test public void imageDragVerificationIsDetectedAcrossOcrLinesWithoutTheExactOldSentence() {
        assertFalse(TaskRules.guardReason(List.of(node("将满足描述的所有图片拖到指定区域后提交", 600),
                node("提交", 2500), node("反馈码：23244d696", 2650))).isEmpty());
        assertFalse(TaskRules.guardReason(List.of(node("将满足描迷的所有图", 600), node("片拖故到指定区域后", 665),
                node("提交", 2500), node("反馈码", 2650))).isEmpty());
    }
    @Test public void normalProductCopyDoesNotBecomeAnImageVerificationPage() {
        assertTrue(TaskRules.guardReason(List.of(node("提交订单享优惠", 600), node("查看所有图片", 900),
                node("按配送区域发货", 1000))).isEmpty());
    }
    @Test public void everySearchProductIsNowChineseAndUniqueForTheWholeRun() {
        List<String> words = TaskRunner.newSearchQueries();
        assertEquals(80, words.size()); assertEquals(80, new java.util.HashSet<>(words).size());
        for (String word : words) assertTrue(word, word.matches("[\\u3400-\\u9FFF]{2,12}"));
    }
}
