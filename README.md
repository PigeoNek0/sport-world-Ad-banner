# NetKit · 网络工具箱

> **一个为「运动世界」做的去广告工具(受不了广告世界了)**：用「本机 VPN 只做 DNS 过滤」拦掉它的广告域名，用「无障碍服务」自动跳过它的开屏 / 插屏广告。
> 因为实现方式是通用的，所以也能用于其它 App；但内置清单、跳过规则、默认目标全部是围绕运动世界调优的。
> 全部逻辑在本机完成，没有远程服务、没有遥测、不改动任何第三方 App。

```
包名       com.zszl.netkit
默认目标   com.zjwh.android_wh_physicalfitness（运动世界 / 校园跑）
minSdk 26 (Android 8.0)   targetSdk 36 (Android 16)
语言       Java 8，零第三方依赖，约 130 KB 代码
许可       MIT
```

---

## 为什么做这个

「运动世界」这类校园跑 App 里塞了**八家广告聚合**（穿山甲 / GroMore、广点通、快手、百度、京东、天目、adgain、yfanads、华为），开屏、插屏、信息流、激励视频全都有，跑一次步要关好几次广告。

市面上的广告拦截工具要么需要 root，要么依赖境外 DNS（在国内不可用），要么拦不住「素材随业务接口下发、App 自己渲染」的开屏。所以这个项目只为这一件事：**在不 root、不碰目标 App 一行代码的前提下，把它的广告压下去**。

- 拦截范围默认只勾选运动世界，**其它 App 完全不受影响**；
- 内置核心清单来自对它实际联网域名的长期观察（`api-access.pangolin-sdk-toutiao*.com`、`win.gdt.qq.com`、`tracker.yfanads.com`、`sdk.tianmu.mobi`、`xadsmartget.adgain.cn` 等）；
- 跳过规则按它的开屏版式调优（右上角「跳过 3」、底部「上滑或点击」、「应用名称：应用商店…」等信息栏）；
- 坐标学习只记录你在它上面手动关广告的位置。

> 想用到别的 App 也行：在「选择要净化的应用」里勾选即可，但清单与规则需要自己补。

## 它解决什么问题

移动端广告有两条路可走：

| 广告形态 | 拦截手段 | 本项目的做法 |
|---|---|---|
| 走网络的横幅 / 插屏 / 激励视频 | 在 DNS 层把广告域解析结果改成 `0.0.0.0` | **VpnService + 本地 DNS 代理**，按应用生效（per-app） |
| 素材随业务接口下发、App 自己渲染的开屏 / 插屏 | 只能点掉 | **AccessibilityService** 按「文字 / id / 描述」规则点击，并用「坐标学习」加速同版式 |

两条腿互补：DNS 拦掉大部分 SDK 请求，无障碍兜住剩下的自渲染广告。

## 特性

- **默认就为运动世界服务**：装好后目标应用已经勾好（`com.zjwh.android_wh_physicalfitness`），开启过滤即可生效，不需要先做任何配置。
- **仅本机 VPN 做 DNS 过滤**：不转发任何流量，只劫持 53/UDP 的 DNS 请求；未命中的查询转发到国内公共 DNS（阿里 / 腾讯）。
- **按应用生效**：只对勾选的应用启用，其他 App 完全不受影响（系统只允许一个 VPN，这是硬约束）。
- **可插拔清单**：内置核心清单（约 30 条精选广告域，全部来自运动世界实测流量）、可选大清单（32 万条，自带误伤保护，默认关闭），支持导入自定义 `.txt`、在线编辑规则。
- **开屏自动跳过**：针对运动世界的开屏做了预置热区（右上角「跳过」），并兼容通用规则语义（`|包含` / `=相等` / `+前缀` / 资源 id 后缀 / contentDescription）。
- **坐标学习 + 人工复核**：把「在你手机上手点确实关掉了广告」的位置记下来，下次同版式直接命中；样本默认「待复核」，需你确认才生效，可逐条启用 / 停用 / 删除。
- **跳转判负**：点击后若真的跳到了别的 App（京东 / 淘宝 / 应用商店），该样本自动停用，避免越学越歪。
- **竖条带扫描**：同一个开屏的「跳过」会随「有无通知横幅 / 素材比例」纵向漂移（实测同素材会从 y≈0.157 漂到 y≈0.206），因此在锚点上下自动换点重试。
- **好用的应用选择页**（1.1.0）：按名称 / 包名搜索，按「外部应用 / 系统应用 / 系统组件」分类并显示数量，可只看有网络权限的应用，支持按名称 / 安装时间 / 最近使用排序，已选的排在最前；VPN 类应用灰显、不可勾选。
- **跟随系统深色模式**（1.1.0）。
- **零网络权限滥用**：联网权限只有 `INTERNET`（用于向 DNS 上游发查询）和 `ACCESS_NETWORK_STATE`。

