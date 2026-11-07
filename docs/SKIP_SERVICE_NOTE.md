# A 方案落地记录：无障碍自动跳过（netkit）

> 2026-11-02 23:30 · 对应提交 `71d0a2a`
> 目标：干掉 DNS 拦不掉的那一类广告——已经渲染出来的**整屏插屏 / 半屏弹窗**。

## 1. 做了什么

在 `com.zszl.netkit`（网络工具箱）里新增一个**可选**的无障碍服务 `SkipService`：

| 组成 | 文件 | 说明 |
|---|---|---|
| 服务 | `app/src/com/zszl/netkit/SkipService.java` | 监听窗口事件 → 遍历窗口树 → 命中规则就点 |
| 配置 | `app/res/xml/skip_service.xml` | `typeWindowStateChanged|typeWindowContentChanged`、`canRetrieveWindowContent` |
| 规则 | `app/assets/skip_rules.json` | 16 条，语义对齐李跳跳 |
| 开关 | `MainActivity` 里「自动跳过开屏 / 插屏（需无障碍权限）」 | 默认**关**；首次打开会跳系统无障碍设置 |
| 构建 | `build/build_apk.ps1` | 新增 `aapt2 compile --dir app/res`，支持资源目录 |

**默认关闭**是刻意的：无障碍权限敏感，不开这个开关时 `SkipService` 收到任何事件都立刻返回，不做任何事。

## 2. 规则格式（与李跳跳 AllRules.json 同构）

```json
{"popup_rules":[
  {"id":"ad_close","action":"ad_close","times":8},
  {"id":"|跳过","action":"|跳过","times":4},
  {"id":"tt_splash_ad_logo","action":"tt_splash_skip_btn","times":4},
  {"id":"NativeVideoAdView","action":"GLOBAL_ACTION_BACK","times":2}
]}
```

- `id` 的匹配语义：`|文本` = 包含、`=文本` = 相等、`+文本` = 前缀、无前缀 = 资源 id 后缀或 contentDescription 相等。
- `action`：目标资源 id / 文本 / `GLOBAL_ACTION_BACK` / `GLOBAL_ACTION_HOME` / `TAP_BR`（点节点区域右下角）/ `TAP_TR`（右上角）。
- `times`：单次运行内最多触发次数，防死循环；另有 1.5 s 规则级冷却 + 250 ms 扫描节流 + 5 s 调试 dump 节流。

## 3. 两个关键工程细节（踩过才知道）

1. **广告弹窗是独立窗口**。只取 `getRootInActiveWindow()` 拿不到 GDT / 快手 / 小米商店的弹窗——它们的 root 在 `getWindows()` 的另一层。必须把所有 window 的 root 都收进来并按包名过滤，否则规则永远命中不到（这是第一版 `fired=0` 的真因）。
2. **有些 × 是画出来的图片，树里既没有 id 也没有 desc**。对这类节点提供 `TAP_BR` / `TAP_TR` 手势点击（`dispatchGesture`），按节点（或祖先）区域的角点换算坐标。
   `GLOBAL_ACTION_BACK` / `TAP_*` 只在「节点或祖先 ≥35% 屏幕」时放行，避免把手滑信息流里的小广告条当成插屏处理。

## 4. 实测

| 场景 | 结果 |
|---|---|
| 冷启动 DNS 拦截 | **112–128 条 BLOCK / 轮**（大清单 319,962 条） |
| 广点通整屏插屏（`ad_close` 有 id） | ✅ `skip fired: ad_close -> ad_close (n=1)`，弹窗关闭 |
| 干净启动（无插屏） | ✅ 无副作用，`fired=0`，首页正常 |
| 小米应用商店自渲染弹窗（× 是图片、无 id） | ⚠️ 已加 `TAP_INFO_CLOSE`（按「应用名称：…」信息栏反推 × 坐标）与主窗口守卫，**等该弹窗再出现时才能确认** |
| shell 侧 DNS（uid 2000） | 不进 VPN，per-app 隔离仍成立 |

### 4.1 坐标剖面这一轮修了什么

- **主窗口守卫**：`autoClose()` 里先用 `isMainWindow(bigRect)` 比对 `getWindows()` 中 `TYPE_APPLICATION` 窗口的 bounds（容差 4px），相等就 return false。修掉了「弹窗关掉后仍然按比例点右下角 → 点到导航栏」的问题。
- **`TAP_INFO_CLOSE`**：小米商店那类弹窗的 × 不存在于无障碍树，但它下面固定有一条 `应用名称：应用商店 | 开发者：…` 信息栏（实测 `Rect(64,2454-970,2536)`，× 实测在 `(1103,2530)`）。因此用信息栏自身坐标反推：`x = 屏宽 - 68dp`、`y = 信息栏.bottom - 4dp`，用 `dispatchGesture` 点击。
- 因为 `getWindows()` 需要权限位，`skip_service.xml` 增加 **`flagRetrieveInteractiveWindows`**（否则窗口列表恒为空、`WIN` 日志一条都不出）。

## 5. 已知边界与下一步

- **小米商店那类自渲染弹窗**要在「弹窗出现时」测出 × 的实际坐标，做成「按 SDK 的坐标剖面」（不同分辨率按比例换算），而不是固定角点。当前 `TAP_BR` 只是兜底，落点不准。
- 规则现在是**编译期内置**（assets）。下一步接 `files/skip_rules.json` 做可热更新 + 设置里粘贴导入。
- 无障碍权限会被部分安全软件标记；文档与 UI 里都写明「仅在你主动打开时工作、只作用于选中的应用」。

## 6. 回滚

关掉「自动跳过」开关即可；或在系统无障碍里关掉「网络工具箱（自动跳过）」。
`adb shell settings put secure enabled_accessibility_services null` 也能一次性清空。

