# -*- coding: utf-8 -*-
"""Generate Flyway V236 COMMENT ON TABLE/COLUMN for uncommented schema objects."""
from __future__ import annotations

from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
COLS_TSV = ROOT / ".tmp-uncommented-cols.tsv"
OUT = ROOT / "services/trade-service/src/main/resources/db/migration/V236__schema_comments_backfill.sql"

TABLE_COMMENTS: dict[str, str] = {
    "ad_campaign": "广告投放计划（素材编排与投放窗口）",
    "ad_campaign_device": "投放计划关联设备范围",
    "ad_campaign_item": "投放计划素材条目（顺序/时长）",
    "ad_play_event": "柜机广告播放事件流水",
    "admin_audit_log": "运营后台操作审计日志",
    "aliyun_category_mapping": "阿里云视觉类目与本地类目映射",
    "announcement": "运营通知公告（商户/消费者）",
    "balance_refund_allocation": "余额退款金额分摊明细（按充值单）",
    "balance_refund_request": "用户余额退款申请（审核/原路退）",
    "compensation_task": "补偿任务队列表（失败重试）",
    "data_discrepancy": "数据差异明细（一致性巡检发现）",
    "data_quality_check": "数据质量检查规则/结果",
    "device_env_reading": "设备环境读数（温湿度等）",
    "device_fault_report": "设备故障上报记录",
    "device_lifecycle_event": "设备生命周期状态变更事件",
    "device_ops_event": "设备运维事件（锁机/解锁/告警等）",
    "device_sku_lot": "柜内 SKU 批次库存（效期）",
    "device_sku_price": "柜机 SKU 售价覆盖（可覆盖目录价）",
    "device_slot": "柜内货道/格口定义",
    "device_temp_plan": "设备温控计划主表",
    "device_temp_plan_entry": "温控计划时段条目",
    "device_temperature_reading": "设备温度传感器读数",
    "dispute_message": "争议工单沟通消息",
    "file_attachment": "通用业务附件元数据",
    "finance_margin_daily_lock": "财务毛利日结锁定",
    "flyway_schema_history": "Flyway 数据库版本迁移历史",
    "inventory_movement": "库存变动流水（柜/仓）",
    "inventory_write_off": "库存报损/核销单",
    "invoice_request": "用户开票申请",
    "line_commission_daily": "线长佣金日汇总",
    "line_device": "线长管辖设备绑定",
    "line_manager": "线长档案（用户绑定）",
    "line_promo_task": "线长推广/巡检任务",
    "line_wallet_account": "线长钱包账户",
    "line_wallet_ledger": "线长钱包流水",
    "line_withdraw_request": "线长提现申请",
    "media_asset": "投放素材库（图/视频/H5）",
    "member_points_log": "会员积分变动流水",
    "merchant_notify_log": "商户消息推送记录",
    "merchant_ops_config": "商户运营参数配置",
    "merchant_replenishment_request": "商户要货申请单头",
    "merchant_replenishment_request_line": "商户要货申请明细",
    "merchant_role_template": "商户角色权限模板",
    "merchant_subscribe_pref": "商户订阅消息偏好",
    "merchant_tax_profile": "商户税务资料（开票抬头等）",
    "merchant_wallet_account": "商户钱包账户",
    "merchant_wallet_ledger": "商户钱包流水",
    "merchant_withdraw_request": "商户提现申请",
    "notification_log": "站内信/通知发送记录",
    "notification_template": "通知模板定义",
    "ops_2fa_recovery_code": "运营账号 2FA 恢复码",
    "ops_device_org": "设备与组织节点归属",
    "ops_exception": "运营异常中心工单（履约/结算异常）",
    "ops_org_node": "组织树节点（点位/区域）",
    "ops_permission": "运营 RBAC 权限/菜单",
    "ops_role": "运营角色",
    "ops_role_permission": "角色-权限关联",
    "ops_user_device_scope": "运营账号设备数据范围",
    "ops_user_device_scope_pref": "运营账号设备范围偏好",
    "ops_user_merchant": "运营账号绑定商户范围",
    "ops_user_role": "运营账号-角色关联",
    "ops_user_route_scope": "运营账号补货路线范围",
    "ota_device_report": "OTA 设备上报版本/状态",
    "ota_release": "固件 OTA 发布批次",
    "payment_platform_bill_line": "支付平台对账单明细行",
    "payment_reconciliation": "支付对账结果/差异单",
    "payment_risk_config": "支付风控阈值配置",
    "payscore_order": "微信支付分订单",
    "phone_verify_log": "手机号验证审计流水",
    "points_redeem_item": "积分兑换商品/权益定义",
    "pull_off_task": "下架/撤柜任务",
    "purchase_order": "采购单头",
    "purchase_order_line": "采购单明细",
    "purchase_return": "采购退货单头",
    "purchase_return_line": "采购退货明细",
    "rate_limit_record": "限流命中记录",
    "recharge_order": "用户余额充值单",
    "recognition_result": "视觉识别结果（会话维度）",
    "repair_ticket_event": "维修工单事件流水",
    "replenishment_route": "补货路线规划",
    "replenishment_task": "补货任务单头",
    "replenishment_task_line": "补货任务明细（SKU/数量）",
    "revenue_share_detail": "分润明细（规则落地）",
    "revenue_share_rule": "分润规则定义",
    "risk_event": "风控事件记录",
    "site_contract": "点位场地合同",
    "site_rent_split_rule": "场地租金/分账规则",
    "sku_delist_review": "商品下架评审单",
    "sku_vision_mapping": "SKU 与视觉识别类名映射",
    "sla_daily_snapshot": "SLA 服务时限日快照",
    "sms_verification_code": "短信验证码发送/校验",
    "supplier": "供应商主数据",
    "supplier_payable": "供应商应付账款",
    "supplier_payment": "供应商付款单",
    "sys_dict_data": "系统字典数据项",
    "sys_dict_type": "系统字典类型",
    "sys_oper_log": "系统操作日志（若依风格）",
    "system_config": "系统参数键值配置",
    "transaction_step": "分布式事务步骤明细",
    "user_account": "用户资金账户（余额）",
    "user_blacklist": "用户黑名单",
    "user_feedback": "用户反馈工单",
    "user_info": "用户主数据（手机号/实名等）",
    "user_login_log": "用户登录日志",
    "user_notify_pref": "用户通知偏好",
    "warehouse_bin": "仓库货位",
    "warehouse_bin_stock": "货位库存",
    "warehouse_in_transit": "仓间在途库存",
    "warehouse_inbound": "入库单头",
    "warehouse_inbound_line": "入库单明细",
    "warehouse_movement": "仓库库存移动流水",
    "warehouse_outbound": "出库单头",
    "warehouse_outbound_line": "出库单明细",
    "warehouse_stocktake": "盘点单头",
    "warehouse_stocktake_line": "盘点单明细",
    "warehouse_transfer_line": "仓间调拨明细",
    "warehouse_transfer_order": "仓间调拨单头",
}

