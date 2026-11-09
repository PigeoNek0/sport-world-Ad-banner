# 《应用选择页改造 · 设计计划清单》审查意见

- 审查对象：`docs/PICKER-UI-PLAN.md`（commit `cda0b20` 版本）
- 审查日期：2026-11-09
- 对照代码：`AppPickerActivity.java`、`AdVpnService.java`、`Prefs.java`、`AndroidManifest.xml`、`build/build_apk.ps1`
- 处理方式：技术性问题已直接修订进计划（标 `〔R#〕`）；改变了原 Q1–Q26 决策口径的标 `〔R# 待确认〕`，需 maintainer 复核。

## 结论

方向正确，核心契约（`checked` 唯一数据源、`view` 引用子集、`onPause` 全量保存、RELOAD 不改 STOP、ViewHolder 监听解绑顺序）设计得当。
但有 **3 个阻断级问题**会让实施或验收直接失败（R1 构建链无 R 类、R2 深色模式不生效、R3 验收判据建立在错误假设上），另有若干口径歧义需补齐。

| 级别 | 编号 | 数量 |
|---|---|---|
| 🔴 阻断 | R1–R6 | 6 |
| 🟠 歧义 / 边界 | R7–R13 | 7 |
| 🟡 细节 | R14–R18 | 5 |

---

## 🔴 阻断级

### R1 构建链不生成 `R.java`，P10 无法编译
- **事实**：`build_apk.ps1` 第 1 步就 `javac`，第 4 步才 `aapt2 link`，且 link 未带 `--java`。现有 res 仅被清单引用（`@xml/skip_service`），所以问题未暴露。
- **影响**：P10 在三页 Java 中「取色替换硬编码」需要 `R.color.*`，编译直接失败。原 §9 只提到「确认 aapt2 递归编译」，漏了这一步。
- **修订**：新增 **P0b**——调整为 compile → `link --java gen` → javac（含 gen）→ jar → d8 → base.apk 加 dex；§8 增加对应风险；§9 将 `build_apk.ps1` 列为实改文件。

### R2 只加 `values-night/colors.xml`，深色模式不会生效
- **事实**：清单写死 `Theme.DeviceDefault.Light.NoActionBar`；窗口底色、CheckBox/Spinner 文字、下拉弹层均由主题决定。三页都硬编码 `SYSTEM_UI_FLAG_LIGHT_STATUS_BAR` + `0xFFF2F2F2`。
- **影响**：T11 必然失败，或出现「深色底 + 浅色主题控件」的混搭。
- **修订**：P10 增加 `values/styles.xml` 与 `values-night/styles.xml`（`AppTheme`）。minSdk 为 26，不能用 API 29 才有的 `DeviceDefault.DayNight` 父主题，所以用 night 限定符分别继承 Light / 深色主题。清单 theme 改为 `@style/AppTheme`，状态栏浅色标志按夜间模式条件设置。§9 补齐文件。

### R3 「INTERNET 开时系统组件 = 0」的假设大概率不成立
- **事实**：GMS、GSF、WebView、`com.android.providers.downloads`、厂商推送/定位等无图标包普遍申请 INTERNET。
- **影响**：§3 的示例口径与 T2 判据错误，实施方会以为代码有 bug，或为凑出 0 去改分类逻辑。
- **修订**：新增 **P0 实测口径**，先用 `pm` + `dumpsys` 统计四个分类在开/关 INTERNET 下的数量再回填判据；T2 改为「数量 = 实测值，列表条数与 Spinner 数量一致」。

### R4 T1「全部 488」与 §3 口径自相矛盾
- Spinner 数量按「满足其他筛选后」计算，而 INTERNET 默认开，「全部」不可能是全量。
- 自身包已排除，全量最多 487。
- **修订**：§1 表头改为「不含自身 487」；T1 改为「= P0 实测的 INTERNET 开时总数」；T3 增加「关 INTERNET 时全部 = 487」。

### R5 P1 的 API 组合不成立
- `GET_PERMISSIONS` 是 `getInstalledPackages`/`getPackageInfo` 的 flag，`ApplicationInfo` 上没有 `requestedPermissions`，也没有 `firstInstallTime`。
- 「填充 7 个字段」与 Entry 实际字段数（9 个）不符。
- **修订**：P1 改为一次 `getInstalledPackages(GET_PERMISSIONS | GET_SERVICES)`，`PackageInfo` 同时提供 applicationInfo / 权限 / services / 安装时间；字段注释按来源重写。

### R6 主线程一次性加载有 ANR 风险
- 逐包 `getLaunchIntentForPackage` 内部是 1–2 次 `queryIntentActivities`，488 包约千次 binder 调用；再加上 488 次 `getApplicationLabel`，还会带上 `GET_SERVICES` 的大量数据。
- **修订**：LAUNCHER 判定改为**一次** `queryIntentActivities(MAIN+LAUNCHER)` 取集合；采集放后台线程并显示加载态；新增 T15（无 ANR、本机 < 2s）。

---

## 🟠 歧义 / 边界

### R7 VPN 判定不稳，且已选 VPN 包会卡死
- 原 §1 用隐式 `queryIntentServices("android.net.VpnService")`，§5 注释又写「出现在其 services 里」，两处不一致。隐式查询在部分版本/ROM 上会滤掉其他应用的 `exported=false` 服务（NetKit 自己就是 `exported=false`），会漏判。
- 原文「比读 requestedPermissions 可靠」的对比不成立：`BIND_VPN_SERVICE` 是服务的保护权限，本来就不会出现在 requestedPermissions 里。
- 缺规则：Prefs 中已存的 VPN 类包被灰显后，用户无法取消勾选。
- **修订**：改用 `ServiceInfo.permission == BIND_VPN_SERVICE` 判定；加载时强制取消并一次性提示；新增 T14。

