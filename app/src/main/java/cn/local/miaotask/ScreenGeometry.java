package cn.local.miaotask;

import cn.local.miaotask.TaskRules.Node;

final class ScreenGeometry {
    private ScreenGeometry() {}

    static Node tapPoint(Node target, double xFraction, double yFraction) {
        if (target.right <= target.left || target.bottom <= target.top
                || !(xFraction >= 0 && xFraction < 1) || !(yFraction >= 0 && yFraction < 1))
            throw new IllegalArgumentException("点击区域无效");
        int x = Math.min(target.right - 1, target.left + (int) ((target.right - target.left) * (.2 + xFraction * .6)));
        int y = Math.min(target.bottom - 1, target.top + (int) ((target.bottom - target.top) * (.2 + yFraction * .6)));
        return new Node(target.text, x, y, x, y);
    }

    static float[] browsePath(int width, int height, boolean reverse, boolean horizontal,
                              double origin, double distance, double drift) {
        if (width <= 0 || height <= 0) throw new IllegalArgumentException("页面尺寸无效");
        for (double value : new double[]{origin, distance, drift})
            if (!(value >= 0 && value < 1)) throw new IllegalArgumentException("滑动比例无效");
        float span = (float) (.16 + distance * .12);
        if (horizontal) {
            float x = width * (reverse ? .65f : .35f), y = height * (float) (.48 + origin * .16);
            return new float[]{x, y, x + width * span * (reverse ? -1 : 1), y + height * (float) ((drift - .5) * .025)};
        }
        float x = width * (float) (.40 + origin * .20), y = height * (reverse ? .45f : .74f);
        return new float[]{x, y, x + width * (float) ((drift - .5) * .035), y + height * span * (reverse ? 1 : -1)};
    }

    static Node mapText(Node text, Node capture, int imageWidth, int imageHeight, float ocrScale) {
        float xScale = (capture.right - capture.left) / (imageWidth * ocrScale);
        float yScale = (capture.bottom - capture.top) / (imageHeight * ocrScale);
        return new Node(text.text, capture.left + Math.round(text.left * xScale), capture.top + Math.round(text.top * yScale),
                capture.left + Math.round(text.right * xScale), capture.top + Math.round(text.bottom * yScale));
    }

    static boolean overlaps(Node a, Node b, int margin) {
        return a.left < b.right + margin && a.right > b.left - margin
                && a.top < b.bottom + margin && a.bottom > b.top - margin;
    }

    static Node avoidGesture(Node overlay, Node gesture, int screenWidth, int screenHeight, int margin) {
        if (!overlaps(overlay, gesture, margin)) return overlay;
        int width = overlay.right - overlay.left, height = overlay.bottom - overlay.top;
        int right = screenWidth - margin - width, bottom = screenHeight - margin - height;
        for (int[] point : new int[][]{{right, margin * 3}, {margin, margin * 3}, {right, bottom}, {margin, bottom}}) {
            Node candidate = new Node("", point[0], point[1], point[0] + width, point[1] + height);
            if (candidate.left >= 0 && candidate.top >= 0 && candidate.right <= screenWidth
                    && candidate.bottom <= screenHeight && !overlaps(candidate, gesture, margin)) return candidate;
        }
        return null;
    }
}