COMMON_COL: dict[str, str] = {
    "created_at": "创建时间",
    "updated_at": "更新时间",
    "deleted_at": "软删除时间",
    "created_by": "创建人用户ID",
    "updated_by": "更新人用户ID",
    "operator_id": "操作人用户ID",
    "reviewer_id": "审核人用户ID",
    "assignee_user_id": "经办人用户ID",
    "handler_id": "处理人用户ID",
    "user_id": "用户ID",
    "device_id": "设备ID",
    "merchant_id": "商户ID",
    "session_id": "购物会话ID",
    "order_id": "订单ID",
    "sku_id": "商品SKU ID",
    "warehouse_id": "仓库ID",
    "supplier_id": "供应商ID",
    "campaign_id": "投放计划ID",
    "ticket_id": "工单ID",
    "route_id": "路线ID",
    "task_id": "任务ID",
    "status": "业务状态",
    "remark": "备注",
    "note": "备注说明",
    "reason": "原因说明",
    "amount_cents": "金额（分）",
    "balance_cents": "余额（分）",
    "price_cents": "单价（分）",
    "qty": "数量",
    "quantity": "数量",
    "phone": "手机号",
    "phone_number": "手机号",
    "name": "名称",
    "title": "标题",
    "content": "内容",
    "priority": "优先级",
    "channel": "渠道",
    "source": "来源",
    "expires_at": "过期时间",
    "start_at": "开始时间",
    "end_at": "结束时间",
    "version": "版本号",
    "idempotency_key": "幂等键",
    "trace_id": "链路追踪ID",
    "sort_order": "排序序号",
    "enabled": "是否启用",
    "active": "是否有效",
    "verified": "是否已验证/实名",
    "avatar_url": "头像URL",
    "password_hash": "密码哈希",
    "wx_open_id": "微信 OpenID",
    "alipay_user_id": "支付宝用户ID",
    "slot_code": "货道编码",
    "slot_id": "货道ID",
    "lot_no": "批次号",
    "expire_date": "效期日期",
    "biz_type": "业务类型",
    "biz_id": "业务单据ID",
    "perm_code": "权限码",
    "role_code": "角色码",
    "dict_type": "字典类型",
    "dict_label": "字典标签",
    "dict_value": "字典值",
    "config_key": "配置键",
    "config_value": "配置值",
    "notes": "备注",
    "description": "描述",
    "address": "地址",
    "city": "城市",
    "province": "省份",
    "gender": "性别",
    "level": "级别",
    "type": "类型",
    "action": "动作/操作类型",
    "event_type": "事件类型",
    "from_status": "变更前状态",
    "to_status": "变更后状态",
    "from_warehouse_id": "源仓库ID",
    "to_warehouse_id": "目标仓库ID",
    "shipped_at": "发货时间",
    "received_at": "收货时间",
    "reviewed_at": "审核时间",
    "resolved_at": "解决时间",
    "closed_at": "关闭时间",
    "completed_at": "完成时间",
    "failed_at": "失败时间",
    "last_active_at": "最近活跃时间",
    "overall_confidence": "整体置信度",
    "model_version": "模型版本",
    "need_review": "是否需人工复核",
    "fusion_mode": "融合模式",
    "external_ref": "外部单号/引用",
    "external_mch_id": "外部商户号",
    "fault_type": "故障类型",
    "assignee": "负责人",
    "capacity": "容量",
    "stock_qty": "库存数量",
    "unit_price_cents": "单价（分）",
    "total_amount_cents": "总金额（分）",
    "refund_amount_cents": "退款金额（分）",
    "request_id": "申请单ID",
    "allocation_id": "分摊明细ID",
    "message": "消息内容",
    "error_message": "错误信息",
    "fail_reason": "失败原因",
    "review_note": "审核备注",
    "ip": "IP地址",
    "user_agent": "User-Agent",
    "path": "路径",
    "method": "HTTP方法/调用方法",
    "request_uri": "请求URI",
    "oper_name": "操作人名称",
    "oper_ip": "操作IP",
    "json_result": "响应JSON",
    "cost_time": "耗时（毫秒）",
}