### R8 「隐藏 N 个」口径不完整〔待确认〕
- 被搜索隐藏的已选项、同时被分类和 INTERNET 筛掉的已选项，原文均未定义归属。
- 状态行文案「当前筛选隐藏」会被理解为「所有筛选」，与「仅分类」口径不符。
- 原 T6 中，INTERNET 默认开时根本看不到无网络应用，无法勾选，用例不可执行。
- **修订**：定优先级「分类 > INTERNET > 搜索」，每个已选项只计一处，仅被搜索隐藏的不提示；文案改为「分类隐藏 N 个」；T6 补全操作步骤。
- **需确认**：文案改动及「仅搜索隐藏不提示」是否接受。

### R9 Spinner 数量是否受搜索影响未定义〔待确认〕
- **修订建议**：不受搜索影响，避免打字时数量跳动；仅受 INTERNET 开关影响。

### R10 「最近使用」交互不可实现，且缺回流处理
- Spinner 下拉项内做不出「行右侧可点击」的区域，点击即选中该项。
- 从设置页返回走 `onResume`，原文没有复检，授权回来后排序仍停在降级状态。
- 跳设置页会触发 `onPause` 保存，勾选有变化时会顺带 RELOAD 一次；行为可接受，但应写明。
- UsageStats 查询时间窗未定义。
- **修订**：选中时检测，未授权弹对话框并降级；`onResume` 复检并恢复；时间窗定为近 30 天的 `lastTimeUsed`；T10 增加「授权后返回生效」。

### R11 勾选后计数刷新缺失
- 「勾选不触发重建」是对的，但状态行和设置行小字依赖已选数，必须在监听里同步刷新。已补入 §4 与 P7。
- 现有代码在 lambda 里捕获 `e`，改 ViewHolder 后必须从 holder 取当前 Entry。已补入 §5。

### R12 中文名排序
- 现有代码用 `compareToIgnoreCase`，按码点排序，中文名顺序看起来是乱的。
- **修订**：改用 `Collator.getInstance(Locale.CHINA)`，排序键预计算；搜索的大小写转换统一用 `Locale.ROOT`。

### R13 「清除筛选」范围未定义〔待确认〕
- **修订建议**：只清空搜索、把分类重置为「全部」；INTERNET、置顶、排序属于偏好设置，不动。

---

## 🟡 细节

### R14 窄屏布局
设置行（两个中文 CheckBox + Spinner）在 360dp 宽的屏幕上可能放不下。已补入「放不下时拆两行、不截断」；文案保持原样。

### R15 Prefs 中未安装的包每次都会误触发 RELOAD
- 现状：`onPause` 只根据已安装列表 `items` 重建集合，与 Prefs 比较时，只要 Prefs 含未安装包（比如运动世界被卸载过）就判为 changed，用户每进一次页面就 RELOAD 一次，并悄悄丢掉这些包名。
- `AdVpnService.startVpn` 已逐个跳过未安装包，所以保留它们是安全的。
- **修订**：保存时原样保留 Prefs 中未安装的包名；新增 T13。这一条属于现有问题，借这次改造一并修掉。

### R16 版本号未规划〔待确认〕
清单与 `build_apk.ps1` 两处写死 `versionCode 1 / 1.0.0`，下一版必须同时改。建议 `2 / 1.1.0`，构建脚本改为参数化或从清单读取。

### R17 签名描述与脚本不符
脚本显式设置的是 v1 + v2，v3 只是 apksigner 的默认行为。P11 已改为按 `apksigner verify` 的实际输出验收。

### R18 回滚方案不成立
提交之后 `git checkout -- <path>` 无法回到旧版本，也删不掉新增的 res 文件；而且改动范围远不止 `AppPickerActivity` 和 `res`（还有 Main、Profiles、清单、构建脚本）。
- **修订**：按阶段分提交，用 `git revert` 回滚，并注明构建链与 `R.color` 使用方之间的回滚顺序。

---

## 未修订、仅提示的事项

| 事项 | 说明 |
|---|---|
| 「系统组件」命名 | 无启动图标的**用户**应用（输入法、插件）也会归入这一类，名称不准确。可以考虑改叫「无图标组件」。属于 Q 决策，未改，只在 §1 判据里注明了范围 |
| `sharedUserId` 包 | 共享 UID 的包可能没申请 INTERNET 却实际有网络（权限来自共享 UID），会被 INTERNET 开关隐藏；而且 VPN 按 UID 生效，勾一个就等于勾了同 UID 的全部。影响面小，暂不处理 |
| 「清空勾选」兜底 | `onPause` 在一个都没勾时会悄悄写入默认目标，页面显示「已选 0」，但实际保存了 1 个。这是现有行为，建议在状态行加提示「未选任何应用时默认净化运动世界」 |
| 原 §8 「必要时改 holder + 固定行高」 | 与 P3 重复，已在修订版中删除 |

## 待 maintainer 确认清单

1. R8：状态行文案改为「分类隐藏」，计数优先级为分类 > INTERNET > 搜索，仅被搜索隐藏的已选项不提示。
2. R9：Spinner 数量不受搜索影响。
3. R13：「清除筛选」只清空搜索并把分类重置为「全部」。
4. R16：版本号定为 `2 / 1.1.0`，构建脚本参数化。
5. （可选）「系统组件」是否改名。
