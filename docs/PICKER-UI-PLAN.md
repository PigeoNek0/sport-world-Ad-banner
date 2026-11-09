# 应用选择页改造 · 设计计划清单

> 目标：把「选择要净化的应用」从一张 488 行的裸列表，改成**可搜索、可分类、可排序**的选择器，
> 且保证三件事不出错：**勾选不丢、保存只看全量、切筛选不误判**。
>
> 全部决策已与 maintainer 逐条确认（Q1–Q26），本文是唯一的实施依据。

---

## 1. 分类判据（唯一来源：`ApplicationInfo.flags` + `PackageManager`）

| Spinner 项 | 判据 | 实测规模（本机 488 包） |
|---|---|---|
| 全部 | 全部可见条目 | 488 |
| 外部应用 | `(flags & FLAG_SYSTEM) == 0` **且** 有 LAUNCHER activity | ~40–60 |
| 系统应用 | `(flags & FLAG_SYSTEM) != 0`（含 `FLAG_UPDATED_SYSTEM_APP`）**且** 有 LAUNCHER activity | ~60–80 |
| 系统组件 | 无 LAUNCHER activity | ~330–380 |

> 「有 LAUNCHER activity」= `pm.getLaunchIntentForPackage(pkg) != null`，等价于用户能点开它。
> 口径统一用「有无启动图标」，不引入前缀黑名单、不引入 INTERNET 作为分类条件。

**两条永久规则**

- `com.zszl.netkit` 自身**不进列表**。
- 声明了 `android.net.VpnService` 的其他 App：**灰显 + 不可勾选**，行尾标「VPN 类，不可选」。
  判定用 `queryIntentServices(new Intent("android.net.VpnService"), 0)`（`QUERY_ALL_PACKAGES` 已声明，比读 `requestedPermissions` 可靠）。

---

## 2. 顶部布局

```
┌──────────────────────────────────────────────┐
│ 选择要净化的应用                              │ 20sp 标题
│ 勾选后返回即生效；同一时间只允许一个 VPN。     │ 12sp 说明
│ 已选 5 个 · 当前筛选隐藏 2 个                  │ 13sp 状态行
├──────────────────────────────────────────────┤
│ [🔍 搜索应用名或包名            ] [全部 488 ▾] │ 搜索 70% + 分类 Spinner 30%
│ ☑ 仅显示有网络权限  ☑ 已选置顶  [名称 ▾]       │ 设置行（排序 Spinner 右对齐）
│                          含 2 个无网络已选     │ 右对齐小字（仅 N>0 时出现）
├──────────────────────────────────────────────┤
│ ☑ 运动世界  (com.zjwh.android_wh_physicalfitness) │
│    默认目标                                   │
│ ☐ 京东      (com.jd.jrapp)                     │
└──────────────────────────────────────────────┘
```

- 分类 Spinner：`全部 / 外部应用 / 系统应用 / 系统组件`，**带数量**、**为 0 也保留**。
- 排序 Spinner：`名称 / 最近使用 / 安装时间`，默认「名称」。
- 已选置顶：**开关，默认开**（关闭后纯按排序轴排）。

---

## 3. 数量与提示口径

| 显示项 | 口径 |
|---|---|
| Spinner 数量 | **满足当前其他筛选后**的条数（INTERNET 开时「系统组件 0 ▾」是正确结果） |
| 状态行「隐藏 N 个」 | **只统计被分类筛掉**的已选（不含 INTERNET 筛掉的） |
| 设置行小字「含 N 个无网络已选」 | 只统计**被 INTERNET 开关筛掉**的已选，N>0 才显示 |

---

## 4. 行为契约

| 项 | 行为 |
|---|---|
| 搜索 | 显示名 + 包名双匹配、大小写不敏感、**实时过滤 + 200ms 防抖** |
| 空结果 | 「没有匹配的应用」+ 「清除筛选」按钮 |
| 进入页面 | 一律重置：全部 / 空搜索 / INTERNET 开 / 已选置顶开 / 名称排序 |
| 保存 | `onPause` 仍以 **`items` 全量**为准，与任何筛选无关 |
| 目标变化 | 已选集合变化且 VPN 运行中 → `ACTION_RELOAD`（沿用现状，不改成 STOP） |
| 最近使用 | 需 `PACKAGE_USAGE_STATS`；未授权时下拉该行右侧提示「需授权，点此去开」，并**自动降级为安装时间** |
| 深色模式 | `res/values/colors.xml` + `res/values-night/colors.xml`，三页统一取色 |

---

## 5. 数据模型

```java
static final class Entry {
    String pkg, label;
    boolean checked;      // 唯一数据源，筛选/搜索都不写它
    boolean isSystem;     // FLAG_SYSTEM != 0
    boolean hasLauncher;  // getLaunchIntentForPackage != null
    boolean hasNet;       // requestedPermissions 含 INTERNET
    boolean isVpn;        // VpnService 出现在其 services 里
    long installTime;     // firstInstallTime
    long lastUse;         // UsageStats 最近一次，未授权为 0
}
```

- `items`：全量（排序后）。
- `view`：当前可见子集（`filtered`），只被 `applyFilter()` 重建。
- 行内 `CheckBox` 的 `OnCheckedChangeListener` 只写 `Entry.checked`，**不触发重建**。

---

## 6. 实施步骤（checklist）