## 工作原理

```
                 ┌──────────────────────────────┐
   App 的 DNS ──▶│  VpnService (tun)            │
                 │   └─ DnsProxy: 解析 53/UDP    │
                 │        ├─ 命中清单 → 回 0.0.0.0│
                 │        └─ 未命中 → 转发上游    │
                 └──────────────────────────────┘

   前台窗口 ──▶ AccessibilityService (SkipService)
                 ├─ 文本 / id / desc 规则命中 → 点击
                 ├─ 坐标档案命中 → 点击 + 竖条带重试
                 └─ 点击后 700ms 复核 → 成功才沉淀样本
```

坐标学习与规则的实现细节写在 [`docs/SKIP_SERVICE_NOTE.md`](docs/SKIP_SERVICE_NOTE.md)；真机实测数据在 [`docs/TEST-2026-11-03-band-scan.md`](docs/TEST-2026-11-03-band-scan.md)。

## 目录结构

```
app/
  AndroidManifest.xml
  src/com/zszl/netkit/
    AdVpnService.java       VpnService：建 tun、per-app 允许清单、前台通知
    DnsProxy.java           tun 读写 + DNS 报文解析 + 伪造应答 + 异步转发
    FilterList.java         清单装载（assets / 导入文件 / 自定义规则）+ 父域上溯匹配
    Prefs.java              设置项
    Stats.java              拦截计数 + Top10
    SkipService.java        无障碍跳过：规则引擎、坐标学习、条带重试、跳转判负
    Profiles.java           坐标档案（三态：待复核 / 生效 / 停用）+ 原子写
    ProfilesActivity.java   复核页
    AppPickerActivity.java  选择要净化的应用（搜索 / 分类 / 排序 / 后台加载）
    MainActivity.java       设置主页
    Ui.java                 深浅色适配助手（状态栏、取色）
    BootReceiver.java       开机自启
  assets/
    blocklist_core.txt      核心广告域（精挑，默认启用）
    blocklist_extra.txt     统计 / 推送上报域（可选）
    blocklist_big.txt       32 万条大清单（可选，含误伤保护）
    skip_rules.json         开屏 / 插屏跳过规则
  res/xml/skip_service.xml  无障碍服务声明
  res/values(-night)/       浅色 / 深色配色与 AppTheme
build/
  build_apk.ps1             一条命令出包（aapt2 生成 R.java → javac → d8 → zipalign → apksigner）
  adddex.py                 往 APK 里追加第二个 dex
  install_apk.ps1           安装（自动点掉 MIUI 的 USB 安装弹窗）
  mk_big.py                 生成大清单（从上游清单裁剪 + 误伤保护）
docs/
  SKIP_SERVICE_NOTE.md      坐标学习 / 规则格式
  PLAN.md                   开发计划与待办
  PICKER-UI-PLAN.md         选择页改造计划（1.1.0）及审查意见 PICKER-UI-PLAN-REVIEW.md
  HANDOVER-*.md             每轮交接文档
  TEST-2026-11-03-band-scan.md  真机实测报告
```

## 构建

不使用 Gradle，直接调 Android SDK 命令行工具，方便在任意机器复现。

**需要**：JDK 11+、Android SDK `build-tools`（含 `aapt2` / `d8` / `zipalign` / `apksigner`）、`platforms/android-36/android.jar`。

```powershell
# 目录约定（可用参数覆盖）
#   tools/sdk/android-15/   build-tools 解包目录
#   tools/sdk/android-36/   platform 解包目录（android.jar）

powershell -NoProfile -ExecutionPolicy Bypass -File build\build_apk.ps1 -JavaHome "<JDK 根目录>"
# 产物（未签名）：build/netkit/aligned.apk
```

签名（自己生成密钥，密钥不要入库）：

```powershell
keytool -genkeypair -keystore my.jks -alias mykey -keyalg RSA -keysize 2048 -validity 10000
powershell -NoProfile -ExecutionPolicy Bypass -File build\build_apk.ps1 -JavaHome "<JDK 根目录>" `
  -Sign -Ks my.jks -KsAlias mykey -KsPass <password>
