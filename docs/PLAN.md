# NetKit 下一阶段计划（P1–P4）

> 建立于 2026-11-03。本文件只写**要做什么、怎么算做完**；背景见 [`README.md`](../README.md) 与 [`docs/SKIP_SERVICE_NOTE.md`](SKIP_SERVICE_NOTE.md)。

---

## P0 文档与仓库整理 ✅（2026-11-03 完成）

- [x] 重写 `README.md`（当前状态 + 构建 + 使用）
- [x] 新建 `docs/PLAN.md`（本文件）
- [x] 提交一次基线

**验收**：新接手的人只看 `README.md` 就能构建、安装、复现日志。

---

## P1 单写者重构：彻底修掉「坐标学习档案被覆盖 / 误删」

### 背景（必读）

`files/profiles.json` 目前有两个写者：`SkipService`（自动学习）和 `ProfilesActivity`（人工复核）。
服务是常驻进程，内存副本陈旧，落盘时会把人工设的 `auto` 覆盖回 `pending`。上一版用旁路文件
`files/profile_states.json` 止血（人工状态学习流程永不触碰），但仍有：

1. 学习日志 `hits` 从 1 重新计数 → 服务侧 `Profiles.load()` 偶尔读到空/半截文件，新建同名条目；
2. `verifyPending()` 以「1.2 s 后特征仍在」判 `misses>=2` 即删除 → 特征本身会飘，误删刚复核的表项。

### 步骤

- [ ] **1. 服务成为唯一写者**
  - `ProfilesActivity` 不再直接 `Profiles.save/setState`，改为
    `sendBroadcast(new Intent(ACTION_SET_STATE).setPackage(getPackageName()).putExtra("sig",..).putExtra("state",..))`
  - `SkipService` 注册该广播（`RECEIVER_NOT_EXPORTED`），收到后改内存并 `persist()`
  - 常量：`ACTION_SET_STATE = "com.zszl.netkit.action.SET_STATE"`

- [ ] **2. 原子写**
  - `Profiles.save/saveStates` 先写 `*.tmp` 再 `renameTo()`，避免读到半截文件
  - 读失败时**保留内存，不清空**（现在 `load()` 返回空 map 会被当成「档案是空的」）

- [ ] **3. 学习去重：按「版式」而不是按字符串**
  - 现有 `compatibleSig()` 合并同类签名；补一条：**同一坐标（±0.03）内不再新建条目**，直接并入
  - 新建条目一律 `pending`（已实现），且 `hits` 从磁盘已有值续接

- [ ] **4. 成功的判定从「特征消失」改为「界面变化」**
  - 点完 1.2 s 后计算窗口内容指纹 `fingerprint(nodes)`（`包名 + 顶层类名 + 可见文本数 + 首屏文本哈希`）
  - 指纹变化 → `hits++`；指纹不变 → `misses++`
  - `misses>=2` 只**降级为 pending**，不再直接删除（删除交给用户）

- [ ] **5. 调试页**
  - 新增「诊断」入口：显示 `profiles.json` / `profile_states.json` 的 **原始文本 + mtime + 条目数**
  - 便于下次一眼确认「有没有被覆盖」

### 验收（P1）

| 检查 | 通过标准 |
|---|---|
| 启用后连跑 5 次冷启动 | 该条始终「生效」，`profile_states.json` 中 `state=auto` 存活 |
| 学习日志 | `hits` 单调递增，不出现从 1 重新计数 |
| 误删 | 5 次冷启动内条目数不减少（除非手动删除） |
| 学习失败样本 | 得到 `misses` 而非被静默删除 |

---

## P2 三条验收 + 四条红线

### 三条验收（Q22）

- [ ] ① 目标 App（运动世界）进程内广告域名被拦：冷启动 `BLOCK ≥ 50`，开屏/插屏明显减少
- [ ] ② 其他 App 完全不受影响：浏览器、微信、支付宝等能正常联网（可用 `adb shell ping` 与 App 实测双证）
- [ ] ③ 四条红线复测：
  - [ ] 冷启动进首页
  - [ ] 手机号登录
  - [ ] 校园跑打卡出轨迹
  - [ ] 户外跑记录能上传

**任一条红线挂掉 → 立刻停手，保留可回滚原包，不再加新功能。**

### 执行方式

1. 关掉「大清单」，只用 core 38 条跑一遍（最小干扰）
2. 再开大清单跑一遍（最大拦截）
3. 两次都记录：`BLOCK` 条数、`skip fired` 次数、开屏是否出现、四条红线结果

---

## P3 发行准备

- [ ] `versionName` 提到 1.1.0，`versionCode` 递增
- [ ] `README.md` 补：三张截图（首页 / 坐标学习 / 日志）、三条已知边界
- [ ] `BUILDING.md`：无 Gradle 构建链说明（给想自己编译的人）
- [ ] Release note：本次修了什么、还有什么限制
- [ ] 打 tag `v1.1.0`，附 `NetKit-signed.apk` 与其 sha256

---

## P4 可选（暂缓，等 P1–P3 收尾）

- 规则在线更新（从自建静态 JSON 拉取，注意不能依赖境外服务）
- Gradle 迁移（本机 TLS 中间人问题会让依赖解析不稳，非必要不做）
- 多语言（当前只有中文，暂不需要）

---

## 风险与回滚

| 风险 | 处理 |
|---|---|
| 单写者重构后服务收不到广播 | 保留 `ProfilesActivity` 的本地直写作为 fallback（广播失败时降级），并打日志 |
| 界面指纹算法不稳定 | 保留旧判定作为二级兜底；两者都失败才计 `misses` |
| 验收发现红线挂掉 | 停手；本轮所有改动都可 `git checkout <旧 tag>` 回滚，App 卸载即完全恢复 |
