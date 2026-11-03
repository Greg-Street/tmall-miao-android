package cn.local.miaotask;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Geometry and classification adapted from the v7 task_model.py; see ATTRIBUTION.md. */
public final class TaskRules {
    private TaskRules() {}
    private static final Pattern ACTION = Pattern.compile("去完成|去浏览|去逛逛|逛一逛|逛一下|去看看|去搜索|搜一下|去观看|去参与|去领取|领取奖励|立即领取|立即领|领能量|领体力|领奖励|已完成|已领取|明日再来|去签到");
    private static final Pattern PROGRESS = Pattern.compile("[\\[(](\\d{1,4})/(\\d{1,4})[\\])]");
    private static final Pattern DURATION = Pattern.compile("(?:浏览|停留|观看|逛|滑动|下滑)[^\\d]{0,5}(\\d{1,3})[秒sS]");
    private static final Pattern MANUAL = Pattern.compile("下单|购买|支付\\d|支付订单|充值|开通|订阅|邀请|助力|组队|分享|加购|收藏|关注|授权|登录|登陆|实名|下载|安装|答题|考试|拍照|拍摄|发布|评价|种树|喂鸡|浇水|游戏|拉\\d+人");
    private static final Pattern GUARD = Pattern.compile("滑块|拖动.{0,8}验证|安全验证|请完成验证|验证码|人机验证|操作过于频繁|账号存在风险|访问被拒绝|将满足描述的所有图片拖放|短信登录|登录即同意|请先登录|立即登录|授权登录|确认付款|支付密码|确认支付|同意并授权");
    public static final String TAOBAO = "com.taobao.taobao";

    public enum Kind { BROWSE, SEARCH, VISIT, CLAIM, DONE, MANUAL }

    public static final class Node {
        public final String text;
        public final int left, top, right, bottom;
        public Node(String text, int left, int top, int right, int bottom) {
            this.text = text == null ? "" : text;
            this.left = left; this.top = top; this.right = right; this.bottom = bottom;
        }
        public int x() { return (left + right) / 2; }
        public int y() { return (top + bottom) / 2; }
    }

    public static final class Task {
        public final String key, title, detail, action, reason, external;
        public final Node button;
        public final int current, total, seconds;
        public final Kind kind;
        Task(String title, String detail, String action, Node button, int current,
             int total, int seconds, Kind kind, String reason, String external, String key) {
            this.title = title; this.detail = detail; this.action = action; this.button = button;
            this.current = current; this.total = total; this.seconds = seconds;
            this.kind = kind; this.reason = reason; this.external = external;
            this.key = key == null ? normalize(title) : key;
        }
    }

    public static String normalize(String value) {
        return Normalizer.normalize(value == null ? "" : value, Normalizer.Form.NFKC).replaceAll("\\s+", "");
    }

    public static boolean matches(String value, String regex) {
        return Pattern.compile(regex).matcher(normalize(value)).find();
    }

    static boolean actionLabel(String text) { return ACTION.matcher(normalize(text)).matches(); }

    static String skipReason(String text) {
        text = normalize(text);
        if (matches(text, "点[淘陶掏海]")) return "按要求跳过点淘任务";
        if (matches(text, "百[度渡]")) return "按要求跳过百度 App 任务";
        return "";
    }

    static Node energyEntry(List<Node> fragments) {
        return joinedEntry(fragments, "^(去?赚能量|去?赚体力|去?赚喵币|做任务)$");
    }

    static List<Node> coinEntries(List<Node> fragments) {
        List<Node> entries = new ArrayList<>();
        for (String label : new String[]{"领淘金币", "领海金币", "淘金币"}) {
            Node entry = joinedEntry(fragments, "^" + label + "$");
            if (entry != null) entries.add(entry);
        }
        return entries;
    }

    private static Node joinedEntry(List<Node> fragments, String regex) {
        for (int start = 0; start < fragments.size(); start++) {
            Node first = fragments.get(start);
            String value = "";
            int right = first.left, top = first.top, bottom = first.bottom;
            for (int end = start; end < Math.min(fragments.size(), start + 4); end++) {
                Node part = fragments.get(end);
                int height = Math.max(first.bottom - first.top, part.bottom - part.top);
                if (end > start && (part.left < first.left || part.left - right > height
                        || Math.abs(part.y() - first.y()) > height / 2)) break;
                value += normalize(part.text);
                if (value.length() > 4) break;
                right = Math.max(right, part.right); top = Math.min(top, part.top); bottom = Math.max(bottom, part.bottom);
                if (matches(value, regex)) {
                    return new Node(value, first.left, top, right, bottom);
                }
            }
        }
        return null;
    }

