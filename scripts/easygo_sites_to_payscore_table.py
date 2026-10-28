#!/usr/bin/env python3
"""
把易购（easygo 老系统）导出的柜机点位表，转成微信支付分申请要求的
「业务/产品点位信息」13 字段表格。

用法：
    python scripts/easygo_sites_to_payscore_table.py <导出的csv> [输出目录]

输入：docs/sql/easygo_payscore_sites_export.sql 在阿里云 DMS 的导出结果（CSV）
输出：
    - <输出目录>/payscore-sites.csv        官方 13 字段表格（UTF-8 BOM，Excel 直接打开）
    - <输出目录>/payscore-sites.md         评审用清单（汇总 + 设备明细 + 待补项）
    - <输出目录>/payscore-sites-pending.csv  地址缺失 / 城市缺失的柜机（需人工补）

官方依据：《接入支付分须知》第 14 项「业务/产品点位信息（包括但不限于城市、地址、数量、场景图等）」
"""

import csv
import io
import os
import re
import sys
from collections import defaultdict

HEADER = [
    "序号", "城市", "点位名称", "详细地址", "柜机数量", "设备编号",
    "柜型", "场景说明", "场景图文件名", "点位负责人", "联系电话", "合同起止", "经纬度",
]

# 老系统导出列名 → 本文标准列名
COLMAP = {
    "柜子编号": "code",
    "机器ID": "machine_id",
    "柜机名称": "machine_name",
    "线长": "route_operator",
    "在线状态": "online",
    "标签": "label",
    "点位名称": "position_name",
    "点位等级": "position_level",
    "所属组织": "org",
    "点位负责人": "manager",
    "点位租金费率": "rent",
    "点位照片PC": "pic_pc",
    "点位照片移动端": "pic_mobile",
    "完整地址": "address",
    "城市": "city",
    "区域": "area",
    "详细地址": "address_detail",
    "纬度": "lat",
    "经度": "lng",
}

# 易购柜型映射（m8/m9 两代 + 通用）
def guess_city(addr, fallback=""):
    """从地址推城市。

    🔴 坑：地址常带省级前缀（"广东省深圳市…"），直接 `^.{2,4}?(市|…)` 会匹配到"广东省"，
    反而丢掉真正的城市。⇒ 先剥掉省级前缀，再取市/州。
    """
    if fallback:
        return fallback
    if not addr:
        return ""
    s = addr
    for prov in ("广东省", "广西壮族自治区", "浙江省", "江苏省", "福建省", "山东省",
                 "河南省", "四川省", "湖北省", "湖南省", "河北省", "安徽省", "江西省",
                 "陕西省", "辽宁省", "吉林省", "黑龙江省", "海南省", "云南省", "贵州省",
                 "甘肃省", "青海省", "山西省", "北京市", "上海市", "天津市", "重庆市",
                 "内蒙古自治区", "宁夏回族自治区", "新疆维吾尔自治区", "西藏自治区",
                 "香港特别行政区", "澳门特别行政区", "台湾省"):
        if s.startswith(prov):
            s = s[len(prov):]
            break
    m = re.match(r"^(.{2,6}?(?:市|自治州|地区|盟))", s)
    if m:
        return m.group(1)
    # 仍取不到：退而求其次，取前 3 字（如"深圳市"的"深圳"）
    return s[:3] if len(s) >= 3 else s


CABINET_TYPE = {
    "EGTL": "弹簧出货柜",
    "EGBD": "弹簧出货柜",
    "EG": "智能售卖柜",
}


def guess_cabinet_type(code):
    for prefix, name in sorted(CABINET_TYPE.items(), key=lambda kv: -len(kv[0])):
        if code.upper().startswith(prefix):
            return name
    return "无人售卖柜"


def clean(s):
    return (s or "").strip()


def load(path):
    """读老系统导出 CSV（可能是 UTF-8 BOM / GBK / 带表头注释行）。"""
    raw = io.open(path, "rb").read()
    text = None
    for enc in ("utf-8-sig", "gbk", "gb18030"):
        try:
            text = raw.decode(enc)
            break
        except UnicodeDecodeError:
            continue
    if text is None:
        raise SystemExit(f"[ERR] 无法解码 {path}")

    lines = text.splitlines()
    # 找表头行：包含「柜子编号」或「machine_code」的那行
    hidx = None
    for i, l in enumerate(lines):
        if "柜子编号" in l or "machine_code" in l.lower():
            hidx = i
            break
    if hidx is None:
        raise SystemExit("[ERR] 未找到表头行（应含「柜子编号」列）")
    print(f"[1/4] 表头位于第 {hidx + 1} 行")

    rows = []
    rdr = csv.reader(io.StringIO("\n".join(lines[hidx:])))
    hdr = next(rdr)
    for r in rdr:
        if not r or len(r) < len(hdr):
            continue
        rec = {}
        for k, v in zip(hdr, r):
            key = COLMAP.get(clean(k), clean(k))
            rec[key] = clean(v)
        if rec.get("code"):
            rows.append(rec)
    print(f"[2/4] 解析柜机 {len(rows)} 台")
    return rows