# 产物：build/netkit/NetKit-signed.apk
```

版本号以 `app/AndroidManifest.xml` 的 `versionCode` / `versionName` 为准。

安装：

```powershell
powershell -File build\install_apk.ps1 -Apk build\netkit\NetKit-signed.apk
```

## 使用

1. 打开「网络工具箱」→ **开启过滤** → 允许系统 VPN 授权；
2. 目标应用**默认已经勾选运动世界**（`com.zjwh.android_wh_physicalfitness`），不用改；要加别的 App 就点「选择要净化的应用」，可以搜索名称或包名、按分类筛选、切换排序；返回即生效（过滤运行中会原地重建，不需要手动重开）；
   「按最近使用」排序需要在系统设置里授予「使用情况访问权限」，未授权时自动改按安装时间排序；
3. 需要更狠就打开 **启用大清单（32 万条）**，注意可能误伤业务；
4. 想跳过开屏就打开 **自动跳过开屏 / 插屏**，并按提示授予无障碍权限；
5. 学习到的坐标在 **管理坐标学习（人工复核）** 里逐条核对，默认「待复核」，点「启用」才生效。

装好后正常跑一次「运动世界」，能观察到的变化：

| 位置 | 用前 | 用后（大清单 + 跳过开启） |
|---|---|---|
| 冷启动开屏 | 小米应用商店 / 天猫 / 京东 / 淘宝视频广告 | 多数直接进首页；偶发命中时被规则或预置热区点掉 |
| 首页插屏 | 京东半屏、麦珂娜半屏、美团酒店 | 触发即被关掉 |
| 信息流 | 保留（属业务内容，不做处理） | 保留 |
| 校园跑页插屏 | 淘宝 / 京东全屏视频 | 多数不出现 |

> ⚠️ 每次覆盖安装 APK，系统都会清空无障碍绑定，需要重新授权一次（设置页会出现「重新授权无障碍」按钮）。

## 清单与规则格式

自定义清单（导入 `.txt` 或「编辑自定义规则」）支持：

```
ad.example.com                 命中该域及其子域
0.0.0.0 ad.example.com         hosts 写法
>ad.example.com 0.0.0.0        IP 映射写法
```

跳过规则 `app/assets/skip_rules.json`（内置的那份是围绕运动世界的广告版式写的）：

```json
[
  { "id": "|跳过",              "action": "TAP_AT:0.887,0.157", "times": 3 },
  { "id": "inter-close-button", "action": "TAP",               "times": 2 },
  { "id": "|关闭广告",           "action": "BACK",              "times": 2 }
]
```

`id` 语义：`|` 文本包含、`=` 文本相等、`+` 前缀、无前缀按资源 id 后缀匹配；没有 id 时按 contentDescription 匹配。
`action`：`TAP`（点节点）、`BACK`、`TAP_AT:x,y`（0–1 相对屏幕坐标）。

## 已知限制

- **自渲染广告拦不掉**：素材随业务 API 下发、由 App 自己画出来的广告，DNS 层无能为力，只能靠无障碍点掉。
- **同一时间只能一个 VPN**：与其它 VPN / 代理类 App 互斥。
- **开屏是概率出现**：开着大清单时，多数开屏因为素材请求被拦而根本不出现——这时"没有可跳过的东西"。
- **不同厂商版式差异大**：运动世界的开屏至少见过「右上角跳过」与「左上角 4S|跳过」两种，本工具默认覆盖右上为主，其余靠坐标学习补。
- **换 App 效果打折**：清单与规则是为运动世界调的，勾选别的 App 需要自己补清单与规则。
- **网络运营商 DNS 劫持**：部分地区明文 DNS 被劫持会返回假地址，此时过滤效果依赖清单是否覆盖到被劫持后的域名。

## 隐私

- 不收集、不上传任何数据；无第三方 SDK、无统计、无广告。
- DNS 查询只在「本机 → 你配置的上游」之间传递，不经任何中转服务。
- 无障碍服务只读取前台窗口的控件树用于匹配广告关闭按钮，不做录屏、不存文本内容。
- 「使用情况访问权限」（可选，需手动授予）只用于在选择页本机按最近使用排序，不保存、不上传。
- 读取已安装应用列表（`QUERY_ALL_PACKAGES`）只用于选择页展示与分类。

## 路线图

见 [`docs/PLAN.md`](docs/PLAN.md)。近期重点：坐标档案单写者重构、跳转判负加白名单、条带下界放宽、清单加载挪出主线程。

## 许可证

[MIT](LICENSE)
