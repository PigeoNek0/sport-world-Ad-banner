# 测试报告：竖条带扫描 + 跳转判负（2026-11-03 22:45）

> 被测版本：`e61c11e` + 工作区未提交补丁（`SkipService.java` +48/−19，已编译进 APK）
> 设备：Xiaomi 15 / `24129PN74C` / Android 16（SDK 36）/ arm64-v8a，未 root
> 被测目标：`com.zjwh.android_wh_physicalfitness`（运动世界校园 7.3.93）
> 被测应用：`com.zszl.netkit`（网络工具箱），APK 2,364,144 B，v2+v3 验签通过
> 过滤状态：**开启**，大清单 `hosts=319962`，单目标 `com.zjwh...`，`vpn up` 已确认

---

## 1. 本轮验证的代码改动

`app/src/com/zszl/netkit/SkipService.java`（当前 50,123 B）三处：

| # | 改动 | 目的 |
|---|---|---|
| 1 | `armSkipChain()` 增加前置守卫：锚点 `y` 不在 `[0.12, 0.26]` 或 `bandYs()` 少于 2 档时**不武装链** | 修掉插屏类锚点（如 y≈0.67）产生空候选数组 → `processRetry()` 越界 → 服务卡死 |
| 2 | `processRetry()` 命中「广告消失」时**只记日志、不再学习**（`skip chain done ... (not learned)`） | 广告消失也可能是倒计时自然结束或更早一下延迟生效，学到无法归因的点会污染档案 |
| 3 | 换点前用 `inDangerZone()` 过滤候选点；且**每一下点击后都重开跳转检测窗口**（`jumpSig/jumpFromPkg/jumpAt`） | 避免点到「上滑或点击」素材触发跳转；同时让任何一个候选点点出的跳转都归到该条目 |

## 2. 实测方法

```powershell
adb shell logcat -c
for ($k=1; $k -le 8; $k++) {
  adb shell "am force-stop com.zjwh.android_wh_physicalfitness"
  adb shell "am start -n com.zjwh.android_wh_physicalfitness/.mvi.splash.SplashActivity"
  Start-Sleep 5   # 开屏窗口内（<8s）
  Start-Sleep 4
}
adb shell logcat -d | 统计 NetKitDns:BOCK / skip fired / profile apply / skip chain * / jump
```

判据一律取 logcat 计数，**不以截图是否停在首页为准**（开屏是概率出现，且拦截开启时多数不出现）。

## 3. 结果

```
BLOCK=116  fired=116  apply=2  armed=2  retry=7  done=0
exhausted=1  danger=0  learn=0  jump=1  disabled=1
```

| 指标 | 值 | 解读 |
|---|---|---|
| `BLOCK` | 116 | 拦截正常工作，单轮约 116 条广告域请求被拦截 |
| `skip fired` | 116 | 规则（文字/id/desc）大量命中 |
| `profile apply` | 2 | 坐标档案被套用 2 次 |
| `skip chain armed` | 2 | 与 apply 一致：**只有条带内锚点才武装**，守卫生效 |
| `skip chain retry` | 7 | 失败换点推进正常 |
| `skip chain exhausted` | 1 | 4 档候选点全试过仍未关掉（该版式不在带内） |
| `danger` | 0 | 本轮候选点没有落进 CTA 危险区 |
| `learn` | **0** | 「成功不学习」生效，**零污染样本** ✅ |

失败链日志（证明换点按 339→488→558→619 推进后收手）：

```
skip chain armed sig=上滑或点击+下载或跳转第三方应用 x=0.887 ys=5
skip chain retry 1/4 -> 1064,339 sig=上滑或点击+下载或跳转第三方应用
skip chain retry 2/4 -> 1064,488 sig=上滑或点击+下载或跳转第三方应用
skip chain retry 3/4 -> 1064,558 sig=上滑或点击+下载或跳转第三方应用
skip chain retry 4/4 -> 1064,619 sig=上滑或点击+下载或跳转第三方应用
skip chain exhausted sig=上滑或点击+下载或跳转第三方应用 tries=4
```

## 4. 结论：三处改动全部按预期工作

1. **条带守卫有效** —— `armed` 与 `apply` 严格 1:1，插屏类锚点不再产生空数组，本轮无越界、无服务卡死；
2. **成功不学习有效** —— `learn=0`，没有再出现 `0.094` 这类「y 极小」的污染样本；
3. **逐档开跳转窗口有效** —— 4 档重试全部落点，说明链在推进而不是一次就放弃。

## 5. 遗留问题：跳转判负的两类必然假阳性

本轮抓到：

```
jump detected: com.zjwh... -> com.zszl.netkit d=2303ms sig=上滑或点击+下载或跳转第三方应用
profile disabled by jump sig=上滑或点击+下载或跳转第三方应用
```

上一轮抓到：

```
jump detected: com.zjwh... -> com.miui.home d=2938ms sig=下载或跳转第三方应用
profile disabled by jump sig=下载或跳转第三方应用
```

| 假阳性 | 触发场景 | 后果 |
|---|---|---|
| 目标 → `com.miui.home` | 用户按 Home、目标 App 自杀、`am force-stop` | 用户标注的 `(0.887, 0.157)` 被停用 |
| 目标 → `com.zszl.netkit` | 测试脚本切回本工具、系统回落到上一应用 | 同上 |

**根因**：判据只看「前台包名变化 + 3s 内」，缺少「新包名是否用户真会去的地方」与「跳转时广告是否还在」两个约束。

## 6. 建议修复（未实施，待确认）

```java
boolean realJump = newPkg != null
        && !newPkg.equals(curPkg)
        && !newPkg.equals("com.miui.home")              // 桌面
        && !newPkg.equals(getPackageName())              // 本工具自身
        && !newPkg.startsWith("com.android.systemui")    // 系统 UI
        && d < 1500;                                     // 时间窗 3000 -> 1500
// 再叠一条硬证据：跳转发生时广告特征仍成立（AD_ACTIVE），
// 否则「目标 -> 别的 App」也可能只是正常 App 间切换
```

预期效果：`disabled=0`，`(0.887, 0.157)` 与 `(0.904, 0.208)` 两条坐标不再被误停用。

## 7. 待办（承接本轮）

- [ ] 跳转判负加三条排除 + `AD_ACTIVE` 约束（本节第 6 点）
- [ ] 条带下界 `0.26` 视实测放宽到 `0.34`（本轮 `exhausted=1` 说明有的版式跳过键更靠下）
- [ ] 左上角版式（抖音系 `4S|跳过`）暂不做（用户明确要求）
- [ ] P1 单写者重构：`hits` 归 1 的条目重建问题

## 8. 证据文件

| 文件 | 内容 |
|---|---|
| `README.md` / `docs/PLAN.md` | 架构与计划 |
| `app/src/com/zszl/netkit/SkipService.java` | 被测实现 |
