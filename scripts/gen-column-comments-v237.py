# -*- coding: utf-8 -*-
"""Generate V237: polish all column comments to readable Chinese."""
from __future__ import annotations

from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
COLS_TSV = ROOT / ".tmp-all-cols.tsv"
OUT = (
    ROOT
    / "services/trade-service/src/main/resources/db/migration"
    / "V237__polish_column_comments_zh.sql"
)

# Exact column-name → Chinese (applies across tables unless overridden)
COL: dict[str, str] = {
    "id": "主键ID",
    "log_id": "日志ID",
    "event_id": "事件ID",
    "item_id": "明细ID",
    "asset_id": "素材ID",
    "campaign_id": "投放计划ID",
    "announce_id": "公告ID",
    "def_id": "定义ID",
    "instance_id": "实例ID",
    "node_id": "节点ID",
    "task_id": "任务ID",
    "ticket_id": "工单ID",
    "request_id": "申请单ID",
    "allocation_id": "分摊ID",
    "record_id": "记录ID",
    "check_id": "检查ID",
    "reading_id": "读数ID",
    "report_id": "报告ID",
    "plan_id": "计划ID",
    "entry_id": "条目ID",
    "message_id": "消息ID",
    "attachment_id": "附件ID",
    "movement_id": "变动ID",
    "write_off_id": "核销单ID",
    "invoice_id": "开票申请ID",
    "ledger_id": "流水ID",
    "account_id": "账户ID",
    "template_id": "模板ID",
    "exception_id": "异常单ID",
    "org_id": "组织节点ID",
    "perm_id": "权限ID",
    "role_id": "角色ID",
    "release_id": "发布批次ID",
    "bill_line_id": "对账明细ID",
    "recon_id": "对账单ID",
    "config_id": "配置ID",
    "contract_id": "合同/签约ID",
    "redeem_id": "兑换项ID",
    "po_id": "采购单ID",
    "return_id": "退货单ID",
    "route_id": "路线ID",
    "line_id": "明细行ID",
    "rule_id": "规则ID",
    "detail_id": "明细ID",
    "site_id": "点位ID",
    "review_id": "评审单ID",
    "mapping_id": "映射ID",
    "snapshot_id": "快照ID",
    "code_id": "验证码记录ID",
    "supplier_id": "供应商ID",
    "payable_id": "应付单ID",
    "payment_id": "付款单ID",
    "dict_code": "字典编码",
    "dict_id": "字典项ID",
    "oper_id": "操作日志ID",
    "step_id": "事务步骤ID",
    "feedback_id": "反馈单ID",
    "bin_id": "货位ID",
    "inbound_id": "入库单ID",
    "outbound_id": "出库单ID",
    "stocktake_id": "盘点单ID",
    "transfer_id": "调拨单ID",
    "user_id": "用户ID",
    "device_id": "设备ID",
    "merchant_id": "商户ID",
    "session_id": "购物会话ID",
    "order_id": "订单ID",
    "sku_id": "商品SKU ID",
    "warehouse_id": "仓库ID",
    "operator_id": "操作人用户ID",
    "created_by": "创建人用户ID",
    "updated_by": "更新人用户ID",
    "reviewer_id": "审核人用户ID",
    "assignee_user_id": "经办人用户ID",
    "handler_id": "处理人用户ID",
    "author_id": "作者用户ID",
    "from_warehouse_id": "源仓库ID",
    "to_warehouse_id": "目标仓库ID",
    "parent_id": "父节点ID",
    "category_id": "类目ID",
    "slot_id": "货道ID",
    "activity_id": "活动ID",
    "coupon_id": "优惠券实例ID",
    "department_id": "部门ID",
    "status": "业务状态",
    "name": "名称",
    "title": "标题",
    "content": "内容",
    "remark": "备注",
    "note": "备注说明",
    "notes": "备注",
    "reason": "原因说明",
    "description": "描述",
    "detail": "详情/扩展信息",
    "priority": "优先级",
    "channel": "渠道",
    "source": "来源",
    "type": "类型",
    "level": "级别",
    "action": "动作类型",
    "event_type": "事件类型",
    "biz_type": "业务类型",
    "biz_id": "业务单据ID",
    "target_type": "目标对象类型",
    "target_id": "目标对象ID",
    "target_scope": "目标范围",
    "target_device": "目标设备",
    "device_scope": "设备范围（ALL/SPECIFIC等）",
    "announce_type": "公告类型",
    "category_name": "类目名称",
    "min_confidence": "最低置信度",
    "overall_confidence": "整体置信度",
    "model_version": "模型版本",
    "need_review": "是否需人工复核",
    "fusion_mode": "识别融合模式",
    "phone": "手机号",
    "phone_number": "手机号",
    "address": "地址",
    "city": "城市",
    "province": "省份",
    "gender": "性别",
    "avatar_url": "头像URL",
    "password_hash": "密码哈希",
    "wx_open_id": "微信OpenID",
    "alipay_user_id": "支付宝用户ID",
    "verified": "是否已实名/验证",
    "enabled": "是否启用",
    "active": "是否有效",
    "amount_cents": "金额（分）",
    "balance_cents": "余额（分）",
    "price_cents": "单价（分）",
    "unit_price_cents": "单价（分）",
    "total_amount_cents": "总金额（分）",
    "refund_amount_cents": "退款金额（分）",
    "qty": "数量",
    "quantity": "数量",
    "stock_qty": "库存数量",
    "capacity": "容量",
    "slot_code": "货道编码",
    "lot_no": "批次号",
    "expire_date": "效期日期",
    "expire_at": "过期时间",
    "expires_at": "过期时间",
    "start_at": "开始时间",
    "end_at": "结束时间",
    "publish_at": "发布时间",
    "created_at": "创建时间",
    "updated_at": "更新时间",
    "deleted_at": "软删除时间",
    "reviewed_at": "审核时间",
    "resolved_at": "解决时间",
    "closed_at": "关闭时间",
    "completed_at": "完成时间",
    "failed_at": "失败时间",
    "shipped_at": "发货时间",
    "received_at": "收货时间",
    "last_active_at": "最近活跃时间",
    "bind_time": "绑定时间",
    "last_login_time": "最近登录时间",
    "login_count": "登录次数",
    "version": "版本号",
    "idempotency_key": "幂等键",
    "trace_id": "链路追踪ID",
    "sort_order": "排序序号",
    "perm_code": "权限码",
    "role_code": "角色码",
    "dict_type": "字典类型",
    "dict_label": "字典标签",
    "dict_value": "字典值",
    "config_key": "配置键",
    "config_value": "配置值",
    "external_ref": "外部单号/引用",
    "external_mch_id": "外部商户号",
    "fault_type": "故障类型",
    "assignee": "负责人",
    "from_status": "变更前状态",
    "to_status": "变更后状态",
    "error_message": "错误信息",
    "fail_reason": "失败原因",
    "review_note": "审核备注",
    "message": "消息内容",
    "payload": "请求/事件载荷（JSON）",
    "meta": "元数据（JSON）",
    "metadata": "元数据（JSON）",
    "extra": "扩展字段（JSON）",
    "config": "配置（JSON）",
    "rule_config": "规则配置（JSON）",
    "items": "明细列表（JSON）",
    "ip": "IP地址",
    "user_agent": "User-Agent",
    "path": "路径",
    "method": "方法",
    "request_uri": "请求URI",
    "oper_name": "操作人名称",
    "oper_ip": "操作IP",
    "json_result": "响应JSON",
    "cost_time": "耗时（毫秒）",
    "duration_sec": "时长（秒）",
    "play_order": "播放顺序",
    "asset_type": "素材类型",
    "file_url": "文件URL",
    "mime_type": "MIME类型",
    "file_size": "文件大小（字节）",
    "checksum": "校验和",
    "assignee_type": "指派类型（PERM/ROLE）",
    "assignee_value": "指派值（权限码/角色码）",
    "any_rule": "是否任一通过即可",
    "node_order": "节点顺序",
    "biz_key": "业务键",
    "result": "结果",
    "comment": "批注/意见",
    "scope": "数据范围",
    "value": "值",
    "label": "标签",
    "code": "编码",
    "url": "URL",
    "state": "状态机状态",
    "temperature": "温度",
    "humidity": "湿度",
    "reading_at": "采样时间",
    "firmware_version": "固件版本",
    "app_version": "应用版本",
    "reported_at": "上报时间",
    "row_no": "行号",
    "col_no": "列号",
    "slot_type": "货道类型",
    "assigned_sku_id": "分配的SKU ID",
    "par_level": "标准补货水位",
    "min_level": "最低水位",
    "max_level": "最高水位",
    "last_physical_qty": "最近实盘数量",
    "last_physical_at": "最近实盘时间",
    "last_restock_at": "最近补货时间",
    "request_no": "申请单号",
    "review_remark": "审核备注",
    "refunded_at": "退款完成时间",
    "order_no": "订单号",
    "trade_no": "交易流水号",
    "out_trade_no": "商户订单号",
}