    public static String allText(List<Node> nodes) {
        StringBuilder out = new StringBuilder();
        for (Node node : nodes) out.append(node.text).append('\n');
        return out.toString();
    }

    public static String guardReason(List<Node> nodes) {
        Matcher found = GUARD.matcher(normalize(allText(nodes)));
        if (found.find()) return "检测到「" + found.group() + "」，请本人处理后点击继续";
        String text = normalize(allText(nodes));
        int cues = 0;
        for (String cue : new String[]{"满足描", "所有图", "指定区", "反馈码", "拖放到|拖到|拖拽到|拖至"})
            if (matches(text, cue)) cues++;
        return cues >= 2 && matches(text, "提交|拖放|拖拽|拖到|拖至") ? "检测到图片拖放验证，请本人处理后点击继续" : "";
    }

    public static boolean isList(List<Node> nodes, int width, int height) {
        String text = normalize(allText(nodes));
        boolean heading = matches(text, "累计任务奖励|做任务赚|赚能量任务|任务中心|任务列表");
        List<Task> tasks = parse(nodes, width, height);
        if (!tasks.isEmpty() && (heading || tasks.size() >= 2
                || matches(text, "以上奖励均为最高奖励|以实际获得奖励为准"))) return true;
        return heading && nodes.stream().anyMatch(n -> {
                    Matcher progress = PROGRESS.matcher(normalize(n.text));
                    return progress.find() && progress.start() > 0 && Integer.parseInt(progress.group(2)) > 0
                            && progress.group(1).equals(progress.group(2));
                });
    }

