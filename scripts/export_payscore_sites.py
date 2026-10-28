#!/usr/bin/env python3
"""
导出「业务/产品点位信息」清单 —— 微信支付分申请邮件附件第 5 项。

官方依据：《接入支付分须知》(2021-04-06 PDF) 第 14 项
  「业务/产品点位信息（包括但不限于城市、地址、数量、场景图等）」

用法（需在能连生产库的机器上执行）：
    python scripts/export_payscore_sites.py                 # 输出 CSV + Markdown 清单
    python scripts/export_payscore_sites.py --psql "docker exec -i ai-cabinet-postgres-1 psql -U aicabinet -tA -c"

输出：
    - 站点级汇总表（官方要的：城市 / 地址 / 数量）
    - 设备明细表（一台一行，便于对照拍场景图）
    - 每站点一张「场景图待补」清单（含图文件命名规范，直接按名字存图即可）
"""

import argparse
import csv
import io
import os
import re
import subprocess
import sys

REPO = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
OUT_DIR = os.path.join(REPO, "docs", "figures")

# 取址口径：优先 site_contract（点位合同，有正式地址），回退 device_info.install_address
SQL = """
WITH site AS (
  SELECT
    COALESCE(NULLIF(sc.site_name, ''), di.device_name) AS site_name,
    COALESCE(NULLIF(sc.address, ''), NULLIF(di.install_address, '')) AS address,
    sc.landlord_name,
    sc.landlord_phone,
    sc.status AS contract_status,
    sc.start_date,
    sc.end_date,
    di.device_id,
    di.device_name,
    di.cabinet_type,
    di.install_address,
    di.install_lat,
    di.install_lng,
    di.online_status
  FROM device_info di
  LEFT JOIN site_contract sc
    ON sc.device_id = di.device_id AND COALESCE(sc.is_deleted, false) = false
  WHERE di.install_address IS NOT NULL AND di.install_address <> ''
     OR sc.address IS NOT NULL AND sc.address <> ''
)
SELECT
  site_name, address, landlord_name, landlord_phone, contract_status,
  start_date, end_date, device_id, device_name, cabinet_type,
  install_address, install_lat, install_lng, online_status
FROM site
ORDER BY address NULLS LAST, device_id;
"""


def run_sql(psql_cmd, sql):
    """执行 SQL 并返回行列表（按 | 分隔的文本）。

    SQL 通过 stdin 传入 psql（-f -），避免 shell 引号转义把 SQL 截断。
    """
    try:
        out = subprocess.run(
            psql_cmd + " -f -",
            shell=True,
            input=sql,
            capture_output=True,
            text=True,
            timeout=180,
        )
    except subprocess.TimeoutExpired:
        raise SystemExit("[ERR] SQL 执行超时")
    if out.returncode != 0 or (not out.stdout.strip() and out.stderr.strip()):
        raise SystemExit(f"[ERR] SQL 失败：\n{(out.stderr or out.stdout)[:800]}")
    rows = []
    for line in out.stdout.splitlines():
        if not line.strip():
            continue
        rows.append(line.split("|"))
    return rows


