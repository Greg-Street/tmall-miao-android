package cn.local.miaotask;

import org.junit.Test;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import static org.junit.Assert.*;
import cn.local.miaotask.TaskRules.Node;
import cn.local.miaotask.TaskRunner.Snapshot;

public class ListScanOrderTest {
    private Node node(String text, int y, int left, int width) { return new Node(text, left, y, left + width, y + 65); }
    @Test public void anExhaustedCurrentPageLooksAboveBeforeLookingBelow() {
        AtomicLong clock = new AtomicLong(); List<Boolean> directions = new ArrayList<>(); List<Integer> tasks = new ArrayList<>();
        TaskRunner.Driver driver = new TaskRunner.Driver() {
            int page = 1; boolean browsing;
            Snapshot snapshot(List<Node> nodes) { return new Snapshot(nodes, 1440, 3200, TaskRules.TAOBAO); }
            public Snapshot readForeground() { return snapshot(List.of()); }
            public Snapshot read() {
                if (browsing) return snapshot(List.of(node("精选好物", 500, 100, 500), node("今日推荐", 900, 100, 500), node("任务已完成", 1200, 100, 500)));
                return snapshot(List.of(node("做任务赚能量", 630, 500, 500),
                        node((page == 1 ? "已做任务(1/1)" : page == 0 ? "上方浏览任务(0/1)" : "下方浏览任务(0/1)"), 1110, 260, 700),
                        node("浏览5秒+400", 1190, 260, 700), node(page == 1 ? "已完成" : "去完成", 1140, 1150, 220)));
            }
            public void tap(Node target) { tasks.add(page); browsing = true; }
            public void swipe(boolean reverse) { directions.add(reverse); page = Math.max(0, Math.min(2, page + (reverse ? -1 : 1))); }
            public void back() { browsing = false; }
            public void launchTaobao() { }
            public boolean setSearchText(String text) { return false; }
        };
        new TaskRunner(driver, new TaskRunner.Control(clock::get, clock::addAndGet), s -> {}, 1, true, false, 2, 1).run();
        assertEquals(List.of(0), tasks); assertTrue(directions.get(0));
    }
}
