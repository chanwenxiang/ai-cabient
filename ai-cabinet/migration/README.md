# 从 ego-automat 迁移到新 ai-cabinet

旧系统代码**不修改**，此处仅提供数据映射参考，供上线前一次性 ETL 使用。

## 表映射

| 旧表 (ego-automat) | 新表 (ai-cabinet) | 说明 |
|--------------------|-------------------|------|
| `ego_machine_base_info` | `device_info` | machine_code → device_id |
| `m8_user_info` | `user_info`（待建） | 用户主数据 |
| `m8_user_account` | `user_account`（待建） | 余额账户 |
| `m8_door_current_status` | — | 不迁移，新会话从 CREATED 开始 |
| `m8_sell_trace` | 订单历史归档 | 只读导入 |
| `ego_machine_aisle_info` + `goods_sku_info` | `sku_catalog`（待建） | 商品 + 重量参考 |

## 设备 ID 映射

```
旧 machine_code  →  新 device_id（建议保持一致，减少设备端改动）
旧 machine_id    →  新 device_info 内部字段 legacy_id
```

## 迁移步骤（建议）

1. **商品先行**：导入 SKU 到 `sku_catalog`，补充视觉标准图
2. **用户/account**：导入用户与余额，校验一致性
3. **设备注册**：批量写入 `device_info`，capabilities 标注 `{weight: true}` 或 `{vision: true}`
4. **双系统并行**：同一设备不同时接两套后端；按批次切换 MQTT 接入地址
5. **历史订单**：只读归档，新订单走 `shopping_session`

## 参考文件（旧代码，只读）

- 开门逻辑：`easygo/ego-automat/ego-automat-m8-domain/.../M8MachineService.java`
- 门状态表：`easygo/ego-automat/doc/db/M8_related.sql`
- 设备协议：`ego-automat-android/.../ChzhDevice8.java`

## 脚本目录

```
migration/
├── scripts/
│   ├── export_users.sql          # 从旧库导出用户+余额
│   ├── export_devices.sql        # 导出设备
│   ├── export_skus.sql           # 导出 SKU
│   └── import_to_aicabinet.sql   # staging 表 + 写入新库
└── README.md
```

用法见 `docs/PHASE5.md`。
