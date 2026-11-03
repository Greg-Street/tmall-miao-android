package cn.local.miaotask;

final class ScreenshotPolicy {
    static final long MIN_INTERVAL_MS = 1000;
    static final long BROWSE_INTERVAL_MS = 1500;
    private ScreenshotPolicy() {}

    static long intervalDelay(long now, long lastRequest) {
        return lastRequest < 0 ? 0 : Math.max(0, MIN_INTERVAL_MS - (now - lastRequest));
    }

}
