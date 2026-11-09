# 交接文档：选择页 UI 改造（2026-11-09）

本文只覆盖这一轮更新。项目整体背景见 `README.md`；更早的历史在 `_local/HANDOVER*.md`（未入库）。

---

## 0. 一句话

这一轮把「选择要净化的应用」从「一屏平铺 484 个复选框」改造成**可搜索 / 可分类 / 可排序**的列表，
同时补齐了两项工程基建：**构建链现在能产出 `R.java`（资源引用真正可用）**、**全应用跟随系统深色模式**。
APK 版本从 `1 / 1.0.0` 升到 **`2 / 1.1.0`**。

---

## 1. 提交链（按顺序）

| 提交 | 内容 |
|---|---|
| `2cdb9eb` | 文档：`docs/PICKER-UI-PLAN.md` v2（吸收审查 R1–R18） |
| `53a1652` | 文档：P0 前置实测数字回填 |
| `7f6db2b` | 构建链：`aapt2 link --java` 产出 `R.java`；版本参数化 |
| `3fdf9fd` | 主题：`values` / `values-night` 双份配色 + `AppTheme` + `Ui` 助手 |
| `9ec4541` | 功能：选择页重写（搜索 / 分类计数 / 开关 / 排序 / 置顶 / 行复用 / 后台加载 / VPN 灰显） |
| `0694336` | 功能：空状态一键复位 + 用量权限引导（无权限自动降级为按安装时间） |

APK：`build/netkit/NetKit-signed.apk`，2,372,336 B，`versionCode=2 / versionName=1.1.0`，
`apksigner verify` → **v1=false, v2=true, v3=true**（minSdk 26 下正常）。

---

## 2. 构建链变更（最容易踩坑的一处）

改之前：`javac` 在 `aapt2 link` **之前**跑，且 link 没有 `--java`，所以项目里**根本不存在 `R.java`** —— 任何 `R.color.*` 都编不过。

现在的 8 步（`build/build_apk.ps1`）：

```
[1/8] aapt2 compile --dir app/res -o build/netkit/res.zip
[2/8] aapt2 link res.zip --manifest app/AndroidManifest.xml -I android.jar \
        --java build/netkit/gen --version-code <N> --version-name <V> -o base.apk
[3/8] javac -source 8 -target 8 -classpath android.jar -d classes \
        (app/src/**/*.java) build/netkit/gen/com/zszl/netkit/R.java
[4/8] jar cf classes.jar -C classes .
[5/8] d8 classes.jar --lib android.jar --min-api 26 --output dexout
[6/8] 追加 classes.dex 进 base.apk（build/adddex.py）
[7/8] zipalign -p -f 4
[8/8] apksigner sign（默认方案，不再写死 --v1/--v2）
```

要点：

- **JDK 不在 PATH 上时**，必须显式传：`-JavaHome "<JDK 根目录>"`（JDK 11+）；
- **版本号以 `AndroidManifest.xml` 为准**（`versionCode="2" versionName="1.1.0"`）；aapt2 的 `--version-code/--version-name` CLI 覆盖实测**不生效**；
- 产物校验：
  ```
  tools\sdk\android-15\aapt2.exe dump badging build\netkit\NetKit-signed.apk | findstr "package: name"
  ```
- 签名用**自己的** keystore（不入库，放 `_local/` 下）；密码不要写进任何入库文件。

完整构建命令：

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File build\build_apk.ps1 `
  -JavaHome "<JDK 根目录>" `
  -Sign -Ks "<keystore.jks>" -KsAlias <alias> -KsPass <password>