def split_cn_address(addr):
    """从地址里粗提城市（用于官方要的「城市」列）。"""
    if not addr:
        return ""
    m = re.match(r"^(.*?(?:市|自治州|地区|盟))", addr)
    if m:
        return m.group(1)
    # 没有"市"字：取前 2~4 字（如"深圳市盐田区…"→"深圳市"）
    m = re.match(r"^(.{2,4}?区|.{2,4}?县|.{2,4}?镇|.{2,4}?路|.{2,4}?街)", addr)
    return m.group(1) if m else addr[:6]


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument(
        "--psql",
        default="docker exec -i ai-cabinet-postgres-1 psql -U aicabinet -tA",
        help="执行 SQL 的命令前缀（默认连本机 dev 库；脚本会自行追加 -f - 从 stdin 读 SQL）",
    )
    ap.add_argument(
        "--out-prefix", default="payscore-sites", help="输出文件名前缀"
    )
    args = ap.parse_args()

    print("[1/4] 查询点位数据 …")
    rows = run_sql(args.psql, SQL)
    if not rows:
        print(
            "[WARN] 未查到任何点位数据（device_info.install_address 与 site_contract.address 均为空）。\n"
            "       ⇒ 生产库上执行本脚本；或手工按下表模板填写。"
        )
        rows = []

    # 汇总：按地址聚合成"站点"
    sites = {}
    for r in rows:
        site_name = r[0]
        addr = r[1] or (r[10] if len(r) > 10 else "") or ""
        key = (addr, site_name)
        s = sites.setdefault(
            key,
            {
                "site_name": site_name,
                "address": addr,
                "city": split_cn_address(addr),
                "landlord": r[2],
                "phone": r[3],
                "contract": r[4],
                "start": r[5],
                "end": r[6],
                "devices": [],
            },
        )
        s["devices"].append(
            {
                "device_id": r[7],
                "device_name": r[8],
                "cabinet_type": r[9],
                "address": r[10],
                "lat": r[11],
                "lng": r[12],
                "online": r[13],
            }
        )

    os.makedirs(OUT_DIR, exist_ok=True)
    csv_path = os.path.join(OUT_DIR, f"{args.out_prefix}.csv")
    md_path = os.path.join(OUT_DIR, f"{args.out_prefix}.md")

    # ── CSV：官方要的字段 ──
    with io.open(csv_path, "w", encoding="utf-8-sig", newline="") as f:
        w = csv.writer(f)
        w.writerow(["# 业务/产品点位信息 —— 微信支付分申请附件"])
        w.writerow(["# 官方要求：城市、地址、数量、场景图等"])
        w.writerow([])
        w.writerow(
            [
                "序号",
                "城市",
                "点位名称",
                "详细地址",
                "柜机数量",
                "设备编号",
                "柜型",
                "场景说明",
                "场景图文件名",
                "点位负责人",
                "联系电话",
                "合同起止",
                "经纬度",
            ]
        )
        idx = 0
        for (addr, _), s in sorted(sites.items(), key=lambda kv: (kv[0][0] or "")):
            idx += 1
            devs = s["devices"]
            # 场景说明按柜型归类
            types = sorted({d["cabinet_type"] or "无人柜" for d in devs})
            scene = "、".join(types)
            # 图文件命名规范：点位序号_柜型_序号
            img = "; ".join(
                f"{idx:02d}_{idx}{i:02d}_{(d['cabinet_type'] or 'cabinet')}.jpg"
                for i, d in enumerate(devs, 1)
            )
            w.writerow(
                [
                    idx,
                    s["city"],
                    s["site_name"],
                    s["address"],
                    len(devs),
                    " / ".join(d["device_id"] or "" for d in devs),
                    scene,
                    f"办公室/餐饮店/园区等封闭场景投放的{scene}",
                    img,
                    s["landlord"] or "",
                    s["phone"] or "",
                    f"{s['start'] or ''}~{s['end'] or ''}".strip("~"),
                    "; ".join(
                        f"{d['lat']},{d['lng']}" for d in devs if d["lat"] and d["lng"]
                    ),
                ]
            )
    print(f"[2/4] 已写出站点汇总 CSV：{csv_path}（{len(sites)} 个点位）")

    # ── Markdown：给评审看的清单 + 图命名规范 + 待补项 ──
    md = []
    md.append("# 业务/产品点位信息（微信支付分申请附件）\n")
    md.append(
        f"> 官方要求：**城市、地址、数量、场景图**（以及其他你认为需要补充的信息）。\n"
        f"> 共 **{len(sites)}** 个点位、**{sum(len(s['devices']) for s in sites.values())}** 台柜机。\n"
    )
    md.append("## 一、点位汇总\n")
    md.append("| 序号 | 城市 | 点位名称 | 详细地址 | 柜机数量 | 柜型 | 场景图文件名 |")
    md.append("|---|---|---|---|---|---|---|")
    for idx, ((addr, _), s) in enumerate(
        sorted(sites.items(), key=lambda kv: (kv[0][0] or "")), 1
    ):
        devs = s["devices"]
        types = "、".join(sorted({d["cabinet_type"] or "无人柜" for d in devs}))
        img = "; ".join(
            f"{idx:02d}_{idx}{i:02d}.jpg" for i, d in enumerate(devs, 1)
        )
        md.append(
            f"| {idx} | {s['city']} | {s['site_name']} | {s['address']} | "
            f"{len(devs)} | {types} | {img} |"
        )

    md.append("\n## 二、场景图收集要求\n")
    md.append(
        "每个点位至少 1 张场景图（画面含：柜机全貌 + 摆放环境 + 周边参照物如门牌/工位号）。\n"
        "文件名按上表「场景图文件名」命名，图片与本清单放在同一目录，一一对应。\n"
    )

    md.append("\n## 三、设备明细\n")
    md.append("| 设备编号 | 设备名 | 柜型 | 安装地址 | 经纬度 | 在线状态 |")
    md.append("|---|---|---|---|---|---|")
    for (addr, _), s in sorted(sites.items(), key=lambda kv: (kv[0][0] or "")):
        for d in s["devices"]:
            md.append(
                f"| {d['device_id']} | {d['device_name']} | {d['cabinet_type']} | "
                f"{d['address']} | {d['lat']},{d['lng']} | {d['online']} |"
            )

    md.append("\n## 四、仍需人工补充（脚本无法从库中取得）\n")
    md.append(
        "- [ ] **场景图实拍**（每个点位至少 1 张，含柜机全貌与摆放环境）\n"
        "- [ ] **点位的场景类型描述**（如「某科技公司 18 楼办公区 / 某餐厅门口」）\n"
        "- [ ] 若库中地址为空白，需按合同/实际投放情况补全「城市 + 详细地址」\n"
    )

    io.open(md_path, "w", encoding="utf-8", newline="\n").write("\n".join(md) + "\n")
    print(f"[3/4] 已写出点位清单 Markdown：{md_path}")

    print("[4/4] 完成。请按 Markdown 第三节补齐场景图后，与其它附件一并打包。")
    if not rows:
        print(
            "\n[提示] 库中无数据时，请手工按 CSV 的表头填写，字段为：\n"
            "  序号 / 城市 / 点位名称 / 详细地址 / 柜机数量 / 设备编号 / 柜型 / 场景说明 / "
            "场景图文件名 / 点位负责人 / 联系电话 / 合同起止 / 经纬度"
        )


if __name__ == "__main__":
    main()
