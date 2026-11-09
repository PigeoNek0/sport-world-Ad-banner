# 应用选择页改造 · 设计计划清单（v2）

> 目标：把「选择要净化的应用」从一张 488 行的裸列表，改成**可搜索、可分类、可排序**的选择器，
> 并保证三件事不出错：**勾选不丢、保存只看全量、切筛选不误判**。
>
> - v1 = `cda0b20`
> - v2 = 本文件，吸收 `docs/PICKER-UI-PLAN-REVIEW.md` 的 R1–R18 全部意见
> - 决策来源：Q1–Q26（maintainer 逐条确认）+ R1–R18（审查修订，其中 5 条口径变更列在 §12）

---

## 0. 与 v1 的差异（先看这个）

| 类别 | 变化 |
|---|---|
| 🔴 构建链 | **新增 P0b**：原链路先 `javac` 后 `aapt2 link`，没有 `--java gen`，一旦用 `R.color.*` 就编不过。必须改成 `compile → link --java → javac` 的新顺序（R1） |
| 🔴 深色模式 | 原方案只加 `values-night/colors.xml` 不生效：主题写死 Light、控件色/弹层色由主题决定。**新增 `values/styles.xml` + `values-night/styles.xml`**（R2） |
| 🔴 数量假设 | 原「INTERNET 开 → 系统组件 ≈ 0」不成立（GMS/WebView/推送等无图标包普遍申请 INTERNET）。**新增 P0 实测**，回填真实数字（R3/R4） |
| 🟠 采集方式 | 改用**一次** `getInstalledPackages(GET_PERMISSIONS \| GET_SERVICES)` + **一次** `queryIntentActivities(MAIN/LAUNCHER)`，放后台线程（R5/R6） |
| 🟠 VPN 判定 | 改用 `ServiceInfo.permission == BIND_VPN_SERVICE`；已存 VPN 包强制取消并提示（R7） |
| 🟠 计数口径 | 新增优先级「分类 > INTERNET > 搜索」，文案改「分类隐藏 N 个」，Spinner 数量不受搜索影响（R8/R9） |
| 🟠 排序 | 中文名改用 `Collator.getInstance(Locale.CHINA)`，比较键预计算（R12） |
| 🟠 既有 bug | 保存时保留 Prefs 里**未安装**的包名，避免每次进页面都误触发 RELOAD（R15） |
| 🟡 版本号 | `versionCode 2 / versionName 1.1.0`，构建脚本参数化（R16） |
| 🟡 验收 | 签名以 `apksigner verify -v` 实际输出为准，不写死「v2+v3」（R17） |
| 🟡 回滚 | 分阶段提交 + `git revert`，不再用 `git checkout -- <path>`（R18） |

---

## 1. 分类判据

> 判据唯一来源：`ApplicationInfo.flags` + 一次 LAUNCHER 查询。**不含自身**（`com.zszl.netkit`）。

| Spinner 项 | 判据 | 规模 |
|---|---|---|
| 全部 | 全部可见条目（= 不含自身） | 487（INTERNET 关时）／见 §1.1 |
| 外部应用 | `(flags & FLAG_SYSTEM) == 0` **且** 有 LAUNCHER activity | 待 P0 实测 |
| 系统应用 | `FLAG_SYSTEM != 0`（含 `FLAG_UPDATED_SYSTEM_APP`）**且** 有 LAUNCHER activity | 待 P0 实测 |
| 系统组件 | 无 LAUNCHER activity | 待 P0 实测 |

- 「有 LAUNCHER activity」= 该包名出现在一次 `queryIntentActivities(ACTION_MAIN + CATEGORY_LAUNCHER)` 的结果集合里。
- **命名提示**：这一类的准确定义是「无启动图标的包」，其中也包含无图标的**用户**应用（输入法、插件）。名称「系统组件」沿用 Q1 决策，仅在此注明确切范围。

### 1.1 数量必须实测（P0 交付物）

四个分类 × INTERNET 开/关 = 8 个数字，用 adb 实测后回填上表，**不预设任何数字**（尤其不能假设「系统组件 = 0」）。

```sql
-- 采集口径（与 App 内一致）
总数          = pm list packages                      minus 自身
有 LAUNCHER   = cmd package query-activities -a android.intent.action.MAIN -c android.intent.category.LAUNCHER
FLAG_SYSTEM   = pm list packages -s
三方          = pm list packages -3
有 INTERNET   = dumpsys package <pkg> | grep "android.permission.INTERNET"   （逐包或抽样）
```