## 2026-11-03 更新（第三轮）

### 1. 命中方式：contentDescription 也算
快手系（com.kwad）全屏视频插屏的关闭按钮**没有资源 id，只有 contentDescription**：

```
id=- click=true cls=ViewGroup b=Rect(299,1534-363,1597) desc=inter-close-button_closeBtn
id=- click=false cls=ViewGroup b=Rect(997,1460-1200,1515) desc=auto_call_close_button
id=com.kwad.dy.sdk:id/ksad_video_container click=true b=Rect(0,140-1200,2618)
```

所以 `matches()` 现在：无 rid 时 `desc.equals(spec)`，有 rid 时也保留 desc 兜底。
规则新增 `inter-close-button_closeBtn` / `auto_call_close_button` / `|closeBtn` / `|close_button` / `|inter-close` / `|关闭广告`。

### 2. 盲点击启发式已关闭
按「卡片比例猜 × 位置」的 `heuristicClose()` 实测会点到广告内容（触发系统「已禁止运动世界启动京东 允许一次」），风险大于收益，已注释掉。**现在只按 id / contentDescription 精确命中再点，绝不盲点。**

### 3. 装新包会丢无障碍绑定（重要）
`adb install -r` 后 `dumpsys accessibility` 会变成 `Bound services:{}`，服务静默不工作（日志一条都没有）。
- 命令行恢复：`adb shell settings put secure enabled_accessibility_services com.zszl.netkit/com.zszl.netkit.SkipService`
- 界面恢复：开关打开但未授权时，主页会出现「重新授权无障碍（跳过功能用）」按钮，点它跳到系统无障碍页。

### 4. 实测
单轮冷启动：DNS 拦截 59 次（147 查询，103 放行）+ 跳过 2 次（广点通 `ad_close` + 快手全屏 `inter-close-button_closeBtn`），落地为干净首页。

## 2026-11-03 第四轮：坐标剖面「可信学习」

### 为什么不做「人工点击学习」
人工点 × 时若误触广告内容 → 可能触发跳转，且会把错误坐标写成规则。因此学习来源**只保留一种**：我们自己的规则点完之后、广告特征确实消失的样本。

### 六道闸门
1. **来源**：只学 `skip fired` 成功样本，不接任何人工/盲点击；
2. **落点可信**：只有面积 < 6% 屏的小元素中心才算坐标（`isSmallTarget()`）——点在大容器中心得到的是「容器中心」，不是 × 的位置（上一版就是这么学到 `0.5,0.128` 的废样本）；
3. **签名收敛**：只用能区分版式的特征 `上滑或点击 / 下载或跳转第三方应用 / 应用名称： / 关闭广告`，剔除「广告」「跳过」这类通用词（否则不同广告会混成一个签名）；
4. **生效门槛**：同一签名要 ≥2 次、且落点在 4% 屏范围内聚类，才允许被自动套用；单次样本永不生效；
5. **应用即验证**：每次套用后 1.2s 复核，广告特征还在 → `misses++`；累计 2 次 miss → 直接删除该剖面（`profiles.json`）；
6. **危险区**：落点若在 CTA 文案（立即下载 / 查看详情 / 立即打开 / 了解更多 / 点击查看 / 去逛逛 / 立即进入）矩形外扩 5% 内 → 丢弃样本、也不点击；状态栏 6% / 导航栏 94% 区域一律不学不点。

### 实机验证
```
profile apply sig=广告 -> 1113,1236 hits=4.0
profile miss sig=广告 misses=1.0
profile miss sig=广告 misses=2.0
profile demoted sig=广告                      ← 旧版学到的坏样本，自动作废
profile tap 0.879,0.208 -> 1054,555
profile learn sig=上滑或点击 at 0.878,0.208 hits=1.0   ← 与手算值一致
```
存储位置：`/data/data/com.zszl.netkit/files/profiles.json`（JSON：签名 → [x, y, hits, misses]）。

## 2026-11-03 第五轮：人工复核 + 跳转检测

### 三态档案（`Profiles.java`）
`pending` 待复核 / `auto` 生效 / `off` 停用。只有 `auto && hits>=2` 才会被 `SkipService` 套用。
默认 `Prefs.review()=true`：新学到的样本一律 `pending`，**必须人工点「启用」才会生效**；关掉复核开关则回到「2 次自动确认即生效」。

### 人工复核页（`ProfilesActivity`）
入口：设置页「管理坐标学习（人工复核）」。逐条显示签名 / 命中 / 失误 / 坐标 / 状态，可单独「启用 / 停用 / 删除」，另有「清空全部学习记录」。
旧版的坏样本（`广告+跳过` @ `0.500,0.128`，来自早期「跳过」按钮的可点击祖先）自动以 `pending` 身份出现在列表里，不再生效。

### 跳转检测（负样本信号）
`act()` 点击成功后开始 3 秒观察窗；若期间收到 `TYPE_WINDOW_STATE_CHANGED` 且包名不再是目标应用 → 判定「这一下点到了广告内容」：
```
jump detected: com.zjwh… -> com.jingdong.app.mall d=812ms sig=上滑或点击+…
profile disabled by jump sig=…
```
该样本 `misses += 2` 并直接置 `off`，不再参与自动套用。

### 实机日志
```
NetKitSkip: rules loaded: 24
NetKitSkip: profiles loaded: 1
NetKitSkip: profile learn sig=上滑或点击+下载或跳转第三方应用 at 0.879,0.208 hits=8 state=pending
NetKitSkip: skip fired: =跳过 -> =跳过 (n=4)
NetKitDns: BLOCK …（本轮 58 次）
```
`hits` 已经累到 8 但状态仍是 `pending` —— 复核开关生效时命中数不自动放行。