def guess(col: str, udt: str) -> str:
    if col in COL:
        return COL[col]
    if col.endswith("_cents"):
        return col[: -6].replace("_", "") + "金额（分）"
    if col.endswith("_id"):
        return col[: -3].replace("_", "") + "ID"
    if col.endswith("_no"):
        return col[: -3].replace("_", "") + "编号"
    if col.endswith("_remark") or col.endswith("_note") or col.endswith("_notes"):
        return col.split("_")[0] + "备注"
    if col.endswith("_url"):
        return col[: -4].replace("_", "") + "URL"
    if col.endswith("_code"):
        return col[: -5].replace("_", "") + "编码"
    if col.endswith("_name"):
        return col[: -5].replace("_", "") + "名称"
    if col.endswith("_type"):
        return col[: -5].replace("_", "") + "类型"
    if col.endswith("_status"):
        return col[: -7].replace("_", "") + "状态"
    if col.endswith("_at"):
        stem = col[: -3]
        if stem.startswith("last_"):
            return "最近" + stem[5:].replace("_", "") + "时间"
        return stem.replace("_", "") + "时间"
    if col.endswith("_time"):
        return col[: -5].replace("_", "") + "时间"
    if col.endswith("_date"):
        return col[: -5].replace("_", "") + "日期"
    if col.endswith("_qty"):
        return col[: -4].replace("_", "") + "数量"
    if col.endswith("_count"):
        return col[: -6].replace("_", "") + "次数"
    if col.startswith("is_") or col.startswith("has_") or col.startswith("can_"):
        return "是否" + col.split("_", 1)[1]
    if udt in ("jsonb", "json"):
        return col.replace("_", "") + "（JSON）"
    return col.replace("_", " ")


