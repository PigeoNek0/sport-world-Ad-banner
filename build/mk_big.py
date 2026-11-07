import io, re, sys

SRC = r"build\localfilter_v2.txt"
OUT = r"app\assets\blocklist_big.txt"

# 绝对保护：这些后缀一律不拦（业务/登录/地图/业务 CDN）
KEEP_SUFFIX = (
    "weixin.qq.com", "wx.qq.com", "myhuaweicloud.com", "iydsj.com",
    "amap.com", "autonavi.com", "baidu.com", "bdstatic.com",
    "qq.com", "qpic.cn", "umeng.com", "getui.com", "igexin.com",
    "miui.com", "xiaomi.com", "mi.com",
)
# 精确保护：只有这些业务域放行（与上面相反的白名单）
KEEP_EXACT = set()

def keep(dom):
    d = dom.strip().lower()
    if not d or d.startswith("#") or " " in d or "/" in d:
        return False
    for s in KEEP_SUFFIX:
        if d == s or d.endswith("." + s):
            return False
    return True

seen = set()
out = []
with io.open(SRC, "r", encoding="utf-8", errors="ignore") as f:
    for line in f:
        d = line.strip().lower()
        if not d or d.startswith("#"):
            continue
        # 兼容 hosts 格式
        if " " in d or "\t" in d:
            parts = d.split()
            d = parts[-1]
        if not keep(d):
            continue
        if d in seen:
            continue
        seen.add(d)
        out.append(d)

out.sort()
with io.open(OUT, "w", encoding="utf-8", newline="\n") as f:
    f.write("# 大清单：由 personalDNSfilter 合并清单(32 万条)裁剪而来\n")
    f.write("# 已剔除业务/登录/地图/推送等后缀，默认关闭，开启后误伤风险自负\n")
    for d in out:
        f.write(d + "\n")
print("big list lines:", len(out))
print("bytes:", sum(len(d) + 1 for d in out))
