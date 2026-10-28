#!/usr/bin/env python3
"""生成「业务/产品点位信息」空白模板（无库数据时的兜底）。

用法：python scripts/make_payscore_sites_template.py
输出：docs/figures/payscore-sites-template.csv（含表头 + 2 行示例 + 填写说明）
"""

import csv
import io
import os

REPO = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
OUT = os.path.join(REPO, "docs", "figures", "payscore-sites-template.csv")

HEADER = [
    "序号", "城市", "点位名称", "详细地址", "柜机数量", "设备编号",
    "柜型", "场景说明", "场景图文件名", "点位负责人", "联系电话", "合同起止", "经纬度",
]

ROWS = [
    [
        "1", "深圳市", "示例：某科技公司 A 座 18 层",
        "深圳市南山区科技园科苑路 15 号 XX 大厦 18 层", "2",
        "EG943322621391 / EG500909625160", "智能售卖柜",
        "办公区电梯厅旁，24 小时可取", "01_101.jpg; 01_102.jpg",
        "王经理", "13800000001", "2025-01-01~2027-12-31", "22.5412,113.9510",
    ],
    [
        "2", "深圳市", "示例：某餐饮店门口",
        "深圳市南山区 XX 路 88 号 1 楼", "1",
        "EG879147199289", "智能售卖柜",
        "餐厅取餐区，午市高峰投放", "02_201.jpg",
        "李老板", "13800000002", "2025-06-01~2027-05-31", "22.5122,113.9380",
    ],
]

NOTES = [
    "# 业务/产品点位信息 —— 微信支付分申请邮件附件（空白模板）",
    "# 官方要求：城市、地址、数量、场景图等（见《接入支付分须知》第 14 项）",
    "#",
    "# 填写说明：",
    "#   1) 一个物理点位占一行；同一地址有多台柜机时合并成一行，柜机数量如实填，设备编号用 / 分隔",
    "#   2) 城市填到市级即可（如「深圳市」）；详细地址写到门牌/楼层",
    "#   3) 场景说明写清投放位置与消费场景（办公区/食堂/餐厅/园区等）",
    "#   4) 场景图文件名按「序号_柜机序号.jpg」命名，图片与本表放同一目录，一一对应",
    "#   5) 柜机数量合计应与《设备照》中展示的柜机总数一致",
    "#",
    "# 若生产库有 device_info.install_address / site_contract.address，",
    "# 可直接跑 scripts/export_payscore_sites.py 自动生成，免手工填。",
]

os.makedirs(os.path.dirname(OUT), exist_ok=True)
with io.open(OUT, "w", encoding="utf-8-sig", newline="") as f:
    w = csv.writer(f)
    for n in NOTES:
        w.writerow([n])
    w.writerow([])
    w.writerow(HEADER)
    for r in ROWS:
        w.writerow(r)

print("模板已生成:", OUT, os.path.getsize(OUT), "bytes")