def q(s: str) -> str:
    """Emit PostgreSQL Unicode-escaped string literal (ASCII-safe on Windows)."""
    parts = []
    for ch in s:
        o = ord(ch)
        if ch == "'":
            parts.append("''")
        elif o < 128 and ch not in "\\":
            parts.append(ch)
        else:
            parts.append(f"\\{o:04x}")
    body = "".join(parts)
    return f"U&'{body}'"


def main() -> None:
    rows = []
    for line in COLS_TSV.read_text(encoding="utf-8").splitlines():
        if not line.strip():
            continue
        parts = line.split("\t")
        if len(parts) < 3:
            continue
        rows.append((parts[0], parts[1], parts[2]))

    lines = [
        "-- V237: polish column comments to Chinese (Unicode-escaped, encoding-safe)",
        "-- Idempotent: COMMENT ON can be re-run.",
        "",
    ]
    for table, column, udt in rows:
        comment = guess(column, udt)
        lines.append(f"COMMENT ON COLUMN {table}.{column} IS {q(comment)};")

    OUT.write_text("\n".join(lines) + "\n", encoding="utf-8")
    # verify pure BMP escapes work; file may contain only ASCII outside comments
    try:
        OUT.read_text(encoding="ascii")
        print("file is ASCII-safe")
    except UnicodeDecodeError as e:
        print("WARN not ascii:", e)
    print(f"wrote {OUT} statements={len(rows)}")


if __name__ == "__main__":
    main()