def main():
    if len(sys.argv) < 2:
        print(__doc__)
        raise SystemExit(1)

    src = sys.argv[1]
    out_dir = sys.argv[2] if len(sys.argv) > 2 else os.path.join(
        os.path.dirname(os.path.dirname(os.path.abspath(__file__))), "docs", "figures"
    )
    os.makedirs(out_dir, exist_ok=True)

    rows = load(src)

    # ── 按「完整地址 + 点位名称」聚合点位 ──
    # 说明：同一栋楼的**不同楼层**是不同点位（点位名称含楼层），需分行；
    #       完全同名同址的才合并（同址多台柜机）。
    sites = defaultdict(list)
    pending = []
    for r in rows:
        addr = r.get("address", "")
        city = r.get("city", "")
        if not addr:
            pending.append(r)
            continue
        key = (addr, r.get("position_name", ""))
        sites[key].append(r)

    # ── 输出 ──
    csv_path = os.path.join(out_dir, "payscore-sites.csv")
    md_path = os.path.join(out_dir, "payscore-sites.md")
    pend_path = os.path.join(out_dir, "payscore-sites-pending.csv")

    ordered = sorted(sites.items(), key=lambda kv: (kv[0][0] or ""))
    total_machines = sum(len(v) for v in sites.values())

    with io.open(csv_path, "w", encoding="utf-8-sig", newline="") as f:
        w = csv.writer(f)
        w.writerow(["# 业务/产品点位信息 —— 微信支付分申请邮件附件"])
        w.writerow(["# 数据源：易购（easygo）老系统全量柜机点位导出"])
        w.writerow([f"# 点位数 {len(ordered)}，柜机总数 {total_machines}"])
        w.writerow([])
        w.writerow(HEADER)
        for idx, ((addr, pname), devs) in enumerate(ordered, 1):
            first = devs[0]
            city = guess_city(addr, first.get("city", ""))
            types = sorted({guess_cabinet_type(d["code"]) for d in devs})
            # 场景说明：按线长/点位名推断不了具体场景，用点位等级+区域兜底
            scene = first.get("position_level") or first.get("org") or "办公/餐饮/园区等封闭场景"
            imgs = "; ".join(f"{idx:03d}_{d['code']}.jpg" for d in devs)
            w.writerow([
                idx, city, pname or first.get("machine_name", ""), addr, len(devs),
                " / ".join(d["code"] for d in devs),
                "、".join(types),
                scene, imgs,
                first.get("manager", ""), "", "",
                "; ".join(
                    f"{d['lat']},{d['lng']}" for d in devs if d.get("lat") and d.get("lng")
                ),
            ])

    # 待补地址
    with io.open(pend_path, "w", encoding="utf-8-sig", newline="") as f:
        w = csv.writer(f)
        w.writerow(["柜子编号", "柜机名称", "线长", "点位名称", "城市", "区域", "详细地址", "处理方式"])
        for r in pending:
            w.writerow([
                r.get("code", ""), r.get("machine_name", ""), r.get("route_operator", ""),
                r.get("position_name", ""), r.get("city", ""), r.get("area", ""),
                r.get("address_detail", ""), "需人工补地址与城市",
            ])

    # Markdown
    md = []
    md.append("# 业务/产品点位信息（微信支付分申请附件）\n")
    md.append(
        f"> 官方要求：**城市、地址、数量、场景图**。共 **{len(ordered)}** 个点位、"
        f"**{total_machines}** 台柜机（易购老系统全量在册）。\n"
    )
    md.append("## 一、点位汇总\n")
    md.append("| 序号 | 城市 | 点位名称 | 详细地址 | 柜机数量 | 柜型 | 场景图文件名 |")
    md.append("|---|---|---|---|---|---|---|")
    for idx, ((addr, pname), devs) in enumerate(ordered, 1):
        first = devs[0]
        city = guess_city(addr, first.get("city", ""))
        types = "、".join(sorted({guess_cabinet_type(d["code"]) for d in devs}))
        imgs = "; ".join(f"{idx:03d}_{d['code']}.jpg" for d in devs)
        md.append(
            f"| {idx} | {city} | {pname or first.get('machine_name','')} | {addr} | "
            f"{len(devs)} | {types} | {imgs} |"
        )

    md.append("\n## 二、场景图\n")
    md.append(
        "**优先用老系统已有的点位照片**（导出表里的「点位照片PC / 点位照片移动端」字段），\n"
        "下载后按上表「场景图文件名」重命名，与本表一一对应。\n\n"
        "缺失的点位再实地补拍：画面含柜机全貌 + 摆放环境 + 周边参照物（门牌/工位号/招牌）。\n"
    )

    md.append("\n## 三、设备明细\n")
    md.append("| 柜子编号 | 柜机名称 | 线长 | 点位名称 | 地址 | 在线 |")
    md.append("|---|---|---|---|---|---|")
    for (addr, pname), devs in ordered:
        for d in devs:
            md.append(
                f"| {d['code']} | {d.get('machine_name','')} | {d.get('route_operator','')} | "
                f"{pname} | {addr} | {d.get('online','')} |"
            )

    md.append("\n## 四、仍需人工补充\n")
    md.append(
        f"- [ ] **场景图**：优先用老系统已有点位照片（导出表两列），缺失的补拍\n"
        f"- [ ] **地址缺失的柜机 {len(pending)} 台** → 见 `payscore-sites-pending.csv`，需人工补地址与城市\n"
        f"- [ ] **联系电话 / 合同起止**：库里无此字段，需按点位合同补填\n"
        f"- [ ] 柜机数量合计（{total_machines}）应与《设备照》展示的柜机总数一致\n"
    )

    io.open(md_path, "w", encoding="utf-8", newline="\n").write("\n".join(md) + "\n")

    print(f"[3/4] 点位表：{csv_path}（{len(ordered)} 点位 / {total_machines} 台）")
    print(f"      评审清单：{md_path}")
    print(f"      待补地址：{pend_path}（{len(pending)} 台）")
    print("[4/4] 完成。")


if __name__ == "__main__":
    main()