> 设备未连接时该步骤阻塞，**P0 未完成不得开始 P2 之后的实现**（否则 T1/T2 判据无意义）。

---

## 2. 顶部布局

```
┌────────────────────────────────────────────────┐
│ 选择要净化的应用                                │ 20sp
│ 勾选后返回即生效；同一时间只允许一个 VPN。       │ 12sp
│ 已选 5 个 · 分类隐藏 2 个                        │ 13sp（§3 口径）
├────────────────────────────────────────────────┤
│ [🔍 搜索应用名或包名             ] [全部 487 ▾]  │ 搜索 70% + 分类 Spinner 30%
│ ☑ 仅显示有网络权限  ☑ 已选置顶      [名称 ▾]     │ 设置行（窄屏拆两行，R14）
│                            含 2 个无网络已选     │ 右对齐小字，N>0 才显示
├────────────────────────────────────────────────┤
│ ☑ 运动世界  (com.zjwh.android_wh_physicalfitness)│
│    默认目标                                     │
│ ☐ 京东      (com.jd.jrapp)                       │
│ ☐ 某 VPN    (com.example.vpn)   VPN 类，不可选    │ 灰显
└────────────────────────────────────────────────┘
```

- 分类 Spinner：`全部 / 外部应用 / 系统应用 / 系统组件`，**带数量**，**为 0 也保留**。
- 排序 Spinner：`名称 / 最近使用 / 安装时间`，默认「名称」。
- 已选置顶：开关，默认**开**。

---

## 3. 计数与提示口径（R8/R9/R11）

**每个已选项只归入一个原因，优先级：分类 > INTERNET > 搜索。**

| 显示项 | 口径 |
|---|---|
| Spinner 数量 | **只受 INTERNET 开关影响**，不受搜索影响（打字时数字不跳动） |
| 状态行 | `已选 N 个`，或被隐藏时 `已选 N 个 · 分类隐藏 M 个`（M 仅「被分类筛掉」） |
| 设置行小字 | `含 K 个无网络已选`（K 仅「被 INTERNET 筛掉」），K>0 才显示 |
| 仅被搜索隐藏 | **不提示**（用户自己在打字，预期之内） |
| 已选为 0 | 状态行追加 `（未选任何应用时默认净化运动世界）`（既有兜底行为，见 §11） |

勾选/取消勾选时**必须立即刷新**状态行与小字（不重建列表，只改文本）。

---

## 4. 行为契约

| 项 | 行为 |
|---|---|
| 搜索 | 显示名 + 包名双匹配、`Locale.ROOT` 小写比较、**实时过滤 + 200ms 防抖** |
| 空结果 | 「没有匹配的应用」+「清除筛选」 |
| 清除筛选（R13） | 只做两件事：清空搜索、分类回「全部」。INTERNET / 置顶 / 排序属偏好，不动 |
| 进入页面 | 一律重置：全部 / 空搜索 / INTERNET 开 / 已选置顶开 / 名称排序 |
| 保存 | `onPause` 以 **`items` 全量** `checked` 为准，与筛选完全无关 |
| 未安装包（R15） | Prefs 里存在但当前未安装的包名**原样保留**，`changed` 比较用并集 → 不再每次误 RELOAD |
| 目标变化 | 已选集合变化且 VPN 运行中 → `ACTION_RELOAD`（不改成 STOP） |
| 最近使用（R10） | 选中时检测授权；未授权弹对话框说明并跳 `Settings.ACTION_USAGE_ACCESS_SETTINGS`，同时降级为安装时间；`onResume` 复检，授权回来自动恢复「最近使用」；时间窗 = 近 30 天 `lastTimeUsed` |
| 跳设置页的副作用 | 会触发 `onPause` 保存；若无变更则不发 RELOAD（`changed=false`） |
| VPN 包（R7） | 灰显 + 不可勾选；若 Prefs 里已存 VPN 包，加载时强制取消并 Toast 一次 |
| 深色模式（R2） | 由 `AppTheme` + `values-night` 决定，状态栏浅色标志按 `UI_MODE_NIGHT_MASK` 条件设置 |

---

## 5. 数据模型（10 字段，来源标注）

