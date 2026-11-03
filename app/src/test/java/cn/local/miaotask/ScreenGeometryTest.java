package cn.local.miaotask;

import org.junit.Test;
import static org.junit.Assert.*;
import cn.local.miaotask.TaskRules.Node;

public class ScreenGeometryTest {
    @Test public void browsePathsInEveryDirectionStayAwayFromNavigationAndPurchaseControls() {
        for (boolean horizontal : new boolean[]{false, true}) for (boolean reverse : new boolean[]{false, true})
            for (double origin : new double[]{0, .5, .9999}) for (double distance : new double[]{0, .9999}) {
                float[] p = ScreenGeometry.browsePath(1440, 3168, reverse, horizontal, origin, distance, .9999);
                for (int i = 0; i < 4; i += 2) {
                    assertTrue(p[i] > 1440 * .2 && p[i] < 1440 * .8);
                    assertTrue(p[i + 1] > 3168 * .25 && p[i + 1] < 3168 * .8);
                }
                if (horizontal) assertEquals(!reverse, p[2] > p[0]);
                else assertEquals(reverse, p[3] > p[1]);
            }
    }
    @Test public void browsePathsRejectInvalidDimensionsAndFractions() {
        assertThrows(IllegalArgumentException.class, () -> ScreenGeometry.browsePath(0, 3168, false, true, 0, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> ScreenGeometry.browsePath(1440, 3168, false, true, Double.NaN, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> ScreenGeometry.browsePath(1440, 3168, false, true, 0, 1, 0));
    }
    @Test public void variableTapPointsStayInsideTheCentralPartOfTheRecognizedButton() {
        Node button = new Node("去完成", 1100, 1000, 1300, 1100);
        java.util.Random random = new java.util.Random(24);
        java.util.Set<String> points = new java.util.HashSet<>();
        for (int i = 0; i < 1000; i++) {
            Node point = ScreenGeometry.tapPoint(button, random.nextDouble(), random.nextDouble());
            assertTrue(point.x() >= 1140 && point.x() < 1260); assertTrue(point.y() >= 1020 && point.y() < 1080);
            points.add(point.x() + ":" + point.y());
        }
        assertTrue(points.size() > 100);
    }
    @Test public void onePixelTargetsNeverProduceAnOutsideTouch() {
        Node tiny = new Node("搜索输入", 5, 9, 6, 10);
        Node point = ScreenGeometry.tapPoint(tiny, .99, 0);
        assertEquals(5, point.x()); assertEquals(9, point.y());
    }
    @Test public void emptyAreasAndInvalidFractionsCannotBecomeTapCoordinates() {
        assertThrows(IllegalArgumentException.class, () -> ScreenGeometry.tapPoint(new Node("", 0, 0, 0, 1), .5, .5));
        for (double value : new double[]{-.1, 1, Double.NaN, Double.POSITIVE_INFINITY})
            assertThrows(IllegalArgumentException.class, () -> ScreenGeometry.tapPoint(new Node("", 0, 0, 5, 5), value, .5));
    }
    @Test public void windowScreenshotRestoresStatusBarOffsetAndOcrScale() {
        Node capture = new Node("", 0, 96, 1440, 3096);
        Node mapped = ScreenGeometry.mapText(new Node("去完成", 900, 750, 990, 810), capture, 1440, 3000, .75f);
        assertEquals(1200, mapped.left); assertEquals(1096, mapped.top);
        assertEquals(1320, mapped.right); assertEquals(1176, mapped.bottom);
    }
    @Test public void displayCaptureUsesOriginalPhysicalCoordinates() {
        Node capture = new Node("", 0, 0, 1440, 3168);
        Node mapped = ScreenGeometry.mapText(new Node("搜索", 750, 75, 975, 120), capture, 1440, 3168, .75f);
        assertEquals(1000, mapped.left); assertEquals(100, mapped.top);
    }
    @Test public void scaledWindowCaptureMapsBothAxes() {
        Node mapped = ScreenGeometry.mapText(new Node("按钮", 50, 100, 100, 150), new Node("", 20, 80, 1020, 2080), 500, 1000, 1);
        assertEquals(120, mapped.left); assertEquals(280, mapped.top);
    }
    @Test public void topRegionOcrRestoresTheSamePhysicalEntryCoordinates() {
        Node full = ScreenGeometry.mapText(new Node("去赚能量", 870, 900, 1020, 950),
                new Node("", 0, 96, 1440, 3096), 1440, 3000, .75f);
        Node quick = ScreenGeometry.mapText(new Node("去赚能量", 580, 600, 680, 633),
                new Node("", 0, 96, 1440, 1896), 1440, 1800, .5f);
        assertEquals(full.left, quick.left); assertEquals(full.top, quick.top);
        assertEquals(full.right, quick.right); assertTrue(Math.abs(full.bottom - quick.bottom) <= 1);
    }
    @Test public void unobstructedPanelDoesNotMove() {
        Node overlay = new Node("", 10, 170, 640, 550);
        assertSame(overlay, ScreenGeometry.avoidGesture(overlay, new Node("", 710, 1500, 730, 2400), 1440, 3168, 20));
    }
    @Test public void panelMovesOnlyWhenItWouldInterceptATap() {
        Node target = new Node("", 195, 390, 205, 410);
        Node result = ScreenGeometry.avoidGesture(new Node("", 10, 170, 640, 550), target, 1440, 3168, 20);
        assertNotNull(result); assertFalse(ScreenGeometry.overlaps(result, target, 20));
    }
    @Test public void panelCanAvoidTheFullSwipePath() {
        Node path = new Node("", 710, 1500, 730, 2400);
        Node result = ScreenGeometry.avoidGesture(new Node("", 500, 1600, 1130, 1980), path, 1440, 3168, 20);
        assertNotNull(result); assertFalse(ScreenGeometry.overlaps(result, path, 20));
    }
    @Test public void impossiblePlacementDoesNotPretendToBeSafe() {
        assertNull(ScreenGeometry.avoidGesture(new Node("", 0, 0, 600, 1000), new Node("", 0, 0, 600, 1000), 600, 1000, 20));
    }
}
