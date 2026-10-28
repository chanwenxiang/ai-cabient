#!/usr/bin/env python3
"""生成一份符合微信支付账单格式的测试 xlsx，验证 wxpay_bill_amount_dist.py 的解析能力。

格式依据（官方《交易账单详细说明》 pay.weixin.qq.com/doc/v3/merchant/4013071246）：
  - 前 17 行为表头说明，第 18 行列名，第 19 行起为数据
  - 字段用英文逗号分隔，每个字段前带 ` 前缀
  - SUCCESS 账单列名：交易时间,公众账号ID,商户号,设备号,微信订单号,商户订单号,用户标识,
    交易类型,交易状态,付款银行,货币种类,应结订单金额,代金券金额,商品名称,商户数据包,
    手续费,费率,订单金额,费率备注
"""
import os
import random
import zipfile

REPO_ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
OUT = os.path.join(REPO_ROOT, ".tmp", "fake-bill.xlsx")

HEADER = [
    "交易时间", "公众账号ID", "商户号", "设备号", "微信订单号", "商户订单号", "用户标识",
    "交易类型", "交易状态", "付款银行", "货币种类", "应结订单金额", "代金券金额",
    "商品名称", "商户数据包", "手续费", "费率", "订单金额", "费率备注",
]

random.seed(20261027)
rows = []

# 构造一个已知分布：0-3元 40%, 3-5元 30%, 5-8元 20%, 8-10元 7%, 10-20元 2.5%, 20+ 0.5%
plan = []
for _ in range(4000):
    r = random.random()
    if r < 0.40:
        plan.append(random.uniform(0.5, 2.99))
    elif r < 0.70:
        plan.append(random.uniform(3.0, 4.99))
    elif r < 0.90:
        plan.append(random.uniform(5.0, 7.99))
    elif r < 0.97:
        plan.append(random.uniform(8.0, 9.99))
    elif r < 0.995:
        plan.append(random.uniform(10.0, 19.99))
    else:
        plan.append(random.uniform(20.0, 60.0))

for i, amt in enumerate(plan):
    rows.append([
        f"2026-09-15 10:{i % 60:02d}:00", "wx6dacefb3671de630", "1700080722", "",
        f"4200000008202609{i:012d}", f"OUT{i:016d}", f"openid_{i}",
        "JSAPI", "SUCCESS", "OTHERS", "CNY", f"{amt:.2f}", "0.00",
        "零食", "交易收款", f"{amt * 0.006:.2f}", "0.60%", f"{amt:.2f}", "",
    ])
    # 每 500 笔插一条退款行，验证脚本会剔除
    if i % 500 == 499:
        rows.append([
            f"2026-09-15 11:{i % 60:02d}:00", "wx6dacefb3671de630", "1700080722", "",
            f"4200000008202609{i:012d}", f"OUT{i:016d}", f"openid_{i}",
            "REFUND", "REFUND", "OTHERS", "CNY", "0.00", "0.00",
            "零食", "交易收款", "-0.01", "0.60%", "0.00", "",
        ])

csv_lines = []
csv_lines.append(",".join(f"`{c}" for c in HEADER))
for r in rows:
    csv_lines.append(",".join(f"`{v}" for v in r))

preamble = [
    "微信支付交易账单明细",
    "商户号: 1700080722",
    "起始时间: 2026-08-28 00:00:00",
    "终止时间: 2026-09-26 23:59:59",
    "导出类型: 支付账单",
    "",
    "总交易单数: %d" % len(plan),
    "订单总金额: %.2f" % sum(plan),
]
for _ in range(7):
    preamble.append("备注行")
preamble.append("")  # 第 17 行分隔线
content = "\n".join(preamble) + "\n" + "\n".join(csv_lines) + "\n"

os.makedirs(os.path.dirname(OUT), exist_ok=True)

# 用标准 xlsx 写入 CSV 内容到 sheet1（sharedStrings 方式），确保解析器两条路径都能覆盖
from xml.sax.saxutils import escape

all_rows = content.rstrip("\n").split("\n")
strings = []
sidx = {}
for line in all_rows:
    for cell in line.split(","):
        if cell not in sidx:
            sidx[cell] = len(strings)
            strings.append(cell)

sheet_rows = []
for ri, line in enumerate(all_rows, start=1):
    cells = []
    for ci, cell in enumerate(line.split(",")):
        col = ""
        n = ci
        while True:
            col = chr(ord("A") + n % 26) + col
            n = n // 26 - 1
            if n < 0:
                break
        cells.append(f'<c r="{col}{ri}" t="s"><v>{sidx[cell]}</v></c>')
    sheet_rows.append(f'<row r="{ri}">{"".join(cells)}</row>')

sheet_xml = (
    '<?xml version="1.0" encoding="UTF-8" standalone="yes"?>'
    '<worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">'
    f'<sheetData>{"".join(sheet_rows)}</sheetData></worksheet>'
)
shared_xml = (
    '<?xml version="1.0" encoding="UTF-8" standalone="yes"?>'
    '<sst xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" '
    f'count="{len(strings)}" uniqueCount="{len(strings)}">'
    + "".join(f"<si><t>{escape(s)}</t></si>" for s in strings)
    + "</sst>"
)

with zipfile.ZipFile(OUT, "w", zipfile.ZIP_DEFLATED) as z:
    z.writestr("[Content_Types].xml",
               '<?xml version="1.0" encoding="UTF-8" standalone="yes"?>'
               '<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">'
               '<Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>'
               '<Default Extension="xml" ContentType="application/xml"/>'
               '<Override PartName="/xl/workbook.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"/>'
               '<Override PartName="/xl/worksheets/sheet1.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/>'
               '<Override PartName="/xl/sharedStrings.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sharedStrings+xml"/>'
               '</Types>')
    z.writestr("_rels/.rels",
               '<?xml version="1.0" encoding="UTF-8" standalone="yes"?>'
               '<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">'
               '<Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="xl/workbook.xml"/>'
               '</Relationships>')
    z.writestr("xl/workbook.xml",
               '<?xml version="1.0" encoding="UTF-8" standalone="yes"?>'
               '<workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" '
               'xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships">'
               '<sheets><sheet name="Sheet1" sheetId="1" r:id="rId1"/></sheets></workbook>')
    z.writestr("xl/_rels/workbook.xml.rels",
               '<?xml version="1.0" encoding="UTF-8" standalone="yes"?>'
               '<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">'
               '<Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet" Target="worksheets/sheet1.xml"/>'
               '<Relationship Id="rId2" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/sharedStrings" Target="sharedStrings.xml"/>'
               '</Relationships>')
    z.writestr("xl/sharedStrings.xml", shared_xml)
    z.writestr("xl/worksheets/sheet1.xml", sheet_xml)

print("生成测试账单:", os.path.abspath(OUT))
print("数据行数:", len(plan), "（另含 8 条退款行，脚本应剔除）")
print("预期笔均: %.4f" % (sum(plan) / len(plan)))