```java
static final class Entry {
    String  pkg;          // pi.packageName
    String  label;        // pm.getApplicationLabel(pi.applicationInfo)
    String  sortKey;      // Collator 预计算的比较键（R12）
    boolean checked;      // 唯一数据源；筛选/搜索只读不写
    boolean isSystem;     // (pi.applicationInfo.flags & FLAG_SYSTEM) != 0
    boolean hasLauncher;  // pkg ∈ 一次 queryIntentActivities 结果集
    boolean hasNet;       // pi.requestedPermissions 含 INTERNET；拿不到时默认 true
    boolean isVpn;        // 任一 pi.services[i].permission == BIND_VPN_SERVICE
    long    installTime;  // pi.firstInstallTime
    long    lastUse;      // UsageStats 近 30 天 lastTimeUsed；未授权为 0
}
```

- `items`：全量（按当前排序轴排好）。
- `view`：可见子集（**引用**同一批 Entry 对象，不复制）。
- `applyFilter()` 五步流水：分类 → INTERNET → 搜索 → 排序 → 置顶。
- 行内监听只写 `Entry.checked` 并刷新状态文本，**不触发重建**；改用 ViewHolder 后必须从 holder 取当前 `Entry`，不能再像 v1 那样在 lambda 里捕获 `e`（R11）。

---

## 6. 构建链改造（P0b，R1）

**现状**（`build/build_apk.ps1` 实测）：`[1/7] javac` → `[2/7] jar` → `[3/7] d8` → `[4/7] aapt2 compile + link`（**无 `--java`**）→ `[5/7]` 追加 dex → `[6/7] zipalign` → `[7/7] apksigner`。
因此 Java 代码里**不能**引用 `R.*`，否则 `javac` 阶段就失败。

**新顺序**：

```
[0/8] clean
[1/8] aapt2 compile --dir app/res -o out/res.zip
[2/8] aapt2 link -o out/base.apk -I android.jar --manifest app/AndroidManifest.xml \
              -A app/assets --java out/gen @out/res.zip \
              --min-sdk-version 26 --target-sdk-version 36 \
              --version-code $VersionCode --version-name $VersionName
[3/8] javac -source 8 -target 8 -encoding UTF-8 -classpath android.jar -d out/classes \
            (app/src/**/*.java + out/gen/**/R.java)
[4/8] jar cf out/classes.jar -C out/classes .
[5/8] d8 --lib android.jar --min-api 26 --output out/dex out/classes.jar
[6/8] adddex.py → 把 classes.dex 注入 out/base.apk
[7/8] zipalign -p -f 4
[8/8] apksigner sign（-Sign 时）+ apksigner verify -v
```

- `aapt2 link` 需要在 `app/res` 下至少存在一个资源（现有 `res/xml/skip_service.xml` 已满足），`--java` 会生成 `out/gen/com/zszl/netkit/R.java`。
- 参数化（R16）：新增 `-VersionCode`（默认 2）、`-VersionName`（默认 `1.1.0`），`AndroidManifest.xml` 同步改。

---

## 7. 主题与深色（P10，R2）

`minSdk 26` 不能用 API 29 的 `Theme.DeviceDefault.DayNight`，所以用限定符分文件：

```xml
<!-- app/res/values/styles.xml -->
<style name="AppTheme" parent="@android:style/Theme.DeviceDefault.Light.NoActionBar">
    <item name="android:windowBackground">@color/bg</item>
</style>

<!-- app/res/values-night/styles.xml -->
<style name="AppTheme" parent="@android:style/Theme.DeviceDefault.NoActionBar">
    <item name="android:windowBackground">@color/bg</item>
</style>
```

- `colors.xml` / `values-night/colors.xml` 提供 `bg / text_primary / text_secondary / text_disabled / accent`。
- 清单：`android:theme="@style/AppTheme"`。
- 三个 Activity 移除 `setStatusBarColor(0xFFF2F2F2)` 硬编码，改取 `@color/bg`，并把 `SYSTEM_UI_FLAG_LIGHT_STATUS_BAR` 用 `(getResources().getConfiguration().uiMode & UI_MODE_NIGHT_MASK) != UI_MODE_NIGHT_YES` 条件设置。

---

## 8. 实施步骤（P0–P14）

