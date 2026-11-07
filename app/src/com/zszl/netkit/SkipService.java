package com.zszl.netkit;

import android.accessibilityservice.AccessibilityService;
import android.content.Context;
import android.util.Log;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileWriter;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 可选的「自动跳过开屏 / 插屏」无障碍服务。
 *
 * 思路参考李跳跳的 AllRules.json：规则就是「出现 A 就点 B」，
 * A/B 既可以是资源 id（tt_splash_skip_btn），也可以是文本（|跳过、=关闭）。
 *
 * 与 DNS 方案互补：DNS 让广告请求发不出去；本服务负责点掉那些已经渲染出来的整屏插屏。
 * 只在「启用过滤」且用户在设置里打开开关时才由系统拉起（服务本身不主动做任何事）。
 */
public class SkipService extends AccessibilityService {

    private static final String TAG = "NetKitSkip";

    /** 本次进程内累计跳过的次数，供设置页展示。 */
    public static volatile int firedCount;
    /** 单次事件最多遍历的节点数，防止大页面卡顿。 */
    private static final int MAX_NODES = 600;
    /** 两条规则之间的最小间隔（同一规则重复点击的冷却）。 */
    private static final long RULE_COOLDOWN_MS = 1500L;
    private static final long FAST_COOLDOWN_MS = 350L;   // 开屏窗口内的规则冷却

    private static class Rule {
        String id;
        String action;
        int times = 3;
        int fired;
        long lastAt;
    }

