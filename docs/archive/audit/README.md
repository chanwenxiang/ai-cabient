# 审计文档归档（docs/archive/audit/）

> 2026-10-18 归档。**缺陷编号与修复状态的唯一事实源是 `docs/AUDIT_LEDGER_2026-10-13.md`**——
> 本目录所有文件的「已修/待修」状态一律以台账为准（DEV-03 教训：源报告的状态列会烂）。
> 归档 = 只读快照，不再更新；引用历史结论时先到台账对行号与处置状态。

## 各文档定性（2026-10-18 复审后的采信口径）

| 文件 | 轮次 | 采信口径 |
|---|---|---|
| `AUDIT_TRACK_*_2026-10-13.md` ×14 | 2026-10-13 八路审计的源报告 | **缺陷定性仍有效**（93% 锚点复验成立）；修复状态列一律看台账；TRACK_C 的 F2–F8 运行期实测仍是有效待办（台账 DEV-07） |
| `CODE_AUDIT_REPORT_2026-10-05.md` / `_FULL.md` | 2026-10-05 全仓审计 | P0×7 全部修复/定案在位；P1×26 中 24 属实 2 部分；「存疑 18 条」判定维持；7 条出入详见 2026-10-18 报告 §5.2。P1-7（CsvCells）被 `scripts/check-csv-escape.mjs` 注释引用 |
| `FIX_QUEUE_2026-10-05.md` | 10-05 修复队列 | **严重低估完成度**（P0-2/P0-3 已于 10-15 完成）且「P1-11 docker.sock」改口未回写——只作历史参考，勿按它排活 |
| `THREE_END_FULL_REVIEW_2026-09-27.md` | 2026-09-27 三端复审 | 11 条 P1 全部修在位（11/11 属实）；mock 开关总表仍有参考价值 |
| `three-end-full-audit-2026-09-15.md` | 2026-09-15 首轮 | 已自标过期快照；对象含已删除的 vision-service |
| `audit-backlog-verification-2026-09-18.md` | 2026-09-18 对账 | 已自标过期快照 |
| `SECURITY_BEST_PRACTICES_REPORT.md` | 2026-08-19 | **严重过时，结论不可引用**——早于全部修复批次，扫描范围含已删除的 vision-service |
| `JIANGYI_MIGRATION_AUDIT_2026-10-11.md` | 2026-10-11 将邑迁移复审 | P1/P2 两缺陷已修；5 条 D 级待拍板的处置以台账为准；「CI 集成 job 每次推送真跑」表述失实（见 AU-178） |

## 活文档（仍在 docs/ 根，勿归档）

- `AUDIT_LEDGER_2026-10-13.md` —— **唯一编号源**（AU-001~192）
- `AUDIT_THREE_END_FULL_2026-10-18.md` + `_APPENDIX.md` —— 最新三端全量复审
- `AUDIT_DECISION_BRIEF_2026-10-16.md` —— 待拍板简报（§12/§13 活）
- `AUDIT_F2_F3_F4_DECISION_BRIEF_2026-10-16.md` —— 费用口径拍板状态表
- `AUDIT_B1_EXTENDED_FUND_2026-10-16.md` —— 动钱包/佣金/费用扣款前必读
- `AUDIT_DEV_DRIFT_RECONCILIATION_2026-10-16.md` —— 漂移对账（DEV-07 开放）
- 第三路 9 轴审计在分支 `audit/full-sweep-2026-10-18`（`AUDIT_FULL_SWEEP_2026-10-18.md` @ `5b32dc3e`），其发现已收编台账 AU-174~192