- [ ] **P0 实测口径**：按 §1.1 采集 8 个数字，回填 §1 表与 §9 判据。**未完成不进入 P3+**。
- [ ] **P0b 构建链**：按 §6 改写 `build/build_apk.ps1`（含 `--java`、重排、版本参数化），并用一个临时 `R.color` 引用验证能编过。**独立提交**。
- [ ] **P1 数据采集**：后台线程一次 `getInstalledPackages(GET_PERMISSIONS | GET_SERVICES)` + 一次 `queryIntentActivities(MAIN/LAUNCHER)`；填充 §5 十字段；隐藏自身；识别 VPN 包；加载期间显示「正在读取应用列表…」。
- [ ] **P2 视图分离**：`items` / `view` + `applyFilter()` 五步流水 + `notifyDataSetChanged()`。
- [ ] **P3 列表复用**：`convertView` + `ViewHolder`；赋值顺序 `setOnCheckedChangeListener(null)` → `setChecked()` → 重挂；监听里从 holder 取 Entry。
- [ ] **P4 搜索框**：`EditText` + `TextWatcher` + 200ms 防抖 + 空态 + 「清除筛选」。
- [ ] **P5 两个 Spinner**：分类（4 项带数量，只受 INTERNET 影响）/ 排序（3 项）。
- [ ] **P6 设置行**：两个 CheckBox + 排序 Spinner + 右对小字；窄屏拆两行（R14）。
- [ ] **P7 状态行**：`已选 N` + `· 分类隐藏 M`（含 0 选兜底文案）；勾选即时刷新。
- [ ] **P8 VPN 灰显**：文字变灰、`setEnabled(false)`、行尾提示；已存 VPN 包强制取消 + Toast。
- [ ] **P9 最近使用**：授权检测 / 对话框 / 降级 / `onResume` 复检 / 30 天窗口。
- [ ] **P10 主题与深色**：`styles.xml` ×2、`colors.xml` ×2、清单 theme、三页取色。
- [ ] **P11 版本号**：清单 `2 / 1.1.0` + 脚本参数化。
- [ ] **P12 保存逻辑**：保留未安装包名（R15）；`changed` 用并集比较。
- [ ] **P13 构建 + 装机**：`-Sign` 通过；`apksigner verify -v` 输出存档（不写死 v2+v3，R17）。
- [ ] **P14 真机验证 + 文档**：跑完 §9；README 补搜索/分类/排序；本文件勾选。

---

## 9. 验证矩阵

| # | 用例 | 判据 |
|---|---|---|
| T1 | 默认进入 | 分类=全部、搜索空、INTERNET 开、置顶开、名称排序；**「全部」数量 = P0 实测的 INTERNET 开时总数** |
| T2 | 切「系统组件」（INTERNET 开） | Spinner 数字 = P0 实测值（**不预设 0**）；列表条数与数字一致 |
| T3 | 关 INTERNET | 「全部」= 487（不含自身）；「系统组件」= P0 实测值；出现 `com.qualcomm.qtil.*` 等 |
| T4 | 搜索 `运动` / `zjwh` | 都命中运动世界 |
| T5 | 搜索乱串 | 空态 +「清除筛选」→ 只清搜索与分类（R13） |
| T6 | 关 INTERNET → 勾一个无网络应用 → 开 INTERNET | 该应用消失；设置行小字 `含 1 个无网络已选`；状态行 `分类隐藏` 不增加 |
| T7 | 勾选后滚动 20 屏 | 无错位、无自动勾选/取消 |
| T8 | 勾选 → 返回 → 重进 | 勾选与「已选 N」一致 |
| T9 | 过滤运行中改选 | 出现 `reload requested`，无 `stop requested`，`vpn up` 重建 |
| T10 | 切「最近使用」（未授权） | 弹说明 → 跳系统设置；返回后自动恢复「最近使用」（R10） |
| T11 | 系统深色模式切换 | 三页底色/文字/弹层跟随，无「深底浅控件」（R2） |
| T12 | 自身包 | `pm list` 有、页面无 |
| T13 | Prefs 含未安装包（卸掉运动世界） | 进页面返回后**不产生** RELOAD；包名仍保留在 Prefs（R15） |
| T14 | Prefs 含 VPN 类包 | 该行灰显不可勾；加载时被强制取消并 Toast 一次（R7） |
| T15 | 冷启动进页面 | 无 ANR；本机加载 < 2s（R6） |

---

## 10. 风险与对策

