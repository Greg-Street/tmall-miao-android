package cn.local.miaotask;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

import cn.local.miaotask.TaskRules.Kind;
import cn.local.miaotask.TaskRules.Node;
import cn.local.miaotask.TaskRules.Task;

final class TaskRunner {
    static final class Snapshot {
        final List<Node> nodes;
        final int width, height;
        final String pkg;
        final int windowId;
        final Node windowBounds;
        Snapshot(List<Node> nodes, int width, int height, String pkg) {
            this(nodes, width, height, pkg, -1, new Node("", 0, 0, width, height));
        }
        Snapshot(List<Node> nodes, int width, int height, String pkg, int windowId, Node windowBounds) {
            this.nodes = nodes; this.width = width; this.height = height; this.pkg = pkg;
            this.windowId = windowId; this.windowBounds = windowBounds;
        }
        boolean isList() { return TaskRules.TAOBAO.equals(pkg) && TaskRules.isList(nodes, width, height); }
        boolean isTaobaoHome() {
            return TaskRules.TAOBAO.equals(pkg) && !isList()
                    && nodes.stream().anyMatch(n -> n.y() > height * .9 && TaskRules.matches(n.text, "我的淘宝"))
                    && nodes.stream().anyMatch(n -> n.y() > height * .9 && TaskRules.matches(n.text, "购物车"));
        }
        List<Task> tasks() { return TaskRules.parse(nodes, width, height); }
        boolean has(String regex) { return TaskRules.matches(TaskRules.allText(nodes), regex); }
        Node find(String regex) {
            for (Node n : nodes) if (TaskRules.matches(n.text, regex)) return n;
            return null;
        }
    }

    interface Driver {
        Snapshot read();
        default Snapshot readForeground() { return read(); }
        default Snapshot readNavigation() { return read(); }
        default Snapshot readBrowse() { return read(); }
        void tap(Node node);
        void swipe(boolean reverse);
        default void browseSwipe(boolean reverse) { swipe(reverse); }
        default void browseSwipe(boolean reverse, boolean horizontal) { browseSwipe(reverse); }
        void back();
        void launchTaobao();
        boolean setSearchText(String text);
        default boolean submitSearch() { return false; }
        default boolean openCoinPage() { return false; }
    }

    static final class Stopped extends RuntimeException {}
    static final class Rescan extends RuntimeException {}
    static final class Attention extends RuntimeException {
        final boolean recoverable;
        Attention(String reason) { this(reason, false); }
        Attention(String reason, boolean recoverable) { super(reason); this.recoverable = recoverable; }
    }