- [ ] **P1 数据采集**：`onCreate` 里一次性取 `getInstalledApplications(0)` + `GET_PERMISSIONS`，填充 7 个字段；`queryIntentServices` 取 VPN 包名集合；隐藏自身。
- [ ] **P2 视图分离**：`items` / `view` 两个 List，`applyFilter()` 负责 分类 → INTERNET → 搜索 → 排序 → 置顶 五步流水；每次重建后 `notifyDataSetChanged()`。
- [ ] **P3 列表复用**：`getView` 改 `convertView` + `ViewHolder`；赋值顺序 `setOnCheckedChangeListener(null)` → `setChecked()` → 重挂监听，防复用错位。
- [ ] **P4 搜索框**：`EditText` 单行 + `TextWatcher`，200ms `Handler.postDelayed` 防抖；空态切换 `emptyView`。
- [ ] **P5 两个 Spinner**：分类（4 项带数量）/ 排序（3 项），`setOnItemSelectedListener` → `applyFilter()`。
- [ ] **P6 设置行**：两个 `CheckBox` + 排序 Spinner + 右对齐小字，随筛选结果动态更新文案。
- [ ] **P7 状态行**：`已选 N 个` / `· 当前筛选隐藏 M 个`（M 仅分类口径）。
- [ ] **P8 VPN 灰显**：行文字变灰、`CheckBox.setEnabled(false)`、行尾提示。
- [ ] **P9 最近使用**：`UsageStatsManager` 授权检测 + 跳 `Settings.ACTION_USAGE_ACCESS_SETTINGS` + 降级。
- [ ] **P10 深色**：`colors.xml` / `values-night/colors.xml`；`AppPicker` / `Main` / `Profiles` 三页取色替换硬编码。
- [ ] **P11 构建**：`build\build_apk.ps1 -Sign` 通过，`apksigner` v2+v3。
- [ ] **P12 真机验证**（见 §7）。
- [ ] **P13 文档**：README 使用段补「搜索 / 分类 / 排序」；本文件勾选完成项。

---

## 7. 验证矩阵（装机必过）

| # | 用例 | 判据 |
|---|---|---|
| T1 | 默认进入 | 全部 488、搜索空、INTERNET 开、已选置顶开、名称排序 |
| T2 | 切「系统组件」 | 数量显示为 0（INTERNET 开），列表「没有匹配的应用」 |
| T3 | 关 INTERNET 后切「系统组件」 | 出现 300+ 条，含 `com.qualcomm.qtil.*` |
| T4 | 搜索 `运动` / `zjwh` | 都能命中运动世界 |
| T5 | 搜索乱串 | 空态 + 「清除筛选」可用 |
| T6 | 勾 3 个（含 1 个无网络）→ 切「外部应用」 | 状态行「已选 3 个 · 隐藏 1 个」；设置行小字「含 1 个无网络已选」 |
| T7 | 勾选后滚动 20 屏 | 无错位、无自动勾选/取消 |
| T8 | 勾选 → 返回 → 重进 | 勾选与「已选 N」一致（写盘未丢） |
| T9 | 过滤运行中改选 | 日志出现 `ACTION_RELOAD` 而非 `ACTION_STOP`，`vpn up` 重建成功 |
| T10 | 切「最近使用」（未授权） | 提示「需授权」，自动降级安装时间，不白屏 |
| T11 | 系统深色模式切换 | 三页底色/文字自动跟随，无白底刺眼 |
| T12 | 自研 App 不出现在列表 | `pm list` 里有、页面上无 |

---

## 8. 风险与对策

| 风险 | 对策 |
|---|---|
| 复用导致复选框错位（最常见事故） | `ViewHolder` + 监听解绑/重挂顺序固定，T7 专项验证 |
| 切筛选丢掉已选 | 勾选只写 `Entry.checked`，`view` 是引用子集不复制对象 |
| `GET_PERMISSIONS` 在 Android 11+ 受限 | 已声明 `QUERY_ALL_PACKAGES`；取不到时 `hasNet` 默认 `true`（宁可多显示，不漏拦） |
| `UsageStats` 拿不到数据 | 降级安装时间 + 提示，不阻塞 |
| 300+ 行滚动掉帧 | T7 同时观察掉帧；必要时改 `ListView` 为 `holder` + 固定行高 |

---

## 9. 影响文件

```
app/src/com/zszl/netkit/AppPickerActivity.java    重写（约 +320 / −70）
app/src/com/zszl/netkit/MainActivity.java          取色改为 colors.xml（约 6 行）
app/src/com/zszl/netkit/ProfilesActivity.java      同上（约 6 行）
app/res/values/colors.xml                          新增
app/res/values-night/colors.xml                    新增
app/AndroidManifest.xml                            新增 PACKAGE_USAGE_STATS 声明
README.md                                          使用段补搜索/分类/排序
build/build_apk.ps1                                若新增 res 目录需确认 aapt2 已递归编译（现状已支持 --dir app/res）
```

**不动**：`Prefs`、`AdVpnService`、`SkipService`、`Profiles`、`FilterList`、`DnsProxy`、清单资产。本次是纯 UI 改造。

---

## 10. 回滚

- 单文件回滚即可：`git checkout -- app/src/com/zszl/netkit/AppPickerActivity.java app/res`。
- 新增权限 `PACKAGE_USAGE_STATS` 属特殊权限，不影响安装与上架表述（README 隐私段补一句「仅用于本机排序」）。

---

## 11. 洁净约定（本轮起执行）

- 构建产物、截图、日志一律进 `build/`（已 `.gitignore` 白名单，只放行 4 个脚本）。
- 真机验证截图存 `build/ui-*.png`，**不入库**。
- 每完成一个阶段只提交必要文件，不提交 `*.apk` / `*.jks` / 大清单副本。
- 提交前跑 `git status --short` 必须只有预期文件；完成后必须为空。