| 风险 | 对策 |
|---|---|
| 构建链重排后 `aapt2 link` 与 `javac` 产物不同步（改了资源忘重跑） | 脚本单入口全量重建；`[0/8] clean` 清空 `out/` |
| 复用导致复选框错位 | ViewHolder + 监听解绑/重挂固定顺序，T7 专项 |
| 切筛选丢勾选 | `checked` 只写 Entry；`view` 引用子集不复制 |
| `GET_PERMISSIONS` 受限导致 `hasNet` 失真 | 已声明 `QUERY_ALL_PACKAGES`；取不到时默认 `true`（宁可多显示） |
| 无图标用户应用被归入「系统组件」 | 已在 §1 注明范围；不改名时靠搜索兜住 |
| 共享 UID 包权限不体现在自身 `requestedPermissions` | 已知局限，影响面小，不处理（记入 README 已知限制） |
| 300+ 行滚动掉帧 | T15 同测；必要时固定行高 |

---

## 11. 未修订但需知晓的既有行为

| 事项 | 说明 |
|---|---|
| 一个都没勾时的兜底 | `onPause` 会悄悄写入默认目标（运动世界）。页面显示「已选 0」但实际保存 1 个 → 状态行加提示（§3） |
| `sharedUserId` 包 | 权限可能来自共享 UID；VPN 又按 UID 生效，勾一个等于勾整个 UID。不处理 |
| 「系统组件」名字 | 见 §1 命名提示 |

---

## 12. 本轮采纳的 5 项口径变更（原待确认项）

按审查建议采纳，如要改回请指出：

1. **R8**：状态行文案改为「分类隐藏 N 个」；计数优先级 分类 > INTERNET > 搜索；仅被搜索隐藏的不提示。
2. **R9**：Spinner 数量不受搜索影响（只受 INTERNET 影响）。
3. **R13**：「清除筛选」= 清空搜索 + 分类回「全部」，不动 INTERNET / 置顶 / 排序。
4. **R16**：版本号定为 `2 / 1.1.0`，构建脚本参数化。
5. **命名**：「系统组件」保持原名（不改「无图标组件」），范围在 §1 注明。

---

## 13. 影响文件

```
build/build_apk.ps1                              构建链重排 + --java + 版本参数化（P0b）
app/AndroidManifest.xml                          theme=@style/AppTheme、version 2/1.1.0、PACKAGE_USAGE_STATS
app/src/com/zszl/netkit/AppPickerActivity.java   重写（约 +380 / −90）
app/src/com/zszl/netkit/MainActivity.java        取色 + 状态栏条件（约 8 行）
app/src/com/zszl/netkit/ProfilesActivity.java    同上（约 8 行）
app/res/values/colors.xml                        新增
app/res/values/styles.xml                        新增
app/res/values-night/colors.xml                  新增
app/res/values-night/styles.xml                  新增
README.md                                        使用段补搜索/分类/排序；已知限制补 sharedUid/无图标用户应用
docs/PICKER-UI-PLAN.md                           本文件
```

**不动**：`Prefs`、`AdVpnService`、`SkipService`、`Profiles`、`FilterList`、`DnsProxy`、清单资产。

---

## 14. 提交与回滚（R18）

**分 4 个提交**，每步可独立编译：

| # | 内容 | 提交信息 |
|---|---|---|
| C1 | 构建链重排 + `--java` + 版本参数化 | `build: emit R.java (aapt2 link --java) before javac; parameterize version` |
| C2 | 主题/颜色资源 + 三页取色 + 清单 theme | `feat(ui): AppTheme with values-night; status bar follows system dark mode` |
| C3 | `AppPickerActivity` 重写（采集/筛选/搜索/排序/VPN/UsageStats/保存修正） | `feat(picker): search, category filter, sort axes, VPN rows, keep uninstalled pkgs` |
| C4 | README + 本计划勾选 | `docs: picker UI usage + plan progress` |

**回滚**：`git revert C3 C2 C1`（逆序，先撤使用 `R.color` 的提交，再撤生成 `R.java` 的构建链改动，否则中间态编不过）。

---

## 15. 洁净约定

- 构建产物、截图、日志一律在 `build/`（`.gitignore` 白名单，只放行 4 个脚本）。
- 每个 T 用例的截图存 `build/ui-T*.png`，**不入库**。
- 每个提交前 `git status --short` 只允许出现本阶段预期文件；阶段完成后必须为空。
- 不提交 `*.apk` / `*.jks` / 大清单副本 / `_local/` 下任何内容。