```

---

## 3. 主题与配色

新增文件：

```
app/res/values/colors.xml        bg/surface/text_primary/text_secondary/text_disabled/line（浅色）
app/res/values-night/colors.xml  同键名（深色：#121212 底 / #EDEDED 字）
app/res/values/styles.xml        AppTheme ← Theme.DeviceDefault.Light.NoActionBar + windowBackground=@color/bg
app/res/values-night/styles.xml  AppTheme ← Theme.DeviceDefault.NoActionBar（深色控件）
app/src/com/zszl/netkit/Ui.java  isNight / applyStatusBar / c / alpha
```

`AndroidManifest.xml` 的 `android:theme` 从写死的 `@android:style/...` 改为 `@style/AppTheme`。
三个页面里原来的 `setSystemUiVisibility(LIGHT_STATUS_BAR)` + `setStatusBarColor(0xFFF2F2F2)` 全部替换为 `Ui.applyStatusBar(this)`；
`ProfilesActivity` 里的 `Color.BLACK / DKGRAY / RED` 换成 `Ui.c(this, R.color.text_*)`。

> minSdk 26 用不了 `Theme.DeviceDefault.DayNight`（API 29+），所以浅/深各写一份靠 `values-night` 切换。

实测：`adb shell cmd uimode night yes|no|auto` 切换后，主界面 / 坐标学习页 / 选择页均正确（深色下不再黑字黑底）。

---

## 4. 选择页实现细节（`app/src/com/zszl/netkit/AppPickerActivity.java`，615 行）

### 4.1 分类口径（只按「有没有启动图标」判）

| 分类 | 判定 | 实测（只看有网络=开） |
|---|---|---|
| 外部应用 | 非系统 + 有 LAUNCHER | 101 |
| 系统应用 | 系统 + 有 LAUNCHER | 8 |
| 系统组件 | 无 LAUNCHER | 149 |
| 全部 | 上述之和 | **259** |

LAUNCHER 集合来自 `queryIntentActivities(ACTION_MAIN + CATEGORY_LAUNCHER, MATCH_ALL)`；
自身包名 `com.zszl.netkit` 从列表里剔除。

### 4.2 数据采集

- `getInstalledApplications(0)` + 每个包 `getPackageInfo(pkg, GET_PERMISSIONS | GET_SERVICES)`：
  - `hasNet` ← `requestedPermissions` 含 `android.permission.INTERNET`
  - `vpn` ← `services[].permission == "android.permission.BIND_VPN_SERVICE"`
  - `firstInstall` ← `firstInstallTime`
- **全部在后台线程**（`new Thread(..., "netkit-picker-load")`）执行，完成后 post 回主线程；期间显示「正在读取应用列表…」。
- 可选：`UsageStatsManager.queryAndAggregateUsageStats(7 天)` 取 `lastUsed`（需 `PACKAGE_USAGE_STATS`）。

### 4.3 筛选 / 计数顺序

1. 先算**分类计数**：应用「只看有网络」开关，但**不受搜索影响**（Q19/Q20 口径）；
2. 再算列表：`只看有网络` → `分类` → `搜索`（显示名或包名，均转小写做 contains）；
3. 统计行：`已选 N 个（含 M 个无网络）· 当前筛选隐藏了 K 个已选 · 显示 X 个`。

### 4.4 排序 / 置顶

- 排序轴：按名称（`Collator.getInstance(Locale.CHINA)`，中文正确排序）/ 按安装时间 / 按最近使用；
- **「按最近使用」无授权时自动降级为按安装时间**，并显示授权引导行；
- 「已选置顶」默认开：勾选的排在前面，其余按当前排序轴；
- 三个选择（排序、只看有网络、已选置顶）持久化在 `SharedPreferences("picker_prefs")`；
  搜索词与分类**每次进页面重置**（Q18）。

### 4.5 行渲染与交互

- `convertView` 复用：首次 inflate 时把 `CheckBox` + 副标题 `TextView` 存进 `row.setTag(...)`，复用只改文案与状态；
- 副标题拼接：`包名` + `· 默认目标`（运动世界）+ `· 无网络权限` + `· VPN 类，不能同时启用`；
- **VPN 类行灰显且不可勾选**（避免抢占 VPN）；
- 勾选变化立即 `refresh()`（计数与置顶实时更新）。

### 4.6 空状态与复位

无结果时隐藏列表、显示两行提示，**点击即复位**为默认筛选（空搜索 + 全部 + 只看有网络开）。
提示贴顶 48dp —— 居中会被输入法挡住（已实测踩到）。

### 4.7 保存语义

`onPause()` 时：

```java
// 保留磁盘上「已安装但本次未列出」的旧条目，避免误丢
for (String p : Prefs.packages(this)) if (!installed.contains(p)) out.add(p);
for (Item it : all) if (it.checked && !it.vpn) out.add(it.pkg);
if (out.isEmpty()) out.add(Prefs.DEFAULT_TARGET);
```

- 目标集合变化且过滤正在运行 → 发 `AdVpnService.ACTION_RELOAD` **原地重建**（不是 STOP，STOP 会清 `Prefs.enabled` 且不再拉起）；
- 日志：`NetKitPick: onPause, changed=… running=… size=…`。

---

## 5. 已实测（真机 24129PN74C / Android 16 / SDK 36）

| 用例 | 结果 |
|---|---|
| 打开选择页 | `全部 259`；分类下拉展开为 `全部 259 / 外部应用 101 / 系统应用 8 / 系统组件 149`（101+8+149=259 ✓） |
| 默认状态 | 只看有网络=开、按名称、已选置顶=开；首行是「运动世界」并标「默认目标」 |
| 搜索 `baidu` | `显示 5 个`，并提示「当前筛选隐藏了 1 个已选」 |
| 搜索 `zzzzzz` | `显示 0 个` + 空态提示；点空态 → 恢复 `显示 259 个` |
| 排序切「按最近使用」 | 出现授权引导行，排序降级为安装时间（顺序稳定） |
| 深色模式 | `cmd uimode night yes` 后三页均正确换色 |
| VPN 行 | 「白鲸加速器」灰显 + 「VPN 类，不能同时启用」 |

---

## 6. 已知问题 / 遗留

1. **测试残留勾选**：这几轮用坐标点选做自动化验证时误勾了 `安全服务 / 高德地图 / 肯德基` 等，当前目标集合不是只有运动世界。
   处理方式：打开选择页取消勾选即可（或用下一步要加的「恢复默认目标」按钮）。
2. **`AppPickerActivity` 是 `exported=false`**：`adb shell am start -n com.zszl.netkit/.AppPickerActivity` 起不来，
   自动化只能从主界面点「选择要净化的应用」进入。
3. **按最近使用需要授权**：未授权时该排序轴不可用（已降级 + 引导，但不会自动请求）。
   发版前修复：清单补声明 `PACKAGE_USAGE_STATS`（此前未声明，系统「使用情况访问」列表里根本没有本 App，引导无效）；
   授权后返回页面会在后台补读用量数据并重排（此前只弹 Toast、`lastUsed` 全为 0）。
4. **`build/` 下临时产物**（`build/netkit/gen`、dump 的 xml/txt）都在 `.gitignore` 白名单外，不入库。
5. **PICKER-UI-PLAN 的 T1–T15 验证矩阵尚未逐条跑**，只跑了本文第 5 节这些。

---

## 7. 下一步建议

1. 加「**恢复默认目标**」按钮（一键只选运动世界），顺手解决第 6.1 条；
2. 按 `docs/PICKER-UI-PLAN.md` §13 的 T1–T15 逐条验收并勾选 checklist；
3. 验收后再决定是否把 `versionCode` 升到 3 发一版；
4. 后端（净化的主体逻辑）待办仍在旧计划里：P1 单写者重构、跳转判负两类假阳性、四条红线复测。

---

## 8. 复现与调试命令

```powershell
# 构建 + 安装
powershell -NoProfile -ExecutionPolicy Bypass -File build\build_apk.ps1 `
  -JavaHome "<JDK 根目录>" `
  -Sign -Ks "<keystore.jks>" -KsAlias <alias> -KsPass <password>
adb install -r -d build\netkit\NetKit-signed.apk

# 按文本定位控件（比硬编码坐标可靠）
adb shell uiautomator dump /sdcard/u.xml; adb pull /sdcard/u.xml .
# 然后从 u.xml 里取 bounds="[l,t][r,b]"，用 ((l+r)/2, (t+b)/2) 点

# 深色模式验证
adb shell cmd uimode night yes | no | auto

# 选目标后的服务日志
adb logcat -d -s NetKitPick:* NetKitVpn:* | Select-String 'onPause|per-app|vpn up'
```

> 教训：**UI 加了顶部内边距后，之前硬编码的 tap 坐标会整体失效**，自动化请改按控件文本/bounds 定位。