    public static List<Task> parse(List<Node> nodes, int width, int height) {
        List<Node> buttons = new ArrayList<>();
        for (Node n : nodes) {
            if (!ACTION.matcher(normalize(n.text)).matches() || n.x() <= width * .55
                    || n.y() <= 0 || n.bottom >= height * .985) continue;
            if (buttons.stream().noneMatch(old -> Math.abs(old.y() - n.y()) < height * .012)) buttons.add(n);
        }
        buttons.sort(Comparator.comparingInt(Node::y));
        List<Task> tasks = new ArrayList<>();
        for (int i = 0; i < buttons.size(); i++) {
            Node button = buttons.get(i);
            int y = button.y();
            double prev = i == 0 ? y - height * .16 : buttons.get(i - 1).y();
            double next = i == buttons.size() - 1 ? y + height * .16 : buttons.get(i + 1).y();
            double top = Math.max((prev + y) / 2, y - height * .055);
            double bottom = Math.min((next + y) / 2, y + height * .042);
            List<Node> row = new ArrayList<>();
            for (Node n : nodes) {
                if (n.y() >= top && n.y() <= bottom && n.left < button.left - width * .025
                        && n.bottom - n.top < height * .1 && !ACTION.matcher(normalize(n.text)).matches()
                        && !matches(n.text, "O1CN|!!\\d+|\\.(png|jpg|webp)|^[^\\p{L}\\p{N}]+$")) row.add(n);
            }
            row.sort(Comparator.comparingLong((Node n) -> Math.round(n.y() / (height * .008))).thenComparingInt(n -> n.left));
            LinkedHashSet<String> texts = new LinkedHashSet<>();
            for (Node n : row) if (!normalize(n.text).isEmpty()) texts.add(normalize(n.text));
            String titleLine = texts.stream().filter(t -> PROGRESS.matcher(t).find()).findFirst().orElse("");
            Matcher progress = PROGRESS.matcher(titleLine);
            if (!progress.find()) continue;
            int current = Integer.parseInt(progress.group(1));
            int total = Integer.parseInt(progress.group(2));
            if (total <= 0 || current > total) continue;
            String title = titleLine.substring(0, progress.start()).replaceFirst("^搜一[機机擅](?=你)", "搜一搜");
            String suffix = titleLine.substring(progress.end());
            String detail = suffix.isEmpty() ? String.join(" ", texts).replace(titleLine, "").trim() : suffix;
            if (title.isEmpty()) continue;
            String combined = normalize(title + " " + detail);
            Matcher duration = DURATION.matcher(combined);
            int seconds = duration.find() ? Integer.parseInt(duration.group(1)) : 0;
            String action = normalize(button.text);
            String external = externalPackage(title);
            boolean flash = title.equals("领闪购红包下单直接省") && combined.contains("点击去逛")
                    && !matches(detail, "需下单|完成下单|支付|购买|消费|订单");
            boolean passive = matches(detail, "^(跳转得|点击去逛)");
            boolean browseOnly = combined.contains("浏览") && !MANUAL.matcher(detail).find()
                    && !matches(title, "下单|购买|支付\\d|支付订单|充值|开通|授权|登录|登陆|实名|下载|安装|拍照|拍摄|发布|评价");
            String safetyText = flash ? combined.replace("领闪购红包下单直接省", "领闪购红包") : combined;
            String excluded = skipReason(String.join("", texts));
            Kind kind;
            String reason = "";
            if (matches(action, "^(已完成|已领取|明日再来)$") || current >= total) kind = Kind.DONE;
            else if (!excluded.isEmpty()) {
                kind = Kind.MANUAL; reason = excluded;
            } else if ((!browseOnly && MANUAL.matcher(safetyText).find()) || (matches(combined, "抽奖|夺宝|抽\\d+元红包") && !passive && !browseOnly)
                    || matches(combined, "浏览\\d+个|逛\\d+个|\\d+件商品")) {
                kind = Kind.MANUAL; reason = "需要交易、授权、互动或其他手动操作";
            } else if (matches(action, "^(去领取|领取奖励|立即领取|立即领|领能量|领体力|领奖励)$")) kind = Kind.CLAIM;
            else if (flash || passive || !external.isEmpty()
                    || (seconds == 0 && matches(title, "^去抽最高\\d+万?[淘海]金币$"))) kind = Kind.VISIT;
            else if (matches(combined, "搜一搜|搜索")) kind = Kind.SEARCH;
            else if (seconds > 0) kind = Kind.BROWSE;
            else if (matches(combined, "农场|菜鸟|闲鱼|饿了么|优酷|高德|闪购|天猫|去逛")) kind = Kind.VISIT;
            else if (matches(combined, "浏览|逛|看|签到|打卡|频道|会场")) kind = Kind.BROWSE;
            else { kind = Kind.MANUAL; reason = "未支持的任务类型"; }
            tasks.add(new Task(title, detail, action, button, current, total, seconds, kind, reason, external, null));
        }
        Node header = nodes.stream().filter(n -> normalize(n.text).equals("累计任务奖励")).findFirst().orElse(null);
        if (header != null) {
            int rowTop = tasks.stream().mapToInt(t -> t.button.top).min().orElse(height);
            LinkedHashSet<String> keys = new LinkedHashSet<>();
            for (Node n : nodes) {
                if (!matches(n.text, "立即领取|领取奖励|点击领取") || n.top < header.top - height * .01
                        || n.bottom >= rowTop || n.bottom - n.top >= height * .1 || n.right - n.left >= width * .5) continue;
                String key = "milestone-" + Math.round(n.x() / (double) width * 10);
                if (keys.add(key)) tasks.add(new Task("领取累计任务奖励", n.text, "立即领取", n,
                        0, 1, 0, Kind.CLAIM, "", "", key));
            }
        }
        return tasks;
    }

    public static String externalPackage(String title) {
        title = title.replace("微傅", "微博");
        String[][] apps = {{"快手极速版", "com.kuaishou.nebula"}, {"快手", "com.smile.gifmaker"},
                {"微博", "com.sina.weibo"}, {"抖音", "com.ss.android.ugc.aweme"},
                {"支付宝", "com.eg.android.AlipayGphone"}, {"蚂蚁庄园", "com.eg.android.AlipayGphone"},
                {"蚂蚁森林", "com.eg.android.AlipayGphone"}, {"京东", "com.jingdong.app.mall"},
                {"拼多多", "com.xunmeng.pinduoduo"}, {"闲鱼", "com.taobao.idlefish"},
                {"菜鸟", "com.cainiao.wireless"}, {"高德", "com.autonavi.minimap"}, {"优酷", "com.youku.phone"}};
        for (String[] app : apps) if (title.contains(app[0])) return app[1];
        return "";
    }

    static boolean completionHint(List<Node> nodes, int height) {
        return nodes.stream().anyMatch(n -> n.bottom - n.top < height * .12 && matches(n.text,
                "^(?:任务(?:已|已经)?完成(?:啦)?|浏览(?:已)?完成|完成浏览|奖励已到账|恭喜完成|点我领取奖励|快去领奖吧)[!！,，。]*(?:快去领奖吧|\\+\\d+.*)?$"));
    }
}