def col_comment(col: str, ty: str) -> str:
    if col in COMMON_COL:
        return COMMON_COL[col]
    if col.endswith("_cents"):
        return col[: -len("_cents")].replace("_", " ") + "金额（分）"
    if col.endswith("_id"):
        return col[: -len("_id")].replace("_", " ") + " ID"
    if col.endswith("_url"):
        return col[: -len("_url")].replace("_", " ") + " URL"
    if col.endswith("_code"):
        return col[: -len("_code")].replace("_", " ") + "编码"
    if col.endswith("_name"):
        return col[: -len("_name")].replace("_", " ") + "名称"
    if col.endswith("_at"):
        return col[: -len("_at")].replace("_", " ") + "时间"
    if col.endswith("_time"):
        return col[: -len("_time")].replace("_", " ") + "时间"
    if col.endswith("_date"):
        return col[: -len("_date")].replace("_", " ") + "日期"
    if col.endswith("_status"):
        return col[: -len("_status")].replace("_", " ") + "状态"
    if col.startswith("is_") or col.startswith("has_") or col.startswith("can_"):
        return "是否" + col.split("_", 1)[1].replace("_", "")
    if ty in ("jsonb", "json"):
        return col.replace("_", " ") + "（JSON）"
    if col in ("payload", "meta", "metadata", "extra", "config", "rule_config", "items", "detail"):
        return col + "（扩展配置）"
    return col.replace("_", " ")


def sql_quote(text: str) -> str:
    return text.replace("'", "''")


def main() -> None:
    cols: list[tuple[str, str, str, str]] = []
    for line in COLS_TSV.read_text(encoding="utf-8").splitlines():
        if not line.strip():
            continue
        parts = line.split("\t")
        if len(parts) < 3:
            continue
        table, column, udt = parts[0], parts[1], parts[2]
        existing = parts[3] if len(parts) > 3 else ""
        cols.append((table, column, udt, existing))

    # Only emit TABLE comments for tables that currently lack them
    # (listed in TABLE_COMMENTS). Already-commented tables keep existing comments.
    tables_needing_comment = sorted(TABLE_COMMENTS.keys())
    lines: list[str] = [
        "-- V236: 补全缺失的表注释与字段注释（Navicat/库文档可读性）",
        "-- 幂等：COMMENT ON 可重复执行。",
        "-- 已有表注释的表不覆盖；本文件仅补无注释表 + 全库无注释字段。",
        "",
        "-- ========== 表注释（此前无注释） ==========",
    ]

    for table in tables_needing_comment:
        comment = TABLE_COMMENTS[table]
        lines.append(f"COMMENT ON TABLE {table} IS '{sql_quote(comment)}';")

    # Also translate a few English-only table comments to Chinese
    lines.append("")
    lines.append("-- ========== 英文表注释改中文（可读性） ==========")
    en_to_zh = {
        "approval_definition": "审批流定义（按 biz_type）",
        "approval_instance": "审批实例（业务单据运行中/已完成）",
        "approval_node": "审批节点（顺序；assignee_type=PERM|ROLE）",
        "approval_task": "审批待办（按人；ANY 规则一人通过即完成本节点）",
    }
    for table, comment in en_to_zh.items():
        lines.append(f"COMMENT ON TABLE {table} IS '{sql_quote(comment)}';")

    lines.append("")
    lines.append("-- ========== 字段注释（此前无注释的字段） ==========")
    written = 0
    for table, column, udt, existing in cols:
        if existing:
            continue
        comment = col_comment(column, udt)
        lines.append(f"COMMENT ON COLUMN {table}.{column} IS '{sql_quote(comment)}';")
        written += 1

    OUT.write_text("\n".join(lines) + "\n", encoding="utf-8")
    print(f"wrote {OUT}")
    print(f"table_comments={len(tables_needing_comment)}+{len(en_to_zh)}")
    print(f"columns={written}")


if __name__ == "__main__":
    main()
