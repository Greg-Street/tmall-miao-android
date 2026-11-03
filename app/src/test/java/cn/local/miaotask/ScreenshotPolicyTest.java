package cn.local.miaotask;

import org.junit.Test;
import static org.junit.Assert.*;

public class ScreenshotPolicyTest {
    @Test public void firstRequestDoesNotWait() { assertEquals(0, ScreenshotPolicy.intervalDelay(500, -1)); }
    @Test public void backToBackRequestsWaitForTheMinimumInterval() {
        assertEquals(700, ScreenshotPolicy.intervalDelay(1300, 1000));
        assertEquals(0, ScreenshotPolicy.intervalDelay(2000, 1000));
    }
}