    private final List<Rule> rules = new ArrayList<Rule>();
    private final Map<String, Long> lastClickAt = new HashMap<String, Long>();
    private long lastScanAt;
    private boolean loaded;

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        Log.i(TAG, "accessibility service connected");
        loadRules();
        loadProfiles();
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event == null) return;
        if (!Prefs.skip(this)) return;

        CharSequence pkgCs = event.getPackageName();
        String pkg = pkgCs == null ? "" : pkgCs.toString();

        long now = android.os.SystemClock.elapsedRealtime();
        // 跳转检测必须在目标包过滤之前：跳到的正是别的 App
        boolean enteredTarget = false;
        if (!pkg.equals(lastAnyPkg) && pkg.length() > 0) {
            lastAnyPkg = pkg;       // 记录任意前台包（含桌面/其他 App）
            enteredTarget = true;   // 包名变化即视为一次新的前台切换
        }
        if (jumpAt != 0 && now - jumpAt < JUMP_WINDOW_MS && jumpFromPkg != null
                && !jumpFromPkg.equals(pkg)
                && event.getEventType() == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            onJump(pkg, now);
        }

        Set<String> targets = Prefs.packages(this);
        if (!targets.isEmpty() && !targets.contains(pkg)) return;


        if (enteredTarget) {
            launchAt = now;                        // 开屏窗口计时起点
            // 每次重新进入目标应用都重新发放每条规则的触发配额：
            // 否则常驻服务里的 times 上限会让开屏规则在第 N 次之后永久失效。
            boolean any = false;
            for (Rule r : rules) {
                if (r.fired != 0) { r.fired = 0; any = true; }
            }
            Log.i(TAG, "launch detected: " + pkg + (any ? ", rule budget reset" : ""));
        }
        curPkg = pkg;
        // 开屏期只有 3~5 秒，250ms 的通用限流会让「跳过」明显慢半拍；
        // 因此在启动窗口内把扫描间隔压到 90ms，其余时间仍用 250ms 防事件风暴。
        boolean fastWindow = launchAt != 0
                && now - launchAt < SPLASH_WINDOW_MS;
        if (now - lastScanAt < (fastWindow ? 90L : 250L)) return;
        lastScanAt = now;

        if (!loaded) loadRules();
        if (rules.isEmpty()) return;

        // 不再周期性整体 reload：复核页的改动由 persist() 的「读盘合并」吸收
        // （整体替换在文件正在写入时会读到空/半截内容，把整个档案清空）。

        AccessibilityNodeInfo root = getRootInActiveWindow();
        List<AccessibilityNodeInfo> nodes = new ArrayList<AccessibilityNodeInfo>();
        if (root != null) collect(root, nodes, 0);
        // 广告弹窗常常是独立窗口（Dialog/PopupWindow），必须把所有窗口的根都收进来
        try {
            List<android.view.accessibility.AccessibilityWindowInfo> wins = getWindows();
            if (wins != null) {
                for (android.view.accessibility.AccessibilityWindowInfo w : wins) {
                    if (nodes.size() >= MAX_NODES) break;
                    AccessibilityNodeInfo r = w.getRoot();
                    if (r == null) continue;
                    CharSequence p = r.getPackageName();
                    if (p != null && !targets.isEmpty() && !targets.contains(p.toString())) continue;
                    collect(r, nodes, 0);
                }
            }
        } catch (Throwable ignored) {
        }
        if (nodes.isEmpty()) return;
        try {
            // 开屏广告每次冷启动都会重来，但服务进程是常驻的：
            // 若按「进程内累计 3 次」封顶，第 4 次冷启动起规则就全部失效。
            // 因此闲置一段时间后重置每条的 fired 配额。
            if (lastFireAt != 0 && now - lastFireAt > RULE_RESET_IDLE_MS) {
                for (Rule r : rules) r.fired = 0;
                Log.i(TAG, "rule budget reset (idle " + (now - lastFireAt) + "ms)");
                lastFireAt = 0;
            }
            verifyPending(nodes, now);
            commitLearn(nodes, now);
            processRetry(nodes, now);
            dumpWindows();
            maybeDumpAdTree(nodes, pkg, now);
            for (Rule r : rules) {
                if (r.fired >= r.times) continue;
                long cooldown = (launchAt != 0 && now - launchAt < SPLASH_WINDOW_MS)
                        ? FAST_COOLDOWN_MS : RULE_COOLDOWN_MS;
                if (now - r.lastAt < cooldown) continue;
                AccessibilityNodeInfo trigger = find(nodes, r.id);
                if (trigger == null) continue;
                // 返回键类规则只在「大面积节点」上生效，避免把信息流里的小广告条当成插屏按返回
                boolean needsBig = "GLOBAL_ACTION_BACK".equals(r.action)
                        || "AUTO_CLOSE".equals(r.action)
                        || "TAP_BR".equals(r.action) || "TAP_TR".equals(r.action);
                if (needsBig && !isBig(trigger)) continue;                if (act(nodes, r.action, trigger)) {
                    r.fired++;
                    r.lastAt = now;
                    firedCount++;
                    lastFireAt = now;
                    Log.i(TAG, "skip fired: " + r.id + " -> " + r.action + " (pkg=" + pkg + ", n=" + r.fired + ")");
                    armLearn(nodes, now);
                }
            }
            // 兜底盲点已关闭：实测会点到广告内容（触发「已禁止运动世界启动京东」弹窗），
            // 现在改为完全依赖 id / contentDescription 规则。
            // heuristicClose(nodes, now);
            applyProfile(nodes, now);
        } catch (Throwable t) {
            Log.w(TAG, "scan failed: " + t);
        }
    }

    // ---------------------------------------------------------------- 坐标剖面：只从「已验证的成功」学习
    //
    // 针对「手动点错 → 学到错误坐标 → 下次自动跳转」的风险，做法是：
    //  1) 完全不学习人工点击；只学习「我们自己点完、且广告确实消失」的成功样本；
    //  2) 同一签名必须累计 hits>=2 才允许被自动套用（单次样本永不生效）；
    //  3) 每次套用都复核：1.2s 后广告特征还在 → misses++，misses>=2 立即作废；
    //  4) 危险区排除：命中点落在 CTA 文案（立即下载/查看详情/…）矩形内 → 丢弃样本；
    //  5) 状态栏 / 导航栏区域（y<6% 或 y>94%）一律不学不点。
    // 只保留「能区分版式」的特征；"广告" / "跳过" 这类过于通用的词会把不同广告混成同一签名
    private static final String[] MARKERS = {
            "上滑或点击", "下载或跳转第三方应用", "应用名称：", "关闭广告"
    };
    private static final String[] CTA = {
            "立即下载", "查看详情", "立即打开", "了解更多", "点击查看", "去逛逛", "立即进入"
    };
    private static final long RULE_RESET_IDLE_MS = 20000L;
    private long lastFireAt;
    private static final long PROFILE_VERIFY_MS = 1200L;
    private static final int PROFILE_MIN_HITS = 2;

    private Map<String, Profiles.Item> profiles = new LinkedHashMap<String, Profiles.Item>();
    /** profiles.json 的版本戳：复核页（另一个组件）改过文件后，服务必须重新加载，
     *  否则服务里的旧副本会在下次学习时把 state 覆写回 pending。 */
    private long profilesMtime = -1;
    private String pendingSig;
    private long lastApplyLog;
    private long lastProfilesCheck;
    private long pendingAt;
    private int lastTapX = -1, lastTapY = -1;
    private boolean lastTapReliable = false;   // 落点是否来自「小元素」，大容器的中心不作学习样本

    /** 跳转检测：我们点完之后 3 秒内前台包名若变成别的 App，说明这一下点到了广告内容。 */
    private static final long JUMP_WINDOW_MS = 3000L;
    private String jumpSig, jumpFromPkg;
    private long jumpAt;
    private String curPkg = "";
    private String lastAnyPkg = "";
    private String lastSeenPkg = "";
    /** 目标应用最近的启动时刻：开屏广告只在启动后几秒内出现。 */
    private long launchAt;
    private static final long SPLASH_WINDOW_MS = 8000L;
    private static final String SPLASH_SIG = "SPLASH@";

    /**
     * 人工确认过的「稳定热区」：小米应用商店短剧开屏的「跳过 N」按钮。
     * 实测取自用户标注截图：绿框中心 (1064, 419) / 屏幕 (1200, 2670) = (0.887, 0.157)。
     * 这类开屏的特征文案组合是「上滑或点击」与「上滑或点击+下载或跳转第三方应用」两种，
     * 因此两条签名都预置同一个落点，冷启动首次即可命中，不必等学习累积。
     */
    private static final float SEED_X = 0.887f;
    private static final float SEED_Y = 0.157f;
    private static final String[] SEED_SIGS = {
            "上滑或点击+下载或跳转第三方应用", "上滑或点击"
    };

    /**
     * 竖条带扫描：同一个开屏的「跳过」按钮纵向位置会随「顶部有无通知横幅 / 素材纵横比」漂移
     * （实测同一素材出现过 y=0.157 与 y=0.206 两种）。因此不再只点单点，而是围绕锚点按
     * 上下偏移依次试；一下没关掉就自动换下一个候选点。
     */
    private static final float[] BAND_DY = {0f, -0.030f, 0.026f, 0.052f, 0.075f, -0.058f};
    private static final long RETRY_MS = 400L;   // 开屏只有几秒，重试不能等太久
    private String retrySig;
    private float retryX;
    private float[] retryYs;
    private int retryIdx;
    private long retryAt;
    private int retryTries;

    private String signatureOf(List<AccessibilityNodeInfo> nodes) {
        StringBuilder sb = new StringBuilder();
        for (String m : MARKERS) {
            for (AccessibilityNodeInfo n : nodes) {
                String t = str(n.getText()) + "|" + str(n.getContentDescription());
                if (t.contains(m)) {
                    if (sb.length() > 0) sb.append('+');
                    sb.append(m);
                    break;
                }
            }
        }
        return sb.toString();
    }

    private boolean inDangerZone(List<AccessibilityNodeInfo> nodes, int x, int y) {
        android.graphics.Rect r = new android.graphics.Rect();
        for (AccessibilityNodeInfo n : nodes) {
            String t = str(n.getText());
            if (t.length() == 0) continue;
            boolean cta = false;
            for (String c : CTA) if (t.contains(c)) { cta = true; break; }
            if (!cta) continue;
            n.getBoundsInScreen(r);
            r.inset(-r.width() / 20 - 8, -r.height() / 20 - 8);   // 外扩，容忍落点偏差
            if (r.contains(x, y)) return true;
        }
        return false;
    }

    /** 只有「小元素」（面积 <6% 屏）的中心才当成可信落点；大容器的中心不是 × 的位置。 */
    private boolean isSmallTarget(android.graphics.Rect rc) {
        android.util.DisplayMetrics dm = getResources().getDisplayMetrics();
        long screen = (long) dm.widthPixels * dm.heightPixels;
        return (long) rc.width() * rc.height() * 100L < screen * 6L;
    }

    /** 「上滑或点击」与「上滑或点击+下载或跳转第三方应用」是同一版式，只是扫描时标记多少不同。 */
    private boolean compatibleSig(String a, String b) {
        if (a == null || b == null || a.length() == 0 || b.length() == 0) return false;
        if (a.equals(b)) return true;
        java.util.Set<String> sa = new java.util.HashSet<String>(java.util.Arrays.asList(a.split("\\+")));
        java.util.Set<String> sb = new java.util.HashSet<String>(java.util.Arrays.asList(b.split("\\+")));
        if (sa.containsAll(sb) || sb.containsAll(sa)) return true;   // 互为子集
        int inter = 0;
        for (String x : sa) if (sb.contains(x)) inter++;
        int max = Math.max(sa.size(), sb.size());
        return max > 0 && inter * 10 >= max * 8;                     // 80% 重叠也算同版式
    }

    /** 找出与当前签名+落点兼容的已存在样本（用于「继承状态」而不是新建 pending）。 */
    private Profiles.Item findCompatible(String sig, float fx, float fy) {
        for (Profiles.Item it : profiles.values()) {
            if (Math.abs(it.x - fx) > 0.05f || Math.abs(it.y - fy) > 0.05f) continue;
            if (compatibleSig(sig, it.sig)) return it;
        }
        return null;
    }

    private static final long LEARN_CONFIRM_MS = 700L;
    private long learnAt;
    private String learnSig;
    private int learnX = -1, learnY = -1;
    private boolean learnReliable, learnSafe;

    /** 规则刚点完：先记下「点哪儿、当时是什么版式」，等 700ms 后确认广告真消失了再学。 */
    private void armLearn(List<AccessibilityNodeInfo> nodes, long now) {
        if (lastTapX < 0 || lastTapY < 0) return;
        String sig = signatureOf(nodes);
        boolean splash = launchAt != 0 && now - launchAt < SPLASH_WINDOW_MS;
        if (sig.length() == 0) {
            if (!splash) return;
            sig = SPLASH_SIG;
        }
        learnAt = now + LEARN_CONFIRM_MS;
        learnSig = sig;
        learnX = lastTapX;
        learnY = lastTapY;
        learnReliable = lastTapReliable;
        learnSafe = !inDangerZone(nodes, lastTapX, lastTapY);   // 危险区判定必须在广告还在时做
        Log.d(TAG, "learn armed sig=" + sig + " at " + learnX + "," + learnY
                + " reliable=" + learnReliable + " safe=" + learnSafe);
    }

    /** 700ms 后：广告版式已经不在了，才承认「这一下点对了」，把落点学下来。 */
    private void commitLearn(List<AccessibilityNodeInfo> nodes, long now) {
        if (learnAt == 0 || now < learnAt) return;
        String sig = learnSig;
        learnAt = 0;
        learnSig = null;
        if (sig == null || sig.length() == 0) return;
        String cur = signatureOf(nodes);
        boolean gone = cur.length() == 0 || !compatibleSig(cur, sig);
        if (!gone) {
            Log.d(TAG, "learn skipped (ad still on screen) sig=" + sig);
            return;
        }
        learnSuccess(sig, learnX, learnY, learnReliable, learnSafe);
    }

    private void learnSuccess(String sig, int tx, int ty, boolean reliable, boolean safe) {
        if (tx < 0 || ty < 0) return;
        if (!reliable) return;   // 大容器中心 → 无可信坐标，不学
        if (!safe) {
            Log.i(TAG, "profile learn skipped (danger zone) sig=" + sig);
            return;
        }
        android.util.DisplayMetrics dm = getResources().getDisplayMetrics();
        if (sig == null) sig = "";
        if (sig.length() == 0) return;   // 没有可区分特征 → 不学
        float fx = (float) tx / dm.widthPixels;
        float fy = (float) ty / dm.heightPixels;
        if (fy < 0.06f || fy > 0.94f) return;
        Profiles.Item it = profiles.get(sig);
        boolean inherited = false;
        if (it == null) {
            // 同一版式的签名会随「当前树里有几个标记」变化（1 个 / 2 个）：
            // 只要落点接近且签名兼容，就并入已有样本，继承它的人工复核状态，
            // 否则每出现一次就会多出一条需要重新审批的 pending 条目。
            it = findCompatible(sig, fx, fy);
            if (it != null) {
                inherited = true;
                Log.i(TAG, "profile learn merged: " + sig + " -> " + it.sig
                        + " state=" + it.state);
            }
        }
        if (it == null) {
            it = new Profiles.Item(sig);
            it.x = fx;
            it.y = fy;
            it.hits = 1;
            it.state = "pending";   // 是否生效由 profile_states.json（人工复核）决定
            profiles.put(sig, it);
        } else if (Math.abs(it.x - fx) < 0.05f && Math.abs(it.y - fy) < 0.05f) {
            float n = it.hits + 1f;
            it.x = (it.x * it.hits + fx) / n;
            it.y = (it.y * it.hits + fy) / n;
            it.hits++;
        } else {
            return;   // 同一签名不同位置 → 不合并，等更多样本
        }
        persist();
        Log.i(TAG, "profile learn sig=" + sig + " at " + it.x + "," + it.y
                + " hits=" + it.hits + " state=" + it.state);
    }

    /** 人工复核状态（profile_states.json）优先于学习到的默认状态。 */
    private Map<String, String> statesCache;
    private long statesAt;

    private String effState(String sig, String fallback) {
        long now = android.os.SystemClock.elapsedRealtime();
        if (statesCache == null || now - statesAt > 5000L) {
            statesCache = Profiles.loadStates(this);
            statesAt = now;
        }
        String v = statesCache.get(sig);
        return v != null ? v : fallback;
    }

    /** 以锚点为中心生成竖向候选点（去重、夹在安全区内）。 */
    private float[] bandYs(float anchorY) {
        java.util.LinkedHashSet<Float> set = new java.util.LinkedHashSet<Float>();
        for (float dy : BAND_DY) {
            float y = anchorY + dy;
            if (y < 0.12f || y > 0.26f) continue;
            set.add(Math.round(y * 1000f) / 1000f);
        }
        float[] out = new float[set.size()];
        int i = 0;
        for (Float f : set) out[i++] = f;
        return out;
    }

    private void armSkipChain(String sig, float x, float y, long now) {
        // 条带只覆盖开屏「跳过」的高度范围（0.12~0.26）。锚点不在带内的条目（如插屏中下部的 ×）
        // 不做竖向重试：候选数组会为空或全是不相干的点（空数组曾导致 processRetry 越界、服务卡死）。
        float[] ys = bandYs(y);
        if (y < 0.12f || y > 0.26f || ys.length < 2) {
            retrySig = null;
            return;
        }
        retrySig = sig;
        retryX = x;
        retryYs = ys;
        retryIdx = 0;          // 第 0 个就是锚点本身（applyProfile 已经点过一次，这里从下一个开始）
        retryTries = 0;
        retryAt = now + RETRY_MS;
        Log.d(TAG, "skip chain armed sig=" + sig + " x=" + x + " ys=" + retryYs.length);
    }

    /**
     * 一下没关掉 → 换下一个候选高度再试；关掉了 → 只记日志，不学习。
     * 不学习的原因：广告消失也可能是开屏倒计时自己结束，或是更早一下的延迟生效，
     * 无法确定是哪个候选点起的作用，学进去会污染档案。
     */
    private void processRetry(List<AccessibilityNodeInfo> nodes, long now) {
        if (retrySig == null || now < retryAt) return;
        String sig = retrySig;
        float[] ys = retryYs;
        if (ys == null || ys.length == 0) {   // 防御：任何异常状态都先清掉链，绝不能每次扫描都抛
            retrySig = null;
            return;
        }
        String cur = signatureOf(nodes);
        boolean gone = cur.length() == 0 || !compatibleSig(cur, sig);
        if (gone) {
            int idx = Math.min(retryIdx, ys.length - 1);
            Log.i(TAG, "skip chain done sig=" + sig + " after " + retryTries
                    + " retries, last tap y=" + ys[idx] + " (not learned)");
            retrySig = null;
            return;
        }
        android.util.DisplayMetrics dm = getResources().getDisplayMetrics();
        int x = (int) (dm.widthPixels * retryX);
        // 找下一个不落在 CTA 危险区里的候选点：「上滑或点击」版式点到素材本身就会跳转
        int next = retryIdx + 1;
        int y = -1;
        for (; next < ys.length; next++) {
            int cy = (int) (dm.heightPixels * ys[next]);
            if (inDangerZone(nodes, x, cy)) {
                Log.i(TAG, "skip chain candidate in danger zone, skip y=" + ys[next]);
                continue;
            }
            y = cy;
            break;
        }
        if (y < 0) {
            Log.i(TAG, "skip chain exhausted sig=" + sig + " tries=" + retryTries);
            retrySig = null;
            return;
        }
        retryIdx = next;
        retryTries++;
        retryAt = now + RETRY_MS;
        Log.i(TAG, "skip chain retry " + retryIdx + "/" + (ys.length - 1)
                + " -> " + x + "," + y + " sig=" + sig);
        if (tap(x, y)) {
            // 每一下都重新开跳转检测窗口：任何一个候选点点出跳转，都要归到这个条目上
            jumpSig = sig;
            jumpFromPkg = curPkg;
            jumpAt = now;
        }
    }

    private void applyProfile(List<AccessibilityNodeInfo> nodes, long now) {
        if (pendingSig != null) return;
        String sig = signatureOf(nodes);
        if (sig.length() == 0) {
            if (now - lastApplyLog > 3000L) { lastApplyLog = now; Log.d(TAG, "profile skip: no marker"); }
            return;
        }
        Profiles.Item p = profiles.get(sig);
        if (p == null) {
            float fx = 0f, fy = 0f;
            Profiles.Item any = null;
            for (Profiles.Item c : profiles.values()) {
                if (compatibleSig(sig, c.sig) && c.active()) { any = c; break; }
            }
            if (any != null) {
                p = any;
                Log.i(TAG, "profile compatible hit: " + sig + " -> " + any.sig);
            }
        }
        if (p == null && launchAt != 0
                && now - launchAt < SPLASH_WINDOW_MS) {
            Profiles.Item sp = profiles.get(SPLASH_SIG);
            if (sp != null && "auto".equals(effState(sp.sig, sp.state)) && sp.hits >= 2) {
                sig = SPLASH_SIG;
                p = sp;
                Log.i(TAG, "profile fallback to " + SPLASH_SIG);
            }
        }
        if (p == null) {
            if (now - lastApplyLog > 3000L) { lastApplyLog = now; Log.d(TAG, "profile skip: none for " + sig); }
            return;
        }
        if (!("auto".equals(effState(p.sig, p.state)) && p.hits >= 2)) {
            if (now - lastApplyLog > 3000L) { lastApplyLog = now; Log.d(TAG, "profile skip: state=" + p.state + " hits=" + p.hits + " sig=" + sig); }
            return;
        }
        android.util.DisplayMetrics dm = getResources().getDisplayMetrics();
        int x = (int) (dm.widthPixels * p.x);
        int y = (int) (dm.heightPixels * p.y);
        if (inDangerZone(nodes, x, y)) return;
        if (tap(x, y)) {
            pendingSig = p.sig;      // 记条目自己的 key（兼容命中时 sig != p.sig）
            pendingAt = now;
            jumpSig = p.sig;
            jumpFromPkg = curPkg;
            jumpAt = now;
            Log.i(TAG, "profile apply sig=" + sig + " -> " + x + "," + y + " hits=" + p.hits);
            armSkipChain(p.sig, p.x, p.y, now);   // 没关掉就自动换高度重试
        }
    }

    private void verifyPending(List<AccessibilityNodeInfo> nodes, long now) {
        if (pendingSig == null || now - pendingAt < PROFILE_VERIFY_MS) return;
        String sig = pendingSig;
        pendingSig = null;
        if (SPLASH_SIG.equals(sig)) {
            Log.d(TAG, "profile verify skipped for " + SPLASH_SIG);
            return;
        }
        Profiles.Item p = profiles.get(sig);
        if (p == null) return;
        if (signatureOf(nodes).equals(sig)) {
            p.misses++;
            Log.i(TAG, "profile miss sig=" + sig + " misses=" + p.misses);
            if (p.misses >= 2) {
                profiles.remove(sig);
                Log.i(TAG, "profile demoted sig=" + sig);
            }
        } else {
            p.hits++;
            Log.i(TAG, "profile confirmed sig=" + sig + " hits=" + p.hits);
        }
        persist();
    }

    /** 点完之后前台包名变了 → 这一下点到了广告内容（跳转），学习样本作废。 */
    private void onJump(String toPkg, long now) {
        Log.i(TAG, "jump detected: " + jumpFromPkg + " -> " + toPkg
                + " d=" + (now - jumpAt) + "ms sig=" + jumpSig);
        if (jumpSig != null) {
            Profiles.Item p = profiles.get(jumpSig);
            if (p != null) {
                p.misses += 2;
                p.state = "off";
            }
            Profiles.setState(this, jumpSig, "off");   // 侧车优先：不写这里，人工启用会覆盖 off
            persist();
            Log.i(TAG, "profile disabled by jump sig=" + jumpSig);
        }
        jumpSig = null;
        jumpFromPkg = null;
        jumpAt = 0;
    }

    /** 把人工确认过的热区写进档案（只在该签名还不存在时写，绝不覆盖用户/学习结果）。 */
    private void seedDefaults() {
        boolean changed = false;
        for (String sig : SEED_SIGS) {
            if (profiles.containsKey(sig)) continue;
            Profiles.Item it = new Profiles.Item(sig);
            it.x = SEED_X;
            it.y = SEED_Y;
            it.hits = 3;
            it.misses = 0;
            it.state = "auto";
            profiles.put(sig, it);
            Profiles.setState(this, sig, "auto");   // 侧车也标为已启用，复核页显示「生效」
            changed = true;
            Log.i(TAG, "seed profile added sig=" + sig + " at " + SEED_X + "," + SEED_Y);
        }
        if (changed) persist();
    }

    private void loadProfiles() {
        java.io.File pf = new java.io.File(getFilesDir(), "profiles.json");
        profiles = Profiles.load(this);
        seedDefaults();
        profilesMtime = pf.lastModified();
        Log.i(TAG, "profiles loaded: " + profiles.size()
                + " fileLen=" + (pf.exists() ? pf.length() : -1L));
    }

    /**
     * 保存样本。关键点：先读盘做一次合并 —— 复核页（另一个组件）刚改过的 state 必须优先，
     * 否则服务内存里的旧副本会把「已启用」覆写回 pending（实测踩过这个坑）。
     */
    private void persist() {
        try {
            Map<String, Profiles.Item> disk = Profiles.load(this);
            if (profiles.isEmpty() && !disk.isEmpty()) {
                profiles.putAll(disk);
                Log.w(TAG, "profiles memory was empty, adopted " + disk.size() + " from disk");
            }
            for (Map.Entry<String, Profiles.Item> e : profiles.entrySet()) {
                Profiles.Item d = disk.get(e.getKey());
                if (d == null) continue;
                if (d.hits > e.getValue().hits) e.getValue().hits = d.hits;
                if (d.misses > e.getValue().misses) e.getValue().misses = d.misses;
            }
        } catch (Throwable ignored) {
        }
        Profiles.save(this, profiles);
        profilesMtime = new java.io.File(getFilesDir(), "profiles.json").lastModified();
    }


    // ---------------------------------------------------------------- 启发式兜底

    private long lastHeurAt;
    private int heurStep;
    private static final int HEUR_MAX_STEPS = 8;

    /**
     * 兜底：树里既没有 id、也没有「关闭 / 广告」字样的自渲染插屏。
     * 判定（覆盖竖版全屏卡与横版半屏卡两类）：
     *   1) 宽度 ≥55% 屏宽、面积 22%~92% 屏；
     *   2) 水平居中（卡片中心与屏中心差 ≤8% 屏宽）；
     *   3) 卡片不等于应用主窗口、且落在应用窗口内；
     *   4) 最多试 6 个候选位置，每次间隔 ≥1.5 秒。
     * 候选位置按「竖版全屏卡 × 在左中」「横版卡 × 在左上/右下」经验排序。
     */
    private void heuristicClose(List<AccessibilityNodeInfo> nodes, long now) {
        if (heurStep >= HEUR_MAX_STEPS) return;
        if (now - lastHeurAt < 1500L) return;
        android.util.DisplayMetrics dm = getResources().getDisplayMetrics();
        long screen = (long) dm.widthPixels * dm.heightPixels;
        android.graphics.Rect app = appWindowRect();
        android.graphics.Rect best = null;
        long bestArea = 0;
        for (AccessibilityNodeInfo n : nodes) {
            if (n == null) continue;
            android.graphics.Rect rc = new android.graphics.Rect();
            n.getBoundsInScreen(rc);
            if (rc.width() < dm.widthPixels * 55 / 100) continue;
            long area = (long) rc.width() * rc.height();
            if (area * 100L < screen * 22L || area * 100L > screen * 92L) continue;
            if (app != null && sameRect(app, rc)) continue;
            if (app != null && !app.contains(rc)) continue;
            // 排除「整页容器」：插屏卡片一定比页面窄、且不贴着页面顶边
            if (app != null && rc.width() * 100 > app.width() * 92) continue;
            if (app != null && rc.top < app.top + app.height() * 3 / 100) continue;
            int cardCx = (rc.left + rc.right) / 2;
            if (Math.abs(cardCx - dm.widthPixels / 2) > dm.widthPixels * 8 / 100) continue;  // 必须水平居中
            if (area > bestArea) {
                bestArea = area;
                best = rc;
            }
        }
        if (best == null) {
            if (now - lastHeurAt > 10000L) heurStep = 0;   // 广告消失一段时间后重新武装
            return;
        }
        int w = best.width(), h = best.height();
        int[][] cand = {
                // 竖版全屏卡：× 常见在左侧边缘、垂直 50%~80% 处
                {best.left + w * 15 / 100, best.top + h * 52 / 100},
                {best.left + w * 15 / 100, best.top + h * 67 / 100},
                {best.right - w * 15 / 100, best.top + h * 52 / 100},
                {best.left + w * 15 / 100, best.top + h * 80 / 100},
                {best.right - w * 15 / 100, best.top + h * 67 / 100},
                // 横版卡：× 在左上 / 右上 / 右下
                {best.left + w * 6 / 100, best.top + h * 5 / 100},
                {best.right - w * 6 / 100, best.top + h * 5 / 100},
                {best.right - w * 8 / 100, best.bottom - h * 6 / 100},
        };
        int[] pt = cand[heurStep % cand.length];
        lastHeurAt = now;
        heurStep++;
        Log.i(TAG, "heuristic close step=" + heurStep + " card=" + best + " tap=" + pt[0] + "," + pt[1]);
        tap(pt[0], pt[1]);
    }

    private android.graphics.Rect appWindowRect() {
        try {
            List<android.view.accessibility.AccessibilityWindowInfo> wins = getWindows();
            if (wins == null) return null;
            for (android.view.accessibility.AccessibilityWindowInfo w : wins) {
                if (w.getType() != android.view.accessibility.AccessibilityWindowInfo.TYPE_APPLICATION) continue;
                if (!w.isActive() && !w.isFocused()) continue;
                android.graphics.Rect r = new android.graphics.Rect();
                w.getBoundsInScreen(r);
                return r;
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    private boolean sameRect(android.graphics.Rect a, android.graphics.Rect b) {
        return Math.abs(a.left - b.left) <= 4 && Math.abs(a.top - b.top) <= 4
                && Math.abs(a.right - b.right) <= 4 && Math.abs(a.bottom - b.bottom) <= 4;
    }

    @Override
    public void onInterrupt() {
    }

    private long lastWinDumpAt;

    /** 探索用：把当前所有窗口的类型/层次/区域打出来，用于定位弹窗坐标系。 */
    private void dumpWindows() {
        long now = android.os.SystemClock.elapsedRealtime();
        if (now - lastWinDumpAt < 5000L) return;
        lastWinDumpAt = now;
        try {
            List<android.view.accessibility.AccessibilityWindowInfo> wins = getWindows();
            if (wins == null) return;
            for (android.view.accessibility.AccessibilityWindowInfo w : wins) {
                android.graphics.Rect rc = new android.graphics.Rect();
                w.getBoundsInScreen(rc);
                AccessibilityNodeInfo r = w.getRoot();
                String p = r == null || r.getPackageName() == null ? "-" : r.getPackageName().toString();
                Log.i(TAG, "WIN type=" + w.getType() + " layer=" + w.getLayer()
                        + " focused=" + w.isFocused() + " active=" + w.isActive()
                        + " pkg=" + p + " b=" + rc);
            }
        } catch (Throwable t) {
            Log.w(TAG, "dump windows failed: " + t);
        }
    }

    private long lastDumpAt;

    /**
     * 探索用：当窗口里出现「广告」字样时，把可点击节点与带 id 的节点打到 logcat，
     * 方便为新广告补规则（限流 5 秒一次，最多 40 行）。上线可整体删掉。
     */
    private void maybeDumpAdTree(List<AccessibilityNodeInfo> nodes, String pkg, long now) {
        if (now - lastDumpAt < 5000L) return;
        lastDumpAt = now;
        int shown = 0;
        for (AccessibilityNodeInfo n : nodes) {
            if (shown >= 200) break;
            String rid = n.getViewIdResourceName();
            String t = str(n.getText());
            String d = str(n.getContentDescription());
            boolean clickable = n.isClickable();
            if (rid == null && t.length() == 0 && d.length() == 0 && !clickable) continue;
            android.graphics.Rect rc = new android.graphics.Rect();
            n.getBoundsInScreen(rc);
            Log.i(TAG, "DUMP " + pkg + " id=" + (rid == null ? "-" : rid)
                    + " click=" + n.isClickable()
                    + " cls=" + n.getClassName()
                    + " b=" + rc
                    + " text=" + t + " desc=" + d);
            shown++;
        }
    }

    /** 节点或它的祖先是否占屏幕 ≥35%（判断「整屏插屏」用）。 */
    private boolean isBig(AccessibilityNodeInfo node) {
        AccessibilityNodeInfo cur = node;
        for (int i = 0; i < 6 && cur != null; i++) {
            if (isBigSelf(cur)) return true;
            cur = cur.getParent();
        }
        return false;
    }

    private boolean isBigSelf(AccessibilityNodeInfo node) {
        android.graphics.Rect rc = new android.graphics.Rect();
        node.getBoundsInScreen(rc);
        android.util.DisplayMetrics dm = getResources().getDisplayMetrics();
        long screen = (long) dm.widthPixels * dm.heightPixels;
        long area = (long) rc.width() * rc.height();
        return screen > 0 && area * 100L >= screen * 35L;
    }

    private void collect(AccessibilityNodeInfo node, List<AccessibilityNodeInfo> out, int depth) {
        if (node == null || out.size() >= MAX_NODES || depth > 40) return;
        out.add(node);
        int n = node.getChildCount();
        for (int i = 0; i < n && out.size() < MAX_NODES; i++) {
            AccessibilityNodeInfo c = node.getChild(i);
            if (c != null) collect(c, out, depth + 1);
        }
    }

    /** 匹配语义与李跳跳一致：| 包含、= 相等、+ 前缀，其余按资源 id 后缀匹配。 */
    private boolean matches(AccessibilityNodeInfo node, String spec) {
        if (node == null || spec == null || spec.length() == 0) return false;
        String text = str(node.getText());
        String desc = str(node.getContentDescription());
        char c0 = spec.charAt(0);
        if (c0 == '|') {
            String s = spec.substring(1).trim();
            return s.length() > 0 && (text.contains(s) || desc.contains(s));
        }
        if (c0 == '=') {
            String s = spec.substring(1).trim();
            return s.length() > 0 && (text.equals(s) || desc.equals(s));
        }
        if (c0 == '+') {
            String s = spec.substring(1).trim();
            return s.length() > 0 && (text.startsWith(s) || desc.startsWith(s));
        }
        String rid = node.getViewIdResourceName();
        if (rid == null) {
            // 没有资源 id 时，退化到 contentDescription 精确匹配（自渲染广告常用这个）
            return desc.equals(spec);
        }
        return rid.equals(spec) || rid.endsWith(":id/" + spec) || rid.endsWith("/" + spec) || desc.equals(spec);
    }

    private String str(CharSequence cs) {
        return cs == null ? "" : cs.toString().trim();
    }

    private AccessibilityNodeInfo find(List<AccessibilityNodeInfo> nodes, String spec) {
        for (AccessibilityNodeInfo n : nodes) {
            if (matches(n, spec)) return n;
        }
        return null;
    }

    private boolean act(List<AccessibilityNodeInfo> nodes, String action, AccessibilityNodeInfo trigger) {
        if (action == null) return false;
        if ("GLOBAL_ACTION_BACK".equals(action)) return performGlobalAction(GLOBAL_ACTION_BACK);
        if ("GLOBAL_ACTION_HOME".equals(action)) return performGlobalAction(GLOBAL_ACTION_HOME);
        if (action.startsWith("TAP_AT:")) return tapAt(action.substring(7), trigger);
        if ("TAP_BR".equals(action) || "TAP_TR".equals(action)) return tapCorner(trigger, action);
        if ("AUTO_CLOSE".equals(action)) return autoClose(trigger, nodes);
        if ("TAP_INFO_CLOSE".equals(action)) return tapInfoClose(trigger);
        AccessibilityNodeInfo target = find(nodes, action);
        if (target == null) return false;
        AccessibilityNodeInfo clickable = target;
        for (int i = 0; i < 6 && clickable != null && !clickable.isClickable(); i++) {
            clickable = clickable.getParent();
        }
        if (clickable != null && clickable.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
            android.graphics.Rect c = new android.graphics.Rect();
            clickable.getBoundsInScreen(c);
            lastTapX = c.centerX();
            lastTapY = c.centerY();
            lastTapReliable = isSmallTarget(c);
            return true;
        }
        // 「跳过 5」这类倒计时按钮常是 clickable=false 的 TextView，ACTION_CLICK 无效 →
        // 退化为在节点（或最近可点击祖先）中心点做手势点击。
        AccessibilityNodeInfo rectNode = (clickable != null) ? clickable : target;
        android.graphics.Rect rc = new android.graphics.Rect();
        rectNode.getBoundsInScreen(rc);
        if (rc.width() <= 0 || rc.height() <= 0 || rc.centerY() < 0) {
            target.getBoundsInScreen(rc);
        }
        if (rc.width() <= 0 || rc.height() <= 0) return false;
        boolean small = isSmallTarget(rc);
        // 实测：MIUI 开屏的「跳过 5」TextView 中心并不在可点热区里（中心点点不动、右侧 3~4dp 才生效），
        // 因此对「文字型小元素」把落点右移到 bounding box 的 right 附近。
        int tx = rc.centerX();
        if (small && str(target.getText()).length() > 0) {
            tx = rc.right - Math.max(8, rc.width() / 8);
        }
        boolean ok = tap(tx, rc.centerY());
        lastTapReliable = small;
        return ok;
    }

    /**
     * 关掉没有 id 的自渲染插屏：先在这个「大面积节点」范围内找小的可点击控件（通常是画出来的 ×），
     * 取离右下角最近的那个点头部中心；找不到就按比例点右下角。
     */
    private boolean autoClose(AccessibilityNodeInfo trigger, List<AccessibilityNodeInfo> nodes) {
        android.graphics.Rect big = bigRect(trigger);
        if (big == null) return false;
        if (isMainWindow(big)) return false;   // 弹窗已消失 / 本来就是普通页面，不要再点
        int w = big.width(), h = big.height();
        AccessibilityNodeInfo best = null;
        long bestScore = Long.MAX_VALUE;
        for (AccessibilityNodeInfo n : nodes) {
            if (n == null || !n.isClickable()) continue;
            android.graphics.Rect rc = new android.graphics.Rect();
            n.getBoundsInScreen(rc);
            if (rc.width() <= 0 || rc.height() <= 0) continue;
            if (rc.width() > w * 15 / 100 || rc.height() > h * 10 / 100) continue;   // 只要小控件
            if (!big.contains(rc)) continue;                                          // 必须在弹窗里
            // 离右下角越近越像关闭按钮
            long dx = big.right - rc.right;
            long dy = big.bottom - rc.bottom;
            long score = dx * dx + dy * dy;
            if (score < bestScore) {
                bestScore = score;
                best = n;
            }
        }
        if (best != null) {
            android.graphics.Rect rc = new android.graphics.Rect();
            best.getBoundsInScreen(rc);
            Log.i(TAG, "autoClose: click small control " + rc);
            if (best.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true;
            return tap(rc.centerX(), rc.centerY());
        }
        int x = big.right - Math.max(w * 5 / 100, 40);
        int y = big.bottom - Math.max(h * 3 / 100, 60);
        Log.i(TAG, "autoClose: ratio tap " + x + "," + y + " in " + big);
        return tap(x, y);
    }

    /**
     * 小米商店这类自渲染弹窗：× 是画在「应用名称：…」这条信息栏右侧的图片，树里没有节点。
     * 用信息栏自身的坐标反推：x 贴近屏幕右缘，y 与信息栏底部对齐。
     */
    private boolean tapInfoClose(AccessibilityNodeInfo trigger) {
        android.graphics.Rect rc = new android.graphics.Rect();
        trigger.getBoundsInScreen(rc);
        android.util.DisplayMetrics dm = getResources().getDisplayMetrics();
        int x = dm.widthPixels - (int) (dm.density * 68);
        int y = rc.bottom - (int) (dm.density * 4);
        Log.i(TAG, "tapInfoClose: bar=" + rc + " -> tap " + x + "," + y);
        return tap(x, y);
    }

    /** 找出节点（或祖先）里最大的那个矩形。 */
    private android.graphics.Rect bigRect(AccessibilityNodeInfo node) {        AccessibilityNodeInfo cur = node;
        android.graphics.Rect best = null;
        long bestArea = 0;
        for (int i = 0; i < 6 && cur != null; i++) {
            android.graphics.Rect rc = new android.graphics.Rect();
            cur.getBoundsInScreen(rc);
            long area = (long) rc.width() * rc.height();
            if (area > bestArea && isBigSelf(cur)) {
                bestArea = area;
                best = rc;
            }
            cur = cur.getParent();
        }
        return best;
    }

    /** 是否为「应用主窗口」（整屏、从状态栏下沿开始）——用来判断弹窗是否已经关掉。 */
    private boolean isMainWindow(android.graphics.Rect rc) {
        try {
            List<android.view.accessibility.AccessibilityWindowInfo> wins = getWindows();
            if (wins == null) return false;
            for (android.view.accessibility.AccessibilityWindowInfo w : wins) {
                if (w.getType() != android.view.accessibility.AccessibilityWindowInfo.TYPE_APPLICATION) continue;
                android.graphics.Rect wr = new android.graphics.Rect();
                w.getBoundsInScreen(wr);
                if (Math.abs(wr.left - rc.left) <= 4 && Math.abs(wr.top - rc.top) <= 4
                        && Math.abs(wr.right - rc.right) <= 4 && Math.abs(wr.bottom - rc.bottom) <= 4) {
                    return true;
                }
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    /** 无 id 的图片按钮（自渲染广告的 ×）：按节点区域角点做手势点击。 */
    private boolean tapCorner(AccessibilityNodeInfo node, String corner) {
        AccessibilityNodeInfo box = node;
        for (int i = 0; i < 4 && box != null; i++) {
            android.graphics.Rect rc = new android.graphics.Rect();
            box.getBoundsInScreen(rc);
            if (rc.width() > 0 && rc.height() > 0 && isBigSelf(box)) {
                int inset = (int) (getResources().getDisplayMetrics().density * 22);
                int x = "TAP_BR".equals(corner) ? rc.right - inset : rc.right - inset;
                int y = "TAP_BR".equals(corner) ? rc.bottom - inset : rc.top + inset;
                return tap(x, y);
            }
            box = box.getParent();
        }
        return false;
    }

    /**
     * 按「屏幕相对坐标」点击，用于纯图片广告：x,y 是 0~1 的小数（相对屏幕）。
     * 例：TAP_AT:0.879,0.208 → 点屏宽 87.9% / 屏高 20.8% 的位置。
     */
    private boolean tapAt(String spec, AccessibilityNodeInfo trigger) {
        String[] p = spec.split(",");
        if (p.length != 2) return false;
        float fx, fy;
        try {
            fx = Float.parseFloat(p[0].trim());
            fy = Float.parseFloat(p[1].trim());
        } catch (Throwable t) {
            return false;
        }
        if (fx <= 0f || fx >= 1f || fy <= 0f || fy >= 1f) return false;
        android.util.DisplayMetrics dm = getResources().getDisplayMetrics();
        int x = (int) (dm.widthPixels * fx);
        int y = (int) (dm.heightPixels * fy);
        Log.i(TAG, "profile tap " + String.format("%.3f,%.3f", fx, fy) + " -> " + x + "," + y);
        return tap(x, y);
    }

    private void markJumpWatch(String sig) {
        jumpSig = sig;
        jumpFromPkg = curPkg;
        jumpAt = android.os.SystemClock.elapsedRealtime();
    }

    private boolean tap(int x, int y) {
        try {
            lastTapX = x;
            lastTapY = y;
            lastTapReliable = false;   // 由调用方在确认是小元素后置 true
            android.graphics.Path p = new android.graphics.Path();
            p.moveTo(x, y);
            android.accessibilityservice.GestureDescription.Builder b =
                    new android.accessibilityservice.GestureDescription.Builder();
            b.addStroke(new android.accessibilityservice.GestureDescription.StrokeDescription(p, 0, 60));
            boolean ok = dispatchGesture(b.build(), null, null);
            Log.i(TAG, "tap " + x + "," + y + " -> " + ok);
            return ok;
        } catch (Throwable t) {
            Log.w(TAG, "tap failed: " + t);
            return false;
        }
    }

    private void loadRules() {
        rules.clear();
        try {
            InputStream in = getAssets().open("skip_rules.json");
            BufferedReader r = new BufferedReader(new InputStreamReader(in, "UTF-8"));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = r.readLine()) != null) sb.append(line);
            r.close();
            JSONObject obj = new JSONObject(sb.toString());
            JSONArray arr = obj.optJSONArray("popup_rules");
            if (arr != null) {
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject o = arr.optJSONObject(i);
                    if (o == null) continue;
                    Rule rule = new Rule();
                    rule.id = o.optString("id", "");
                    rule.action = o.optString("action", "");
                    rule.times = o.optInt("times", 3);
                    if (rule.id.length() > 0 && rule.action.length() > 0) rules.add(rule);
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "load rules failed: " + e);
        }
        loaded = true;
        Log.i(TAG, "rules loaded: " + rules.size());
    }

    /** 供界面判断服务是否被系统启用。 */
    public static boolean isEnabled(Context ctx) {
        String flat = android.provider.Settings.Secure.getString(
                ctx.getContentResolver(),
                android.provider.Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        if (flat == null) return false;
        String want = ctx.getPackageName() + "/" + SkipService.class.getName();
        for (String s : flat.split(":")) {
            if (s.equalsIgnoreCase(want)) return true;
        }
        return false;
    }

    /** 兼容旧版：给未来规则热更新预留（当前未使用）。 */
    public static Set<String> knownIds() {
        return new HashSet<String>();
    }
}