    static final class Control {
        private volatile boolean stopped, paused;
        private boolean rescan;
        private long pausedAt, pausedTotal;
        private final java.util.function.LongSupplier time;
        private final Sleeper sleeper;
        interface Sleeper { void sleep(long ms) throws InterruptedException; }
        Control() { this(() -> System.nanoTime() / 1_000_000, Thread::sleep); }
        Control(java.util.function.LongSupplier time, Sleeper sleeper) { this.time = time; this.sleeper = sleeper; }
        synchronized void stop() { stopped = true; notifyAll(); }
        synchronized void setPaused(boolean value) {
            if (paused == value || stopped) return;
            if (value) pausedAt = clock();
            else { pausedTotal += clock() - pausedAt; rescan = true; }
            paused = value;
            notifyAll();
        }
        boolean paused() { return paused; }
        boolean actionable() { return !stopped && !paused; }
        private long clock() { return time.getAsLong(); }
        synchronized long now() { return (paused ? pausedAt : clock()) - pausedTotal; }
        synchronized void check() {
            while (paused && !stopped) {
                try { wait(150); } catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new Stopped(); }
            }
            if (stopped) throw new Stopped();
            if (rescan) { rescan = false; throw new Rescan(); }
        }
        void sleep(long ms) {
            long deadline = now() + ms;
            while (now() < deadline) {
                check();
                try { sleeper.sleep(Math.min(100, Math.max(1, deadline - now()))); }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new Stopped(); }
            }
            check();
        }
    }

    private final Driver driver;
    private final Control control;
    private final Consumer<String> log;
    private final int limit, visitSeconds, browseExtraSeconds;
    private final boolean autoEntry, crossApp;
    private final Map<String, Integer> failures = new HashMap<>();
    private final Map<String, Integer> quotas = new HashMap<>(), uses = new HashMap<>();
    private final Set<String> skipped = new HashSet<>();
    private boolean directEntryAllowed = true;
    private final List<String> searchQueries = newSearchQueries();
    private int searchIndex;
    private int attempts, executed, claimClicks, errors;

    TaskRunner(Driver driver, Control control, Consumer<String> log, int limit, boolean autoEntry, boolean crossApp,
               int visitSeconds, int browseExtraSeconds) {
        this.driver = driver; this.control = control; this.log = log;
        this.limit = limit; this.autoEntry = autoEntry; this.crossApp = crossApp;
        this.visitSeconds = visitSeconds; this.browseExtraSeconds = browseExtraSeconds;
    }

    String summary() { return "尝试 " + attempts + " 次 · 已执行 " + executed + " · 领取点击 " + claimClicks + " · 异常 " + errors; }

    void run() {
        Snapshot list = null;
        boolean launched = false, resuming = false;
        int consecutiveRecoveries = 0, pendingRecovery = 0;
        while (attempts < limit || pendingRecovery > 0) {
            Task activeTask = null;
            try {
                control.check();
                if (pendingRecovery > 0) {
                    int step = pendingRecovery; pendingRecovery = 0;
                    recover(step);
                    list = null; resuming = true;
                }
                if (!launched) {
                    driver.launchTaobao(); launched = true;
                    control.sleep(1800);
                }
                if (list == null) { list = findList(autoEntry || resuming, resuming); resuming = false; }
                if (attempts >= limit) break;
                Task task = select(list.tasks());
                if (task == null) {
                    log.accept("当前暂无待执行项，先向上查找，再向下继续");
                    for (boolean reverse : new boolean[]{true, false}) {
                        String previous = listSignature(list); int unchanged = 0;
                        for (int page = 0; page < 8 && task == null; page++) {
                            driver.swipe(reverse); control.sleep(400); list = read();
                            if (!list.isList()) list = findList(true, true);
                            task = select(list.tasks());
                            String signature = listSignature(list);
                            unchanged = signature.equals(previous) ? unchanged + 1 : 0;
                            previous = signature;
                            if (unchanged >= 2) break;
                        }
                        if (task != null) break;
                    }
                }
                if (task == null) { log.accept("可识别的任务已执行完或已跳过；" + summary()); return; }
                control.check();
                activeTask = task;
                directEntryAllowed = false;
                attempts++;
                uses.merge(operationKey(task), 1, Integer::sum);
                log.accept("执行「" + task.title + "」(" + task.current + "/" + task.total + ")");
                control.sleep(java.util.concurrent.ThreadLocalRandom.current().nextLong(250, 601));
                driver.tap(task.button);
                Snapshot destination = awaitDestination(task);
                if (task.kind != Kind.CLAIM && !destination.isList()) {
                    boolean external = !destination.pkg.isEmpty() && !TaskRules.TAOBAO.equals(destination.pkg);
                    if ((task.kind == Kind.VISIT || external) && task.seconds == 0) {
                        if (visitSeconds > 0) {
                            log.accept("已确认进入「" + task.title + "」，停留 " + visitSeconds + " 秒后返回");
                            control.sleep(visitSeconds * 1000L);
                        } else log.accept("已确认进入「" + task.title + "」，立即返回，不额外停留");
                    } else {
                        if (!external && (task.kind == Kind.SEARCH || destination.has("^历史搜索$|搜索发现|搜索后浏览|搜索有福利"))) destination = search(destination);
                        destination = browse(task, destination);
                    }
                }
                executed++;
                if (task.kind == Kind.CLAIM) claimClicks++;
                log.accept("已执行：" + task.title + "；继续下一项；" + summary());
                activeTask = null;
                list = destination.isList() ? destination : findList(true, true,
                        !destination.pkg.isEmpty() && !TaskRules.TAOBAO.equals(destination.pkg) ? destination : null);
                consecutiveRecoveries = 0;
            } catch (Rescan e) {
                log.accept("继续执行：重新读取页面与服务端任务进度");
                list = null; resuming = true;
                consecutiveRecoveries = 0; pendingRecovery = 0;
            } catch (Attention e) {
                errors++;
                if (activeTask != null) {
                    failures.merge(activeTask.key, 1, Integer::sum);
                    log.accept("任务操作中断：" + activeTask.title);
                }
                if (e.recoverable && consecutiveRecoveries < 2) {
                    pendingRecovery = ++consecutiveRecoveries;
                    log.accept("自动恢复（" + pendingRecovery + "/2）：" + e.getMessage() + "；"
                            + (pendingRecovery == 1 ? "返回并重新进入任务列表" : "重新打开淘宝并进入任务列表"));
                    continue;
                }
                control.setPaused(true);
                log.accept("需要本人处理：" + e.getMessage() + "；已暂停，处理后点击悬浮窗「继续」；" + summary());
                try { control.check(); } catch (Rescan resumed) { }
                log.accept("继续执行：重新读取页面与服务端任务进度");
                list = null; resuming = true;
                consecutiveRecoveries = 0; pendingRecovery = 0;
            }
        }
        log.accept("达到本轮上限；" + summary());
    }

    private void recover(int step) {
        control.check();
        Snapshot foreground = driver.readForeground();
        control.check();
        String reason = TaskRules.guardReason(foreground.nodes);
        if (!reason.isEmpty()) throw new Attention(reason);
        if (step == 1) {
            // findList reads the current page before deciding whether another Back is needed.
            control.sleep(400);
        } else {
            driver.launchTaobao();
            control.sleep(1200);
        }
    }

    private String listSignature(Snapshot screen) {
        StringBuilder signature = new StringBuilder();
        for (Task task : screen.tasks()) signature.append(task.key).append(':').append(task.current).append(':').append(task.kind).append(';');
        return signature.toString();
    }

    private Snapshot read() { return read(false); }

    private Snapshot read(boolean navigation) {
        control.check();
        Snapshot screen = navigation ? driver.readNavigation() : driver.read();
        control.check();
        String guard = TaskRules.guardReason(screen.nodes);
        if (!guard.isEmpty()) throw new Attention(guard);
        return screen;
    }

    private Task select(List<Task> tasks) {
        Task candidate = null;
        for (Task task : tasks) {
            String reason = task.reason;
            if (task.kind == Kind.DONE) continue;
            String operation = operationKey(task);
            int quota = quotas.computeIfAbsent(operation, key -> task.kind == Kind.CLAIM ? 1 : Math.max(0, task.total - task.current));
            if (uses.getOrDefault(operation, 0) >= quota) continue;
            if (!crossApp && !task.external.isEmpty()) reason = "本轮未开启跨应用访问";
            if (failures.getOrDefault(task.key, 0) >= 2) reason = "两次操作异常，本轮跳过";
            if (!reason.isEmpty()) {
                if (skipped.add(task.key)) log.accept("跳过「" + task.title + "」：" + reason);
                continue;
            }
            if (task.kind == Kind.CLAIM) return task;
            if (task.kind != Kind.MANUAL && candidate == null) candidate = task;
        }
        return candidate;
    }

    private String operationKey(Task task) { return task.kind == Kind.CLAIM ? "claim:" + task.key + ":" + task.current : task.key; }

    private Snapshot findList(boolean navigate, boolean recovering) {
        return findList(navigate, recovering, null);
    }

    private Snapshot findList(boolean navigate, boolean recovering, Snapshot current) {
        log.accept(recovering ? "返回淘宝，定位下一项任务" : "定位赚能量任务列表");
        long deadline = control.now() + (recovering ? 20_000 : 50_000);
        long nextTap = 0, nextEntryTap = 0, channelUntil = 0;
        int backSteps = 0, panelScrolls = 0;
        boolean launched = false, coinOpened = false;
        Snapshot screen = current == null ? readReturning(true) : current;
        while (control.now() < deadline) {
            if (screen.isList()) return screen;
            if (!navigate) { control.sleep(1000); screen = readReturning(true); continue; }
            Node entry = screen.find("^(去?赚能量|去?赚体力|去?赚喵币|做任务)$");
            boolean partialRows = !screen.tasks().isEmpty();
            boolean loading = partialRows || screen.has("做任务赚能量|累计任务奖励");
            boolean externalPage = !screen.pkg.isEmpty() && !TaskRules.TAOBAO.equals(screen.pkg);
            if (!coinOpened && !loading && !energyEntryReady(screen) && (directEntryAllowed || externalPage)) {
                if (externalPage) directEntryAllowed = true;
                if (driver.openCoinPage()) {
                    coinOpened = true; channelUntil = control.now() + 6000;
                    log.accept("直接打开淘金币频道，返回任务入口");
                    control.sleep(300); screen = readReturning(true); continue;
                }
            }
            if (coinOpened && !energyEntryReady(screen)) {
                if (control.now() >= channelUntil) throw new Attention("淘金币频道未能打开，请手动进入后继续", true);
                if (!loading) { control.sleep(250); screen = readReturning(true); continue; }
            }
            if (!TaskRules.TAOBAO.equals(screen.pkg)) {
                if (recovering && backSteps++ < 3) driver.back();
                else if (!launched) { driver.launchTaobao(); launched = true; }
                else throw new Attention("未能回到淘宝", true);
                control.sleep(1600); screen = readReturning(true); continue;
            }
            if (entry != null && !loading && entry.bottom - entry.top < screen.height * .15
                    && entry.right - entry.left < screen.width * .4) {
                if (control.now() >= nextEntryTap) {
                    log.accept("已找到「" + entry.text + "」，打开任务列表");
                    driver.tap(entry); nextTap = nextEntryTap = control.now() + 2000;
                }
            } else if (control.now() >= nextTap) {
                if (partialRows && panelScrolls < 1) {
                    log.accept("任务窗口仅识别到部分内容，向上复查列表");
                    driver.swipe(true); panelScrolls++; nextTap = control.now() + 1500;
                } else if (!loading && screen.isTaobaoHome()) {
                    Node coins = null;
                    for (Node n : screen.nodes) {
                        if (n.y() < screen.height * .7 && TaskRules.matches(n.text, "^(领[淘海]金币|淘金币|领淘金币抵现金|双11赢20亿)$")) { coins = n; break; }
                    }
                    if (coins == null) throw new Attention("已回到淘宝首页，未找到任务入口，请手动进入后继续", true);
                    driver.tap(coins); nextTap = control.now() + 1800;
                } else if (recovering && !loading && entry == null && backSteps < 4) {
                    driver.back(); backSteps++; nextTap = control.now() + 2000;
                    screen = readReturning(true); continue;
                }
            }
            control.sleep(300); screen = readReturning(true);
        }
        throw new Attention("未识别到完整任务列表", true);
    }

    private Snapshot readReturning(boolean findEntry) {
        control.check();
        Snapshot foreground = driver.readForeground();
        control.check();
        String reason = TaskRules.guardReason(foreground.nodes);
        if (!reason.isEmpty()) throw new Attention(reason);
        if (!TaskRules.TAOBAO.equals(foreground.pkg) || (findEntry && energyEntryReady(foreground))
                || (!findEntry && foreground.isTaobaoHome())) return foreground;
        return read(findEntry);
    }

    static boolean energyEntryReady(Snapshot screen) {
        if (!TaskRules.TAOBAO.equals(screen.pkg) || screen.has("做任务赚|累计任务奖励") || !screen.tasks().isEmpty()) return false;
        Node entry = screen.find("^(去?赚能量|去?赚体力|去?赚喵币|做任务)$");
        return entry != null && entry.top > 0 && entry.bottom < screen.height
                && entry.left >= 0 && entry.right <= screen.width
                && entry.right - entry.left < screen.width * .4 && entry.bottom - entry.top < screen.height * .15
                && screen.has("淘金币|升级赛车|涂装升级");
    }

    private Snapshot awaitDestination(Task task) {
        long deadline = control.now() + 18_000;
        long nextRead = control.now() + 900;
        int readyFrames = 0;
        while (control.now() < deadline) {
            control.sleep(Math.min(250, Math.max(1, nextRead - control.now())));
            Snapshot foreground = driver.readForeground();
            control.check();
            String reason = TaskRules.guardReason(foreground.nodes);
            if (!reason.isEmpty()) throw new Attention(reason);
            if (foreground.isTaobaoHome()) throw new Attention("已回到淘宝首页，重新进入任务列表", true);
            if (!foreground.pkg.isEmpty() && !TaskRules.TAOBAO.equals(foreground.pkg)) {
                if (!crossApp) throw new Attention("本轮未开启跨应用访问，请手动返回淘宝后继续");
                log.accept("已检测到应用跳转，按访问任务处理");
                return foreground;
            }
            if (control.now() < nextRead) continue;
            Snapshot screen = read();
            nextRead = control.now() + 900;
            if (screen.pkg.isEmpty()) { readyFrames = 0; continue; }
            if (screen.isTaobaoHome()) throw new Attention("已回到淘宝首页，重新进入任务列表", true);
            if (!screen.pkg.isEmpty() && !TaskRules.TAOBAO.equals(screen.pkg)) {
                if (!crossApp) throw new Attention("本轮未开启跨应用访问，请手动返回淘宝后继续");
                return screen;
            }
            if (screen.isList()) {
                log.accept("页面已在任务列表，直接继续下一项");
                return screen;
            }
            if (!screen.nodes.isEmpty()) {
                if (screen.has("打开.{0,10}应用|是否.{0,12}打开|即将.{0,10}跳转|允许.{0,12}打开")) {
                    // Leave the launch confirmation before retrying the task.
                    throw new Attention("遇到跨应用打开确认，返回列表重试", true);
                }
                if (destinationReady(screen)) {
                    readyFrames++;
                    boolean activeBrowse = task.kind == Kind.BROWSE && screen.has("浏览\\d+[秒sS]|再看|剩余");
                    boolean searchPage = task.kind == Kind.SEARCH && (screen.has("历史搜索|搜索发现") || resultsReady(screen));
                    if (readyFrames >= 2 || activeBrowse || searchPage || (task.kind == Kind.VISIT && task.seconds == 0
                            && task.title.contains("闪购") && screen.find("^(?:淘宝)?闪购$") != null)) return screen;
                } else readyFrames = 0;
            }
        }
        log.accept("任务跳转未确认，返回列表继续");
        throw new Attention("任务页面未能打开", true);
    }

    static boolean destinationReady(Snapshot screen) {
        if (screen.isList() || screen.isTaobaoHome() || screen.nodes.size() < 3) return false;
        long meaningful = screen.nodes.stream().filter(n -> n.y() > screen.height * .12 && n.y() < screen.height * .93
                && !TaskRules.matches(n.text, "^(加载中[.。…]*|正在加载[.。…]*|请稍候|返回|关闭)$")).count();
        return meaningful >= 2;
    }

    private Snapshot search(Snapshot screen) {
        if (resultsReady(screen)) return screen;
        String query = searchQueries.get(searchIndex++ % searchQueries.size());
        log.accept("搜索「" + query + "」");
        if (!driver.setSearchText(query)) throw new Attention("没有可确认的搜索输入框", true);
        control.sleep(700);
        long deadline = control.now() + 18_000;
        int submitted = 0;
        long nextSubmit = 0;
        while (control.now() < deadline) {
            screen = read();
            if (resultsReady(screen)) return screen;
            if (control.now() >= nextSubmit && submitted < 3) {
                Node button = searchButton(screen);
                if (button != null) { driver.tap(button); submitted++; }
                else if (driver.submitSearch()) submitted++;
                if (submitted > 0) {
                    if (nextSubmit == 0) log.accept("已提交搜索，等待商品列表");
                    nextSubmit = control.now() + 3000;
                }
            }
            control.sleep(800);
        }
        log.accept("未确认搜索结果页，返回任务列表继续");
        throw new Attention("未确认搜索结果页", true);
    }

    static Node searchButton(Snapshot screen) {
        Node top = null;
        for (Node n : screen.nodes) {
            if (!TaskRules.normalize(n.text).equals("搜索") || n.x() <= screen.width * .65
                    || n.right - n.left >= screen.width * .3 || n.bottom - n.top >= screen.height * .1) continue;
            if (n.y() > screen.height * .75 && n.bottom < screen.height * .97) return n;
            if (n.top >= 0 && n.y() < screen.height * .25) top = n;
        }
        return top;
    }

    static boolean resultsReady(Snapshot screen) {
        if (screen.has("历史搜索|搜索发现")) return false;
        int filters = 0;
        for (String label : new String[]{"^综合", "^销量", "^筛选", "^店铺", "^价格"}) if (screen.find(label) != null) filters++;
        long products = screen.nodes.stream().filter(n -> TaskRules.matches(n.text, "[¥￥]\\d|金币热卖价|金币已抵")).count();
        if (products < 2 && screen.has("猜你想搜|搜索推荐")) return false;
        return (filters >= 2 && screen.nodes.size() >= 8)
                || (products >= 2 && (filters >= 1 || screen.find("^搜索$") != null));
    }

    static List<String> newSearchQueries() {
        List<String> queries = new java.util.ArrayList<>(List.of(
                "手机壳", "数据线", "保温杯", "收纳盒", "台灯", "笔记本", "双肩包", "鼠标垫",
                "浴巾", "棉袜", "雨伞", "机械键盘", "陶瓷杯", "文具盒", "耳机", "拖鞋",
                "砧板", "厨房秤", "咖啡滤纸", "饭盒", "茶漏", "平底锅", "削皮刀", "电热水壶",
                "牙刷", "洗手液瓶", "纸巾盒", "木梳", "发梳", "剃须刷", "旅行枕", "行李牌",
                "手机贴膜", "电脑支架", "平板保护套", "充电宝", "手机支架", "充电头", "无线鼠标", "理线器",
                "摄像头盖", "读卡器", "蓝牙音箱", "散热风扇", "耳机收纳包", "屏幕挂灯", "网线", "相机背带",
                "瑜伽垫", "跳绳", "弹力带", "泳镜", "运动毛巾", "棒球帽", "雨衣", "骑行手套",
                "跑步腰包", "登山包", "园艺手套", "花盆", "浇水壶", "育苗盘", "台历", "挂钟",
                "书立", "直尺", "便利贴", "圆珠笔", "水彩笔", "彩色铅笔", "胶带座", "手工剪刀",
                "文件夹", "相册", "针线盒", "粘毛器", "洗衣袋", "鞋架", "真空收纳袋", "清洁海绵"));
        java.util.Collections.shuffle(queries);
        return queries;
    }

    private Snapshot browse(Task task, Snapshot screen) {
        int seconds = task.seconds > 0 ? task.seconds : task.kind == Kind.VISIT ? 18 : 15;
        long hardDeadline = control.now() + 90_000;
        long deadline = control.now() + Math.min(90, seconds + browseExtraSeconds) * 1000L;
        long nextCycleAt = control.now();
        int swipes = 0;
        int horizontalAt = java.util.concurrent.ThreadLocalRandom.current().nextInt(2, 4);
        while (control.now() < deadline && control.now() < hardDeadline && !screen.isList()) {
            if (screen.isTaobaoHome()) throw new Attention("浏览已离开任务页面，重新进入任务列表", true);
            if (!TaskRules.TAOBAO.equals(screen.pkg) && !crossApp) {
                throw new Attention("本轮未开启跨应用访问，请手动返回淘宝后继续");
            }
            if (TaskRules.completionHint(screen.nodes, screen.height)) {
                log.accept("检测到浏览完成提示，返回继续下一项");
                break;
            }
            java.util.regex.Matcher countdown = java.util.regex.Pattern.compile("(?:还需|再逛|再浏览|剩余|再看)[^\\d]{0,3}(\\d{1,2})秒")
                    .matcher(TaskRules.normalize(TaskRules.allText(screen.nodes)));
            if (countdown.find()) deadline = Math.max(deadline,
                    control.now() + (Integer.parseInt(countdown.group(1)) + browseExtraSeconds) * 1000L);
            control.sleep(Math.min(Math.max(0, nextCycleAt - control.now()),
                    Math.max(0, Math.min(deadline, hardDeadline) - control.now())));
            if (control.now() >= deadline || control.now() >= hardDeadline) break;
            nextCycleAt = control.now() + ScreenshotPolicy.BROWSE_INTERVAL_MS;
            log.accept("浏览「" + task.title + "」· 剩余约 " + Math.max(1, (deadline - control.now()) / 1000) + " 秒");
            if (deadline - control.now() > 1000 && TaskRules.TAOBAO.equals(screen.pkg)
                    && (task.kind == Kind.BROWSE || task.kind == Kind.SEARCH)) {
                boolean horizontal = swipes == horizontalAt;
                boolean reverse = horizontal ? java.util.concurrent.ThreadLocalRandom.current().nextBoolean()
                        : swipes > 0 && java.util.concurrent.ThreadLocalRandom.current().nextInt(7) == 0;
                driver.browseSwipe(reverse, horizontal); swipes++;
                if (horizontal) horizontalAt = swipes + java.util.concurrent.ThreadLocalRandom.current().nextInt(2, 5);
            }
            control.sleep(Math.min(java.util.concurrent.ThreadLocalRandom.current().nextLong(80, 181), Math.max(0, deadline - control.now())));
            long remaining = Math.min(deadline, hardDeadline) - control.now();
            // Do not start another OCR request in the last second or after the timer ends.
            if (remaining <= 1000) {
                if (remaining > 0) control.sleep(remaining);
                break;
            }
            long readingAt = control.now();
            control.check();
            screen = TaskRules.TAOBAO.equals(screen.pkg) ? driver.readBrowse() : readReturning(false);
            control.check();
            String guard = TaskRules.guardReason(screen.nodes);
            if (!guard.isEmpty()) throw new Attention(guard);
            deadline += Math.max(0, control.now() - readingAt - 1000);
        }
        if (control.now() >= hardDeadline && control.now() < deadline && !screen.isList()
                && !TaskRules.completionHint(screen.nodes, screen.height))
            throw new Attention("浏览计时尚未结束，返回列表后重试", true);
        return screen;
    }

}
