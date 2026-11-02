-- MIGRATION_KIND: schema
-- MIGRATION_REVIEWED: yes
-- LOCK_RISK: low（仅 COMMENT ON，未改结构与数据）
-- NOTES: 补 V326 之后新增表的表注释与全库缺列注释（22 表 / 463 列），幂等可重跑。
--       表注释沿用 V236 的 Unicode 转义写法；列注释沿用 V237。
-- TABLES: 22 张既有表（只写 COMMENT ON，不建表、不改行数）
--
COMMENT ON TABLE commission_clawback IS U&'\4f63\91d1\51b2\56de\7d2f\8ba1\ff08CB-040\ff09\ff1a\6309\7ebf\957f/\8865\8d27\5458\00d7\8bbe\5907\00d7\65e5\671f\7d2f\8ba1\9000\6b3e\5e94\51b2\56de\7684\4f63\91d1\ff0c\65e5\6279\5e42\7b49\9012\589e';
COMMENT ON TABLE device_fault_order IS U&'\8bbe\5907\4fa7\6545\969c\5355\ff08CB-077\ff09\ff1a\56fa\4ef6\81ea\52a8\4e0a\62a5\7684\6545\969c\9065\6d4b\ff0c\53ea\4f5c\5c55\793a/\8bca\65ad';
COMMENT ON TABLE external_channel_flow IS U&'\5916\90e8\6e20\9053\805a\5408\6d41\6c34\ff08CB-036\ff09\ff1a\6279\6b21\786e\8ba4\540e\6309\4ed3\00d7SKU\00d7\6e20\9053\00d7\64cd\4f5c\7c7b\578b\805a\5408\ff0c\6708\7ed3\53d6\6570\6e90';
COMMENT ON TABLE external_import_batch IS U&'\5916\90e8\6e20\9053\5bfc\5165\6279\6b21\ff08CB-036\ff09\ff1aPARSED \5f85\786e\8ba4 / COMMITTED \5df2\5165\8d26\ff0c\4fdd\7559\89e3\6790\884c JSONB';
COMMENT ON TABLE external_import_template IS U&'\5916\90e8\6e20\9053\89e3\6790\6a21\677f\ff08CB-036\ff09\ff1a\6bcf\6e20\9053\4e00\6761\ff0c\5217\6620\5c04\4e0e\6570\91cf\53e3\5f84\914d\7f6e';
COMMENT ON TABLE external_product IS U&'\5916\90e8\5546\54c1\5e93\ff08CB-036\ff09\ff1a\5404\6e20\9053\81ea\89c1\ff0c\9996\6b21\51fa\73b0\5373\5efa\6863\ff0c\7ecf\6620\5c04\8868\5173\8054\6211\65b9 SKU';
COMMENT ON TABLE external_product_mapping IS U&'\5916\90e8\5546\54c1\4e0e\6211\65b9 SKU \6620\5c04\ff08CB-036\ff09\ff1a\4e00\4e2a\5916\90e8\5546\54c1\81f3\591a\7ed1\4e00\4e2a SKU';
COMMENT ON TABLE jiangyi_order_video IS U&'\5c06\9091\8bbe\5907\89c6\9891\4e0a\62a5\53f0\8d26\ff08CB-024\ff09\ff1a\6309\8ba2\5355\53f7+\5206\7247\5e8f\53f7\5e42\7b49\ff0c\7a7a\4e32=\8be5\7247\5931\8d25';
COMMENT ON TABLE line_route IS U&'\8865\8d27\7ebf\8def\ff08V358\ff09\ff1a\7ebf\8def\540d\79f0\3001\8d1f\8d23\4eba\4e0e\72b6\6001';
COMMENT ON TABLE line_route_member IS U&'\7ebf\8def\6210\5458\ff08V358\ff09\ff1a\7ebf\957f/\8865\8d27\5458\89d2\8272\4e0e\63d0\6210\89c4\5219';
COMMENT ON TABLE machine_payee_bill IS U&'\67dc\673a\573a\5730\65b9\8d26\5355\ff08V356\ff09\ff1a\7535\8d39/\9500\552e\5206\6210\ff0c\7ebf\4e0b\6536\6b3e\540e\4eba\5de5\6807\8bb0\7ed3\7b97';
COMMENT ON TABLE machine_position_config IS U&'\67dc\673a\70b9\4f4d\8d39\7528/\5206\6210\914d\7f6e\ff08V356\ff09\ff1a\7535\8d39\4e0e\573a\5730\65b9\5206\6210\53c2\6570';
COMMENT ON TABLE merchant_fee_ledger IS U&'\5546\6237\8d39\7528\53f0\8d26\ff08V355\ff09\ff1a\65e5\7ed3\6263\6b3e\6210\529f\540e\5199\884c\ff0c\884c\5373\6536\636e';
COMMENT ON TABLE promotion_placement IS U&'\6d3b\52a8\5c55\793a\4f4d\ff08V419/V420\ff09\ff1aBANNER \5fc5\9009\ff0c\4e24\4e2a\5f39\7a97\4f4d\6bcf\6d3b\52a8\6700\591a\4e00\4e2a';
COMMENT ON TABLE slot_template IS U&'\8d27\9053\94fa\8d27\6a21\677f\5934\ff08C3\ff09\ff1a\6a21\677f\540d\79f0\4e0e\521b\5efa\4eba';
COMMENT ON TABLE slot_template_line IS U&'\8d27\9053\94fa\8d27\6a21\677f\884c\ff08C3\ff09\ff1a\8d27\9053\00d7SKU\00d7\552e\4ef7\00d7\6807\51c6\8865\8d27\6c34\4f4d';
COMMENT ON TABLE staff_commission_daily IS U&'\8865\8d27\5458\4f63\91d1\65e5\6c47\603b\ff08V358\ff09\ff1a\6309\7ebf\8def/\67dc/\65e5\53d1\4f63\ff0c\652f\6301 GMV \6216\6309\4ef6\6a21\5f0f';
COMMENT ON TABLE supplier_payable_entry IS U&'\4f9b\5e94\5546\5e94\4ed8\4e8b\4ef6\6d41\6c34\ff08V326\ff09\ff1aRECEIVE/RETURN/OPENING append-only\ff0c\652f\6491\671f\521d\671f\672b\5bf9\8d26';
COMMENT ON TABLE wallet_account IS U&'\4e09\57df\7edf\4e00\94b1\5305\8d26\6237\ff08V388\ff09\ff1a\590d\5408\4e3b\952e (domain, owner_id)';
COMMENT ON TABLE wallet_ledger IS U&'\4e09\57df\7edf\4e00\94b1\5305\6d41\6c34\ff08V388\ff09\ff1a\5546\6237/\7ebf\957f/\8865\8d27\5458\5171\7528\ff0c\8bb0\5f55\53d8\52a8\540e\4f59\989d';
COMMENT ON TABLE warehouse_monthly_close IS U&'\4ed3\5e93\6708\7ed3\5355\ff08V327\ff09\ff1a\4ed3\5e93\00d7\6708\552f\4e00\ff0cDRAFT/APPROVED \4e24\6b65\6cd5';
COMMENT ON TABLE warehouse_monthly_close_line IS U&'\4ed3\5e93\6708\7ed3\660e\7ec6\884c\ff08V327/V348\ff09\ff1a\6570\91cf\5f52\96c6\3001\635f\8017\62c6\56db\7c7b\3001\5dee\5f02\5904\7f6e\72b6\6001';
--
-- ad_campaign
COMMENT ON COLUMN ad_campaign.channel IS U&'\6295\653e\7aef\ff08CABINET_SCREEN=\67dc\673a\5c4f / MINI_PROGRAM=\5c0f\7a0b\5e8f\8f6e\64ad\4f4d\ff09';
COMMENT ON COLUMN ad_campaign.link_url IS U&'\5c0f\7a0b\5e8f\8f6e\64ad\4f4d\70b9\51fb\8df3\8f6c\6df1\94fe\ff08\53ef\7a7a\ff09';
-- ad_revenue_daily
COMMENT ON COLUMN ad_revenue_daily.ad_revenue_id IS U&'\4e3b\952eID';
COMMENT ON COLUMN ad_revenue_daily.biz_date IS U&'\4e1a\52a1\65e5\671f';
COMMENT ON COLUMN ad_revenue_daily.ad_slot IS U&'\5e7f\544a\4f4d\7c7b\578b\540d\ff0c\5982 SLOT_ID_WEAPP_BANNER';
COMMENT ON COLUMN ad_revenue_daily.ad_unit_id IS U&'\5e7f\544a\5355\5143 ID';
COMMENT ON COLUMN ad_revenue_daily.ad_unit_name IS U&'\5e7f\544a\5355\5143\540d\79f0';
COMMENT ON COLUMN ad_revenue_daily.req_succ_count IS U&'\5e7f\544a\8bf7\6c42\6210\529f\6b21\6570';
COMMENT ON COLUMN ad_revenue_daily.exposure_count IS U&'\66dd\5149\6b21\6570';
COMMENT ON COLUMN ad_revenue_daily.click_count IS U&'\70b9\51fb\6b21\6570';
COMMENT ON COLUMN ad_revenue_daily.created_at IS U&'\521b\5efa\65f6\95f4';
COMMENT ON COLUMN ad_revenue_daily.updated_at IS U&'\66f4\65b0\65f6\95f4';
-- cabinet_order
COMMENT ON COLUMN cabinet_order.activity_discount_cents IS U&'\6d3b\52a8\4f18\60e0\5408\8ba1\ff08\5206\ff09';
-- cabinet_order_line
COMMENT ON COLUMN cabinet_order_line.activity_id IS U&'\547d\4e2d\7684\6d3b\52a8ID';
COMMENT ON COLUMN cabinet_order_line.activity_discount_cents IS U&'\884c\6d3b\52a8\4f18\60e0\989d\ff08\5206\ff09';
-- commission_clawback
COMMENT ON COLUMN commission_clawback.id IS U&'\4e3b\952eID';
COMMENT ON COLUMN commission_clawback.owner_type IS U&'MANAGER=\7ebf\957f / STAFF=\8865\8d27\5458(GMV \6a21\5f0f)';
COMMENT ON COLUMN commission_clawback.owner_id IS U&'manager_id \6216 staff user_id';
COMMENT ON COLUMN commission_clawback.device_id IS U&'\8bbe\5907ID';
COMMENT ON COLUMN commission_clawback.biz_date IS U&'\539f\4f63\91d1\5f52\5c5e\65e5';
COMMENT ON COLUMN commission_clawback.refund_gmv_cents IS U&'\7d2f\8ba1\51b2\56de\5bf9\5e94\7684\9000\6b3e GMV\ff08\8bb0\5f55\7559\75d5\ff09';
COMMENT ON COLUMN commission_clawback.order_count IS U&'\7d2f\8ba1\51b2\56de\5355\6570\ff08\7ebf\957f\4fa7\ff09';
COMMENT ON COLUMN commission_clawback.clawback_cents IS U&'\7d2f\8ba1\5df2\51b2\56de\91d1\989d\ff08\6b63\6570\ff09';
COMMENT ON COLUMN commission_clawback.status IS U&'\72b6\6001\ff08POSTED=\5df2\51b2\56de\ff09';
COMMENT ON COLUMN commission_clawback.created_at IS U&'\521b\5efa\65f6\95f4';
COMMENT ON COLUMN commission_clawback.updated_at IS U&'\66f4\65b0\65f6\95f4';
-- consumer_preauth_hold
COMMENT ON COLUMN consumer_preauth_hold.session_id IS U&'\8d2d\7269\4f1a\8bddID';
COMMENT ON COLUMN consumer_preauth_hold.user_id IS U&'\7528\6237ID';
COMMENT ON COLUMN consumer_preauth_hold.hold_cents IS U&'\672c\4f1a\8bdd\51bb\7ed3\91d1\989d\ff08\5206\ff09';
COMMENT ON COLUMN consumer_preauth_hold.created_at IS U&'\521b\5efa\65f6\95f4';
COMMENT ON COLUMN consumer_preauth_hold.updated_at IS U&'\66f4\65b0\65f6\95f4';
-- device_daily_online_rate
COMMENT ON COLUMN device_daily_online_rate.kpi_date IS U&'\7edf\8ba1\65e5\671f';
COMMENT ON COLUMN device_daily_online_rate.device_id IS U&'\8bbe\5907ID';
COMMENT ON COLUMN device_daily_online_rate.offline_minutes IS U&'\79bb\7ebf\5206\949f\6570';
COMMENT ON COLUMN device_daily_online_rate.online_minutes IS U&'\5728\7ebf\5206\949f\6570';
COMMENT ON COLUMN device_daily_online_rate.rate IS U&'\5728\7ebf\7387\ff080~1\ff09';
COMMENT ON COLUMN device_daily_online_rate.computed_at IS U&'\8ba1\7b97\65f6\95f4';
-- device_data_fee_bill
COMMENT ON COLUMN device_data_fee_bill.bill_id IS U&'\8d26\5355ID';
COMMENT ON COLUMN device_data_fee_bill.device_id IS U&'\8bbe\5907ID';
COMMENT ON COLUMN device_data_fee_bill.device_name IS U&'\8bbe\5907\540d\79f0\5197\4f59';
COMMENT ON COLUMN device_data_fee_bill.merchant_id IS U&'\5546\6237ID';
COMMENT ON COLUMN device_data_fee_bill.bill_month IS U&'\8d26\671f\ff08YYYY-MM\ff09';
COMMENT ON COLUMN device_data_fee_bill.amount_cents IS U&'\91d1\989d\ff08\5206\ff09';
COMMENT ON COLUMN device_data_fee_bill.status IS U&'\72b6\6001\ff1aUNPAID/PAID/VOID';
COMMENT ON COLUMN device_data_fee_bill.paid_at IS U&'\652f\4ed8\65f6\95f4';
COMMENT ON COLUMN device_data_fee_bill.remark IS U&'\5907\6ce8';
COMMENT ON COLUMN device_data_fee_bill.created_at IS U&'\521b\5efa\65f6\95f4';
COMMENT ON COLUMN device_data_fee_bill.updated_at IS U&'\66f4\65b0\65f6\95f4';
-- device_fault_order
COMMENT ON COLUMN device_fault_order.id IS U&'\4e3b\952eID';
COMMENT ON COLUMN device_fault_order.device_id IS U&'\4e0a\62a5\8bbe\5907ID\ff08gateway \4fa7\89e3\6790\ff0c\4e0d\4fe1\4efb body\ff09';
COMMENT ON COLUMN device_fault_order.identifier IS U&'\5382\5546\6807\8bc6\ff08\5982 CQYB10720\ff09\ff0c\53ef\80fd\4e3a\7a7a';
COMMENT ON COLUMN device_fault_order.order_no IS U&'\5173\8054\5355\53f7\ff08\6211\65b9 sessionId\ff0c\53ef\7a7a\ff09';
COMMENT ON COLUMN device_fault_order.error_code IS U&'\56fa\4ef6\9519\8bef\7801\ff08\53ef\7a7a\ff09';
COMMENT ON COLUMN device_fault_order.error_msg IS U&'\56fa\4ef6\9519\8bef\63cf\8ff0\ff08\5df2\622a\65ad\ff09';
COMMENT ON COLUMN device_fault_order.source IS U&'\6765\6e90\534f\8bae\ff08JIANGYI_FIRMWARE\ff09';
COMMENT ON COLUMN device_fault_order.reported_at IS U&'\8bbe\5907\4e0a\62a5\65f6\95f4\ff08\670d\52a1\7aef\6536\5230\65f6\523b\ff09';
COMMENT ON COLUMN device_fault_order.created_at IS U&'\521b\5efa\65f6\95f4';
-- device_http_credential
COMMENT ON COLUMN device_http_credential.device_id IS U&'\8bbe\5907ID';
COMMENT ON COLUMN device_http_credential.secret_sha256 IS U&'\5bc6\94a5\54c8\5e0c\ff08SHA-256\ff09';
COMMENT ON COLUMN device_http_credential.status IS U&'\72b6\6001\ff1aACTIVE/REVOKED';
COMMENT ON COLUMN device_http_credential.issued_by IS U&'\7b7e\53d1\4eba\7528\6237ID';
COMMENT ON COLUMN device_http_credential.issued_at IS U&'\7b7e\53d1\65f6\95f4';
COMMENT ON COLUMN device_http_credential.rotated_at IS U&'\8f6e\6362\65f6\95f4';
COMMENT ON COLUMN device_http_credential.revoked_at IS U&'\540a\9500\65f6\95f4';
COMMENT ON COLUMN device_http_credential.revoke_reason IS U&'\540a\9500\539f\56e0';
-- device_mqtt_credential
COMMENT ON COLUMN device_mqtt_credential.device_id IS U&'\8bbe\5907ID';
COMMENT ON COLUMN device_mqtt_credential.mqtt_username IS U&'MQTT \7528\6237\540d\ff08=deviceId\ff09';
COMMENT ON COLUMN device_mqtt_credential.mqtt_secret IS U&'MQTT \5bc6\7801\660e\6587\ff08\4ec5\7528\4e8e\751f\6210 EMQX bootstrap\ff09';
COMMENT ON COLUMN device_mqtt_credential.secret_sha256 IS U&'\5bc6\94a5\54c8\5e0c\ff08SHA-256\ff09';
COMMENT ON COLUMN device_mqtt_credential.status IS U&'\72b6\6001\ff1aACTIVE/REVOKED';
COMMENT ON COLUMN device_mqtt_credential.issued_by IS U&'\7b7e\53d1\4eba\7528\6237ID';
COMMENT ON COLUMN device_mqtt_credential.issued_at IS U&'\7b7e\53d1\65f6\95f4';
COMMENT ON COLUMN device_mqtt_credential.rotated_at IS U&'\8f6e\6362\65f6\95f4';
COMMENT ON COLUMN device_mqtt_credential.revoked_at IS U&'\540a\9500\65f6\95f4';
COMMENT ON COLUMN device_mqtt_credential.revoke_reason IS U&'\540a\9500\539f\56e0';
-- device_rental_bill
COMMENT ON COLUMN device_rental_bill.bill_id IS U&'\8d26\5355ID';
COMMENT ON COLUMN device_rental_bill.device_id IS U&'\8bbe\5907ID';
COMMENT ON COLUMN device_rental_bill.device_name IS U&'\8bbe\5907\540d\79f0\5197\4f59';
COMMENT ON COLUMN device_rental_bill.merchant_id IS U&'\5546\6237ID';
COMMENT ON COLUMN device_rental_bill.amount_cents IS U&'\91d1\989d\ff08\5206\ff09';
COMMENT ON COLUMN device_rental_bill.paid_at IS U&'\652f\4ed8\65f6\95f4';
COMMENT ON COLUMN device_rental_bill.remark IS U&'\5907\6ce8';
COMMENT ON COLUMN device_rental_bill.created_at IS U&'\521b\5efa\65f6\95f4';
COMMENT ON COLUMN device_rental_bill.updated_at IS U&'\66f4\65b0\65f6\95f4';
-- device_rental_deposit
COMMENT ON COLUMN device_rental_deposit.deposit_id IS U&'\62bc\91d1\53f0\8d26ID';
COMMENT ON COLUMN device_rental_deposit.device_id IS U&'\8bbe\5907ID';
COMMENT ON COLUMN device_rental_deposit.merchant_id IS U&'\5546\6237ID';
COMMENT ON COLUMN device_rental_deposit.settled_at IS U&'\7ed3\6e05\65f6\95f4';
COMMENT ON COLUMN device_rental_deposit.remark IS U&'\5907\6ce8';
COMMENT ON COLUMN device_rental_deposit.created_at IS U&'\521b\5efa\65f6\95f4';
COMMENT ON COLUMN device_rental_deposit.updated_at IS U&'\66f4\65b0\65f6\95f4';
COMMENT ON COLUMN device_rental_deposit.charge_mode IS U&'\62bc\91d1\6536\53d6\65b9\5f0f\ff1aONLINE=\7ebf\4e0a\6263\6b3e / OFFLINE=\7ebf\4e0b\767b\8bb0';
COMMENT ON COLUMN device_rental_deposit.monthly_refund_cents IS U&'\6bcf\6708\9000\8fd8\989d\ff08\5206\ff09\ff1bnull/<=0=\4e0d\81ea\52a8\9000\8fd8';
COMMENT ON COLUMN device_rental_deposit.refund_start_month IS U&'\5f00\59cb\9000\8fd8\7684\6708\5e8f\ff08\767b\8bb0\6708\540e\7b2c N \4e2a\6708\ff09';
COMMENT ON COLUMN device_rental_deposit.expire_reward_cents IS U&'\671f\6ee1\5956\52b1\ff08\5206\ff09\ff0c\9000\5b8c\5f53\6708\4e00\6b21\6027\53d1\653e';
COMMENT ON COLUMN device_rental_deposit.install_fee_deduct_cents IS U&'\64a4\673a\5b89\88c5\8d39\6263\989d\ff08\5206\ff09';
COMMENT ON COLUMN device_rental_deposit.install_fee_clawback_enabled IS U&'\672a\6ee1\5408\540c\5e74\6570\64a4\673a\662f\5426\5012\6263\5b89\88c5\8d39';
COMMENT ON COLUMN device_rental_deposit.contract_years IS U&'\5408\540c\5e74\6570\ff08\6ee1\671f\53d1\5956\52b1\3001\514d\6263\5b89\88c5\8d39\ff09';
-- device_rental_deposit_event
COMMENT ON COLUMN device_rental_deposit_event.event_id IS U&'\4e8b\4ef6ID';
COMMENT ON COLUMN device_rental_deposit_event.deposit_id IS U&'\62bc\91d1\53f0\8d26ID';
COMMENT ON COLUMN device_rental_deposit_event.operator_id IS U&'\64cd\4f5c\4eba\7528\6237ID';
COMMENT ON COLUMN device_rental_deposit_event.created_at IS U&'\521b\5efa\65f6\95f4';
-- device_sku_price
COMMENT ON COLUMN device_sku_price.enabled IS U&'\8986\76d6\4ef7\662f\5426\542f\7528';
-- device_slot
COMMENT ON COLUMN device_slot.assigned_at IS U&'SKU \7ed1\5b9a\5230\8be5\8d27\9053\7684\65f6\95f4\ff08\6362\7ed1\5237\65b0\ff09';
-- external_channel_flow
COMMENT ON COLUMN external_channel_flow.flow_id IS U&'\6d41\6c34ID';
COMMENT ON COLUMN external_channel_flow.batch_id IS U&'\5bfc\5165\6279\6b21ID';
COMMENT ON COLUMN external_channel_flow.channel_code IS U&'\6e20\9053\7f16\7801';
COMMENT ON COLUMN external_channel_flow.period_label IS U&'\8d26\671f\6807\7b7e';
COMMENT ON COLUMN external_channel_flow.warehouse_id IS U&'\4ed3\5e93ID';
COMMENT ON COLUMN external_channel_flow.person_name IS U&'\4eba\5458\59d3\540d';
COMMENT ON COLUMN external_channel_flow.sku_id IS U&'\5546\54c1SKU ID';
COMMENT ON COLUMN external_channel_flow.op_type IS U&'\64cd\4f5c\7c7b\578b\ff08WAREHOUSE_OUT/WAREHOUSE_STOCKTAKE/GUEST_WATER\ff09';
COMMENT ON COLUMN external_channel_flow.qty IS U&'\6570\91cf\ff08\4ef6\ff09';
COMMENT ON COLUMN external_channel_flow.unit_price_cents IS U&'\5355\4ef7\ff08\5206\ff09';
COMMENT ON COLUMN external_channel_flow.happened_min IS U&'\884c\5185\6700\65e9/\6700\665a\4e1a\52a1\65f6\95f4\ff08\8de8\5ea6\6838\5bf9\7528\ff09';
COMMENT ON COLUMN external_channel_flow.happened_max IS U&'\6700\665a\4e1a\52a1\65e5\671f';
COMMENT ON COLUMN external_channel_flow.row_count IS U&'\805a\5408\884c\6570';
COMMENT ON COLUMN external_channel_flow.remark IS U&'\5907\6ce8';
COMMENT ON COLUMN external_channel_flow.created_at IS U&'\521b\5efa\65f6\95f4';
-- external_import_batch
COMMENT ON COLUMN external_import_batch.batch_id IS U&'\6279\6b21ID';
COMMENT ON COLUMN external_import_batch.channel_code IS U&'\6e20\9053\7f16\7801';
COMMENT ON COLUMN external_import_batch.period_label IS U&'\8d26\671f\6807\7b7e=\7ed3\675f\6708\ff0c\5982 202609\ff0826~25 \8d26\671f\ff09';
COMMENT ON COLUMN external_import_batch.file_name IS U&'\6587\4ef6\540d';
COMMENT ON COLUMN external_import_batch.person_override IS U&'\4eba\5de5\6307\5b9a\7684\7ebf\957f\ff08MANUAL \6e20\9053\ff09';
COMMENT ON COLUMN external_import_batch.status IS U&'\72b6\6001\ff1aPARSED / COMMITTED';
COMMENT ON COLUMN external_import_batch.rows_total IS U&'\89e3\6790\603b\884c\6570';
COMMENT ON COLUMN external_import_batch.rows_mapped IS U&'\5916\90e8\5546\54c1\5df2\6620\5c04\4e14\4eba\5458\53ef\5f52\4ed3';
COMMENT ON COLUMN external_import_batch.rows_unmapped IS U&'\672a\6620\5c04\884c\6570';
COMMENT ON COLUMN external_import_batch.unmapped_products IS U&'\672a\6620\5c04\5916\90e8\5546\54c1\6e05\5355\ff08JSONB\ff09';
COMMENT ON COLUMN external_import_batch.unresolved_persons IS U&'\65e0\6cd5\5f52\4ed3\4eba\5458\ff08JSONB\ff09';
COMMENT ON COLUMN external_import_batch.parsed_rows IS U&'\7edf\4e00 9 \5217\89e3\6790\884c\ff08JSONB\ff09';
COMMENT ON COLUMN external_import_batch.uploaded_by IS U&'\4e0a\4f20\4eba\7528\6237ID';
COMMENT ON COLUMN external_import_batch.uploaded_at IS U&'\4e0a\4f20\65f6\95f4';
COMMENT ON COLUMN external_import_batch.committed_at IS U&'\786e\8ba4\5165\8d26\65f6\95f4';
COMMENT ON COLUMN external_import_batch.commit_summary IS U&'\786e\8ba4\5165\8d26\6c47\603b\ff08JSONB\ff09';
-- external_import_template
COMMENT ON COLUMN external_import_template.template_id IS U&'\6a21\677fID';
COMMENT ON COLUMN external_import_template.channel_code IS U&'\6e20\9053\7f16\7801';
COMMENT ON COLUMN external_import_template.channel_name IS U&'\6e20\9053\540d\79f0';
COMMENT ON COLUMN external_import_template.file_type IS U&'\6587\4ef6\7c7b\578b\ff1aCSV_GBK / XLSX / XLS';
COMMENT ON COLUMN external_import_template.sheet_strategy IS U&'\5de5\4f5c\8868\7b56\7565\ff1aFIRST / ALL / SKIP_META';
COMMENT ON COLUMN external_import_template.qty_semantics IS U&'\6570\91cf\53e3\5f84\ff1aDIRECT / BY_DIFF / SALES';
COMMENT ON COLUMN external_import_template.key_field IS U&'\805a\5408\952e\ff1aPRODUCT_ID / NAME';
COMMENT ON COLUMN external_import_template.person_source IS U&'\4eba\5458\6765\6e90\ff1aSHEET_NAME / COLUMN / FILE_NAME / MANUAL';
COMMENT ON COLUMN external_import_template.op_type IS U&'\64cd\4f5c\7c7b\578b\ff1aWAREHOUSE_OUT / WAREHOUSE_STOCKTAKE / GUEST_WATER';
COMMENT ON COLUMN external_import_template.column_map IS U&'JSONB \5b57\7b26\4e32\ff1a\8bed\4e49\5b57\6bb5\2192\5916\90e8\5217\5934';
COMMENT ON COLUMN external_import_template.default_warehouse_id IS U&'MANUAL \4eba\5458\5f52\5c5e\65f6\7684\515c\5e95\4ed3';
COMMENT ON COLUMN external_import_template.enabled IS U&'\662f\5426\542f\7528';
COMMENT ON COLUMN external_import_template.remark IS U&'\5907\6ce8';
COMMENT ON COLUMN external_import_template.updated_at IS U&'\66f4\65b0\65f6\95f4';
-- external_product
COMMENT ON COLUMN external_product.external_product_id IS U&'\5916\90e8\5546\54c1ID';
COMMENT ON COLUMN external_product.channel_code IS U&'\6e20\9053\7f16\7801';
COMMENT ON COLUMN external_product.external_key IS U&'\5546\54c1ID\ff08\65e0\5219\7528\5f52\4e00\5316\540d\79f0\ff0c\6a21\677f key_field \51b3\5b9a\ff09';
COMMENT ON COLUMN external_product.external_name IS U&'\5916\90e8\5546\54c1\540d\79f0';
COMMENT ON COLUMN external_product.brand IS U&'\54c1\724c';
COMMENT ON COLUMN external_product.spec IS U&'\89c4\683c';
COMMENT ON COLUMN external_product.unit_price_cents IS U&'\5355\4ef7\ff08\5206\ff09';
COMMENT ON COLUMN external_product.first_seen_at IS U&'\9996\6b21\51fa\73b0\65f6\95f4';
COMMENT ON COLUMN external_product.last_seen_at IS U&'\6700\8fd1\51fa\73b0\65f6\95f4';
COMMENT ON COLUMN external_product.times_seen IS U&'\51fa\73b0\6b21\6570';
-- external_product_mapping
COMMENT ON COLUMN external_product_mapping.mapping_id IS U&'\6620\5c04ID';
COMMENT ON COLUMN external_product_mapping.external_product_id IS U&'\5916\90e8\5546\54c1ID';
COMMENT ON COLUMN external_product_mapping.sku_id IS U&'\5546\54c1SKU ID';
COMMENT ON COLUMN external_product_mapping.bound_by IS U&'\7ed1\5b9a\64cd\4f5c\4eba\7528\6237ID';
COMMENT ON COLUMN external_product_mapping.bound_at IS U&'\7ed1\5b9a\65f6\95f4';
COMMENT ON COLUMN external_product_mapping.note IS U&'\5907\6ce8\8bf4\660e';
-- jiangyi_class_mapping
COMMENT ON COLUMN jiangyi_class_mapping.id IS U&'\4e3b\952eID';
COMMENT ON COLUMN jiangyi_class_mapping.device_id IS U&'\8bbe\5907ID';
COMMENT ON COLUMN jiangyi_class_mapping.class_id IS U&'\5c06\9091\6a21\578b class_id\ff080 \8d77\ff09';
COMMENT ON COLUMN jiangyi_class_mapping.model_name IS U&'\5c06\9091\6a21\578b\540d\79f0';
COMMENT ON COLUMN jiangyi_class_mapping.text_name IS U&'\6a21\578b\7c7b\522b\540d\ff08\5c55\793a/\6838\5bf9\7528\ff09';
COMMENT ON COLUMN jiangyi_class_mapping.sku_id IS U&'\6211\65b9 SKU ID';
COMMENT ON COLUMN jiangyi_class_mapping.status IS U&'\72b6\6001\ff1aACTIVE/DISABLED';
COMMENT ON COLUMN jiangyi_class_mapping.source IS U&'\6765\6e90';
COMMENT ON COLUMN jiangyi_class_mapping.created_at IS U&'\521b\5efa\65f6\95f4';
COMMENT ON COLUMN jiangyi_class_mapping.updated_at IS U&'\66f4\65b0\65f6\95f4';
-- jiangyi_device
COMMENT ON COLUMN jiangyi_device.device_id IS U&'\6211\65b9\4e1a\52a1\57df\67dc\673a ID\ff08device_info.device_id \540c\57df\ff09';
COMMENT ON COLUMN jiangyi_device.device_sn IS U&'\8bbe\5907 SN\ff08\5de5\63a7\673a\552f\4e00\8bc6\522b\7801\ff09';
COMMENT ON COLUMN jiangyi_device.model_name IS U&'\5c06\9091\6a21\578b\540d\79f0';
COMMENT ON COLUMN jiangyi_device.classes_version IS U&'\6a21\578b classes \7248\672c\ff08\4e8c\671f\6a21\578b\540c\6b65\56de\586b\ff09';
COMMENT ON COLUMN jiangyi_device.status IS U&'\72b6\6001\ff1aUNBOUND/BOUND/RETIRED';
COMMENT ON COLUMN jiangyi_device.token_version IS U&'token \7248\672c\53f7\ff08\6bcf\6b21\7b7e\53d1 +1\ff0c\65e7 token \5931\6548\ff09';
COMMENT ON COLUMN jiangyi_device.token_issued_at IS U&'token \6700\8fd1\7b7e\53d1\65f6\95f4';
COMMENT ON COLUMN jiangyi_device.last_ws_online_at IS U&'\6700\8fd1 WS \5728\7ebf\65f6\95f4';
COMMENT ON COLUMN jiangyi_device.created_at IS U&'\521b\5efa\65f6\95f4';
COMMENT ON COLUMN jiangyi_device.updated_at IS U&'\66f4\65b0\65f6\95f4';
COMMENT ON COLUMN jiangyi_device.industrial_control_model IS U&'\5de5\63a7\673a\673a\578b\ff08\5b58\5c06\9091\539f\503c "76"/"88"\ff09';
COMMENT ON COLUMN jiangyi_device.gather_locked_at IS U&'\91c7\96c6\9501\65f6\95f4\ff08\975e\7a7a=\91c7\96c6\4e2d\ff09';
-- jiangyi_model_deployment
COMMENT ON COLUMN jiangyi_model_deployment.id IS U&'\4e3b\952eID';
COMMENT ON COLUMN jiangyi_model_deployment.device_id IS U&'\8bbe\5907ID';
COMMENT ON COLUMN jiangyi_model_deployment.model_id IS U&'\5c06\9091 modelFile id\ff08\00a74.4.4\ff09';
COMMENT ON COLUMN jiangyi_model_deployment.model_name IS U&'\6a21\578b\540d\79f0';
COMMENT ON COLUMN jiangyi_model_deployment.model_url IS U&'\6a21\578b\6587\4ef6\4e0b\8f7d\5730\5740';
COMMENT ON COLUMN jiangyi_model_deployment.classes_text_url IS U&'classes.txt \4e0b\8f7d\5730\5740';
COMMENT ON COLUMN jiangyi_model_deployment.industrial_control_model IS U&'\4e0b\53d1\65f6\6821\9a8c\901a\8fc7\7684\673a\578b\5feb\7167';
COMMENT ON COLUMN jiangyi_model_deployment.classes_version IS U&'\6a21\578b classes \7248\672c';
COMMENT ON COLUMN jiangyi_model_deployment.status IS U&'\72b6\6001\ff1aSENT/CONFIRMED/FAILED';
COMMENT ON COLUMN jiangyi_model_deployment.sent_at IS U&'\4e0b\53d1\65f6\95f4';
COMMENT ON COLUMN jiangyi_model_deployment.confirmed_at IS U&'\8bbe\5907\56de\6267\786e\8ba4\65f6\95f4';
COMMENT ON COLUMN jiangyi_model_deployment.fail_reason IS U&'\5931\8d25\539f\56e0';
-- jiangyi_order_video
COMMENT ON COLUMN jiangyi_order_video.id IS U&'\4e3b\952eID';
COMMENT ON COLUMN jiangyi_order_video.order_no IS U&'\5c06\9091\8bbe\5907\4e0a\62a5\7684\8ba2\5355\7f16\53f7\ff08\539f\503c\ff0c\4e0d FK\ff09';
COMMENT ON COLUMN jiangyi_order_video.device_id IS U&'\4e0a\62a5\8bbe\5907ID\ff08gateway \4fa7\89e3\6790\ff09';
COMMENT ON COLUMN jiangyi_order_video.serial_num IS U&'\89c6\9891\5e8f\53f7\ff08\7b2c\51e0\7247\ff0c1 \8d77\ff09';
COMMENT ON COLUMN jiangyi_order_video.video_quantity IS U&'\89c6\9891\603b\7247\6570';
COMMENT ON COLUMN jiangyi_order_video.video_urls IS U&'\89c6\9891 URL \5217\8868\ff08\9017\53f7\62fc\63a5\ff1b\7a7a\4e32=\8be5\7247\5931\8d25\ff09';
COMMENT ON COLUMN jiangyi_order_video.reported_at IS U&'\4e0a\62a5\65f6\95f4';
-- jiangyi_recognition_report
COMMENT ON COLUMN jiangyi_recognition_report.report_id IS U&'\4e0a\62a5\8bb0\5f55ID';
COMMENT ON COLUMN jiangyi_recognition_report.device_id IS U&'\8bbe\5907ID';
COMMENT ON COLUMN jiangyi_recognition_report.order_no IS U&'\2261 \6211\65b9 sessionId\ff1b\7a7a\5355\53f7\62a5\6587\5b58 NULL\ff08\771f\673a\5b9e\8bc1\5b58\5728\8be5\5f62\6001\ff09';
COMMENT ON COLUMN jiangyi_recognition_report.transaction_id IS U&'\56fa\4ef6 OrderEntity.transactionId\ff0c\5382\5546\4fa7\6d41\6c34\53f7';
COMMENT ON COLUMN jiangyi_recognition_report.pay_type IS U&'\56fa\4ef6\4e0a\62a5\652f\4ed8\65b9\5f0f\539f\503c\ff08\53ea\7559\6863\ff0c\4e0d\53c2\4e0e\7ed3\7b97\ff09';
COMMENT ON COLUMN jiangyi_recognition_report.forms_json IS U&'\6574\6bb5 forms \539f\6587\ff08classId/quantity/thirdPid/price \7b49\ff09';
COMMENT ON COLUMN jiangyi_recognition_report.items_json IS U&'\6211\65b9\5f52\4e00\5316\7ed3\679c [{skuId,quantity,confidence}]\ff1b\672a\547d\4e2d/\7a7a\5355\5f62\6001\4e3a NULL';
COMMENT ON COLUMN jiangyi_recognition_report.form_count IS U&'forms \6761\76ee\6570';
COMMENT ON COLUMN jiangyi_recognition_report.item_count IS U&'\5f52\4e00\5316\5546\54c1\6761\76ee\6570';
COMMENT ON COLUMN jiangyi_recognition_report.reported_at IS U&'\4e0a\62a5\65f6\95f4';
-- jiangyi_training_ticket
COMMENT ON COLUMN jiangyi_training_ticket.id IS U&'\4e3b\952eID';
COMMENT ON COLUMN jiangyi_training_ticket.device_id IS U&'\8bbe\5907ID';
COMMENT ON COLUMN jiangyi_training_ticket.sku_id IS U&'\5546\54c1SKU ID';
COMMENT ON COLUMN jiangyi_training_ticket.jiangyi_product_id IS U&'\5c06\9091\5546\54c1 ID';
COMMENT ON COLUMN jiangyi_training_ticket.finish_notify_id IS U&'\4e00\6b21\6027\51ed\636e\ff08UUID\ff09\ff0cUNIQUE';
COMMENT ON COLUMN jiangyi_training_ticket.model_name IS U&'\5c06\9091\6a21\578b\540d\79f0';
COMMENT ON COLUMN jiangyi_training_ticket.status IS U&'\72b6\6001\ff1aPENDING/FINISHED/FAILED';
COMMENT ON COLUMN jiangyi_training_ticket.created_at IS U&'\521b\5efa\65f6\95f4';
COMMENT ON COLUMN jiangyi_training_ticket.finished_at IS U&'\5b66\4e60\5b8c\6210\65f6\95f4';
-- line_manager
COMMENT ON COLUMN line_manager.identity_verified_at IS U&'\8eab\4efd\6838\9a8c\901a\8fc7\65f6\95f4';
-- line_route
COMMENT ON COLUMN line_route.route_id IS U&'\8def\7ebfID';
COMMENT ON COLUMN line_route.route_name IS U&'\7ebf\8def\540d\79f0';
COMMENT ON COLUMN line_route.manager_id IS U&'\7ebf\957f\ff08\7ebf\8def\8d1f\8d23\4eba\ff0cline_manager.manager_id\ff09';
COMMENT ON COLUMN line_route.remark IS U&'\5907\6ce8';
COMMENT ON COLUMN line_route.status IS U&'\72b6\6001\ff1aACTIVE / ARCHIVED';
COMMENT ON COLUMN line_route.created_at IS U&'\521b\5efa\65f6\95f4';
-- line_route_member
COMMENT ON COLUMN line_route_member.member_id IS U&'\4e3b\952eID';
COMMENT ON COLUMN line_route_member.route_id IS U&'\8def\7ebfID';
COMMENT ON COLUMN line_route_member.user_id IS U&'\6210\5458\ff08user_info.userId\ff1b\7ebf\957f\4e0e\8865\8d27\5458\53ef\4e3a\540c\4e00\4eba=\4e24\884c\89d2\8272\ff09';
COMMENT ON COLUMN line_route_member.role IS U&'\89d2\8272\ff1aMANAGER=\7ebf\957f / STAFF=\8865\8d27\5458';
COMMENT ON COLUMN line_route_member.commission_basis IS U&'\63d0\6210\57fa\6570\ff1aGMV / RESTOCK_QTY';
COMMENT ON COLUMN line_route_member.commission_rate_bps IS U&'GMV \6a21\5f0f\4f63\91d1\6bd4\4f8b\ff08bps\ff09';
COMMENT ON COLUMN line_route_member.commission_per_unit_cents IS U&'\6309\4ef6\6a21\5f0f\6bcf\4ef6\4f63\91d1\ff08\5206\ff09';
COMMENT ON COLUMN line_route_member.status IS U&'\72b6\6001\ff08ACTIVE=\751f\6548\ff09';
COMMENT ON COLUMN line_route_member.created_at IS U&'\521b\5efa\65f6\95f4';
-- line_withdraw_request
COMMENT ON COLUMN line_withdraw_request.payee_bank_name IS U&'\5f00\6237\884c\ff08BANK \901a\9053\7528\ff09';
COMMENT ON COLUMN line_withdraw_request.payee_tax_no IS U&'\7eb3\7a0e\4eba\8bc6\522b\53f7\ff08\5bf9\516c\4ee3\4ed8\7528\ff09';
COMMENT ON COLUMN line_withdraw_request.channel_order_no IS U&'\6e20\9053\5355\53f7\ff08\5bf9\8d26\7528\ff09';
COMMENT ON COLUMN line_withdraw_request.channel_batch_no IS U&'\6e20\9053\6279\6b21\53f7\ff08\90e8\5206\6e20\9053\4e00\6b21\53d7\7406\591a\7b14\65f6\7528\ff09';
-- machine_payee_bill
COMMENT ON COLUMN machine_payee_bill.bill_id IS U&'\8d26\5355ID';
COMMENT ON COLUMN machine_payee_bill.device_id IS U&'\8bbe\5907ID';
COMMENT ON COLUMN machine_payee_bill.bill_month IS U&'\8d26\671f\ff08YYYY-MM\ff09';
COMMENT ON COLUMN machine_payee_bill.fee_type IS U&'\8d39\7528\7c7b\578b\ff1aELECTRIC=\7535\8d39 / SHARE=\9500\552e\5206\6210';
COMMENT ON COLUMN machine_payee_bill.amount_cents IS U&'\91d1\989d\ff08\5206\ff09';
COMMENT ON COLUMN machine_payee_bill.gmv_cents IS U&'\9500\552e\5206\6210\57fa\6570\ff08\5206\ff09';
COMMENT ON COLUMN machine_payee_bill.payee_name IS U&'\573a\5730\65b9\6536\6b3e\4eba\59d3\540d';
COMMENT ON COLUMN machine_payee_bill.payee_phone IS U&'\573a\5730\65b9\6536\6b3e\4eba\624b\673a\53f7';
COMMENT ON COLUMN machine_payee_bill.status IS U&'PENDING/SETTLED\ff08\7ebf\4e0b\7ed3\7b97\6807\8bb0\ff09';
COMMENT ON COLUMN machine_payee_bill.settled_at IS U&'\7ebf\4e0b\7ed3\7b97\6807\8bb0\65f6\95f4';
COMMENT ON COLUMN machine_payee_bill.remark IS U&'\5907\6ce8';
COMMENT ON COLUMN machine_payee_bill.created_at IS U&'\521b\5efa\65f6\95f4';
-- machine_position_config
COMMENT ON COLUMN machine_position_config.device_id IS U&'\8bbe\5907ID';
COMMENT ON COLUMN machine_position_config.electric_type IS U&'\7535\8d39\7c7b\578b\ff1aNONE / FIXED';
COMMENT ON COLUMN machine_position_config.electric_amount_cents IS U&'\6708\7535\8d39\ff08\56fa\5b9a\989d\ff0c\5206\ff09';
COMMENT ON COLUMN machine_position_config.welfare_info IS U&'\5ba2\60c5\8bf4\660e';
COMMENT ON COLUMN machine_position_config.share_bps IS U&'\573a\5730\65b9\9500\552e\5206\6210\6bd4\4f8b\ff08bps\ff09';
COMMENT ON COLUMN machine_position_config.payee_name IS U&'\573a\5730\65b9\6536\6b3e\4eba\59d3\540d';
COMMENT ON COLUMN machine_position_config.payee_phone IS U&'\573a\5730\65b9\6536\6b3e\4eba\624b\673a\53f7';
COMMENT ON COLUMN machine_position_config.enabled IS U&'\662f\5426\542f\7528';
COMMENT ON COLUMN machine_position_config.updated_at IS U&'\66f4\65b0\65f6\95f4\ff08\4fdd\5b58\65f6\5237\65b0\ff09';
-- media_asset
COMMENT ON COLUMN media_asset.image_width IS U&'\56fe\7247\5bbd\5ea6\ff08px\ff09';
COMMENT ON COLUMN media_asset.image_height IS U&'\56fe\7247\9ad8\5ea6\ff08px\ff09';
-- merchant_fee_ledger
COMMENT ON COLUMN merchant_fee_ledger.ledger_id IS U&'\53f0\8d26ID';
COMMENT ON COLUMN merchant_fee_ledger.merchant_id IS U&'\5546\6237ID';
COMMENT ON COLUMN merchant_fee_ledger.fee_date IS U&'\8d39\7528\65e5\671f';
COMMENT ON COLUMN merchant_fee_ledger.fee_type IS U&'\8d39\7528\7c7b\578b\ff1aREN=\79df\91d1 / DATA=\6d41\91cf\8d39';
COMMENT ON COLUMN merchant_fee_ledger.settled_at IS U&'\7ed3\6e05\65f6\95f4';
COMMENT ON COLUMN merchant_fee_ledger.remark IS U&'\5907\6ce8';
COMMENT ON COLUMN merchant_fee_ledger.created_at IS U&'\521b\5efa\65f6\95f4';
-- merchant_settlement_bill
COMMENT ON COLUMN merchant_settlement_bill.merchant_id IS U&'\5546\6237ID';
COMMENT ON COLUMN merchant_settlement_bill.status IS U&'\72b6\6001\ff1aPENDING/CONFIRMED';
COMMENT ON COLUMN merchant_settlement_bill.order_count IS U&'\8ba2\5355\6570';
COMMENT ON COLUMN merchant_settlement_bill.gross_cents IS U&'\8ba2\5355\539f\4ef7\8425\6536\5408\8ba1\ff08\5206\ff09';
COMMENT ON COLUMN merchant_settlement_bill.platform_cents IS U&'\5e73\53f0\5206\6210\5408\8ba1\ff08\5206\ff09';
COMMENT ON COLUMN merchant_settlement_bill.merchant_cents IS U&'\5546\6237\5206\6210\5408\8ba1\ff08\5206\ff09';
COMMENT ON COLUMN merchant_settlement_bill.settled_cents IS U&'status=''SUCCESS'' \7684\5546\6237\5206\6210\5408\8ba1\ff08\5df2\5b9e\6536\5fae\4fe1\4fa7\ff09';
COMMENT ON COLUMN merchant_settlement_bill.pending_cents IS U&'\975e SUCCESS \6709\6548\72b6\6001\7684\5546\6237\5206\6210\5408\8ba1\ff08\542b\5165\94b1\5305\672a\5230\8d26\671f\7b49\ff09';
COMMENT ON COLUMN merchant_settlement_bill.failed_count IS U&'WECHAT_FAILED/FAILED \884c\6570';
COMMENT ON COLUMN merchant_settlement_bill.computed_at IS U&'\8ba1\7b97\65f6\95f4';
COMMENT ON COLUMN merchant_settlement_bill.confirmed_at IS U&'\786e\8ba4\65f6\95f4';
-- merchant_withdraw_request
COMMENT ON COLUMN merchant_withdraw_request.payee_bank_name IS U&'\5f00\6237\884c\ff08BANK \901a\9053\7528\ff09';
COMMENT ON COLUMN merchant_withdraw_request.payee_tax_no IS U&'\7eb3\7a0e\4eba\8bc6\522b\53f7\ff08\5bf9\516c\4ee3\4ed8\7528\ff09';
COMMENT ON COLUMN merchant_withdraw_request.channel_order_no IS U&'\6e20\9053\5355\53f7\ff08\5bf9\8d26\7528\ff09';
COMMENT ON COLUMN merchant_withdraw_request.channel_batch_no IS U&'\6e20\9053\6279\6b21\53f7\ff08\90e8\5206\6e20\9053\4e00\6b21\53d7\7406\591a\7b14\65f6\7528\ff09';
-- payment_platform_bill_line
COMMENT ON COLUMN payment_platform_bill_line.fee_cents IS U&'\624b\7eed\8d39\ff08\5206\ff09';
-- payment_reconciliation
COMMENT ON COLUMN payment_reconciliation.channel_fee_cents IS U&'\6e20\9053\624b\7eed\8d39\5408\8ba1\ff08\5206\ff09';
-- payout_account
COMMENT ON COLUMN payout_account.account_id IS U&'\8d26\6237ID';
COMMENT ON COLUMN payout_account.owner_id IS U&'\6240\6709\8005ID';
COMMENT ON COLUMN payout_account.channel IS U&'\6e20\9053\ff1aWECHAT / ALIPAY / BANK';
COMMENT ON COLUMN payout_account.account_name IS U&'\6237\540d\ff08\5bf9\516c=\516c\53f8\5168\79f0\ff0c\5bf9\79c1=\5b9e\540d\ff09';
COMMENT ON COLUMN payout_account.bank_name IS U&'\5f00\6237\94f6\884c';
COMMENT ON COLUMN payout_account.bank_branch IS U&'\5f00\6237\652f\884c';
COMMENT ON COLUMN payout_account.tax_no IS U&'\7eb3\7a0e\4eba\8bc6\522b\53f7\ff08\5bf9\516c\4ee3\4ed8\5fc5\586b\ff09';
COMMENT ON COLUMN payout_account.is_default IS U&'\662f\5426\9ed8\8ba4\8d26\6237';
COMMENT ON COLUMN payout_account.status IS U&'ACTIVE / DISABLED\ff08\505c\7528\540e\4e0d\53ef\7528\4e8e\65b0\63d0\73b0\5355\ff0c\5386\53f2\5355\5feb\7167\4e0d\53d7\5f71\54cd\ff09';
COMMENT ON COLUMN payout_account.version IS U&'\7248\672c\53f7\ff08\9884\7559\ff0c\5f53\524d\672a\53c2\4e0e\5e76\53d1\6821\9a8c\ff09';
COMMENT ON COLUMN payout_account.created_by IS U&'\521b\5efa\4eba\7528\6237ID';
COMMENT ON COLUMN payout_account.reviewed_by IS U&'\5ba1\6838\4eba\7528\6237ID';
COMMENT ON COLUMN payout_account.created_at IS U&'\521b\5efa\65f6\95f4';
COMMENT ON COLUMN payout_account.updated_at IS U&'\66f4\65b0\65f6\95f4';
COMMENT ON COLUMN payout_account.bank_code IS U&'\8054\884c\53f7\ff08CNAPS\ff09';
COMMENT ON COLUMN payout_account.bank_province_city IS U&'\5f00\6237\884c\7701\5e02';
-- platform_fund_account
COMMENT ON COLUMN platform_fund_account.account_id IS U&'\8d26\6237ID';
COMMENT ON COLUMN platform_fund_account.remark IS U&'\5907\6ce8';
COMMENT ON COLUMN platform_fund_account.created_at IS U&'\521b\5efa\65f6\95f4';
COMMENT ON COLUMN platform_fund_account.updated_at IS U&'\66f4\65b0\65f6\95f4';
-- platform_fund_flow
COMMENT ON COLUMN platform_fund_flow.flow_id IS U&'\6d41\6c34ID';
COMMENT ON COLUMN platform_fund_flow.account_id IS U&'\5e73\53f0\8d44\91d1\6c60\8d26\6237ID';
COMMENT ON COLUMN platform_fund_flow.remark IS U&'\5907\6ce8';
COMMENT ON COLUMN platform_fund_flow.created_at IS U&'\521b\5efa\65f6\95f4';
-- promotion_activity
COMMENT ON COLUMN promotion_activity.reduce_cents IS U&'\6ee1\51cf\91d1\989d\ff08\5206\ff09';
COMMENT ON COLUMN promotion_activity.discount_bps IS U&'\6298\6263\6bd4\4f8b\ff08bps\ff09';
COMMENT ON COLUMN promotion_activity.fixed_price_cents IS U&'\4e00\53e3\4ef7\ff08\5206\ff09';
-- promotion_placement
COMMENT ON COLUMN promotion_placement.placement_id IS U&'\5c55\793a\4f4dID';
COMMENT ON COLUMN promotion_placement.activity_id IS U&'\6d3b\52a8ID';
COMMENT ON COLUMN promotion_placement.position IS U&'\5c55\793a\4f4d\7f6e\ff08BANNER/POPUP_BEFORE_OPEN/POPUP_AFTER_CLOSE\ff09';
COMMENT ON COLUMN promotion_placement.asset_id IS U&'\7d20\6750ID';
COMMENT ON COLUMN promotion_placement.image_width IS U&'\56fe\7247\5bbd\5ea6\ff08px\ff09';
COMMENT ON COLUMN promotion_placement.image_height IS U&'\56fe\7247\9ad8\5ea6\ff08px\ff09';
COMMENT ON COLUMN promotion_placement.created_at IS U&'\521b\5efa\65f6\95f4';
-- replenishment_task
COMMENT ON COLUMN replenishment_task.client_request_no IS U&'\5ba2\6237\7aef\5e42\7b49\952e\ff08NULL=\65e7\5ba2\6237\7aef\ff09';
-- site_rent_bill
COMMENT ON COLUMN site_rent_bill.bill_id IS U&'\8d26\5355ID';
COMMENT ON COLUMN site_rent_bill.contract_id IS U&'\5408\540c/\7b7e\7ea6ID';
COMMENT ON COLUMN site_rent_bill.device_id IS U&'\8bbe\5907ID';
COMMENT ON COLUMN site_rent_bill.site_name IS U&'\70b9\4f4d\540d\79f0';
COMMENT ON COLUMN site_rent_bill.bill_month IS U&'\8d26\671f\ff08YYYY-MM\ff09';
COMMENT ON COLUMN site_rent_bill.party_type IS U&'\6536\6b3e\65b9\7c7b\578b\ff08LANDLORD/PLATFORM/MERCHANT/FRANCHISE/OTHER\ff09';
COMMENT ON COLUMN site_rent_bill.party_id IS U&'\6536\6b3e\65b9ID';
COMMENT ON COLUMN site_rent_bill.share_bps IS U&'\5206\6210\6bd4\4f8b\ff08bps\ff09';
COMMENT ON COLUMN site_rent_bill.fixed_cents IS U&'\56fa\5b9a\79df\91d1\ff08\5206\ff09';
COMMENT ON COLUMN site_rent_bill.base_fee_cents IS U&'\5408\540c\6708\8d39\ff08\5206\644a\57fa\6570\ff0c\5206\ff09';
COMMENT ON COLUMN site_rent_bill.amount_cents IS U&'\91d1\989d\ff08\5206\ff09';
COMMENT ON COLUMN site_rent_bill.status IS U&'\72b6\6001\ff1aUNPAID/PAID/VOID';
COMMENT ON COLUMN site_rent_bill.paid_at IS U&'\4ed8\6b3e\65f6\95f4';
COMMENT ON COLUMN site_rent_bill.remark IS U&'\5907\6ce8';
COMMENT ON COLUMN site_rent_bill.created_at IS U&'\521b\5efa\65f6\95f4';
COMMENT ON COLUMN site_rent_bill.updated_at IS U&'\66f4\65b0\65f6\95f4';
COMMENT ON COLUMN site_rent_bill.paid_by IS U&'\4ed8\6b3e\64cd\4f5c\4eba\7528\6237ID';
COMMENT ON COLUMN site_rent_bill.paid_voucher_no IS U&'\4ed8\6b3e\51ed\8bc1\53f7';
COMMENT ON COLUMN site_rent_bill.paid_remark IS U&'\4ed8\6b3e\5907\6ce8';
-- sku_catalog
COMMENT ON COLUMN sku_catalog.operation_scope IS U&'\7ecf\8425\5f52\5c5e\ff08DIRECT/NON_DIRECT\ff09';
COMMENT ON COLUMN sku_catalog.min_price_cents IS U&'\6700\4f4e\9650\4ef7\ff08\5206\ff09\ff1bnull=\4e0d\9650';
-- sku_jiangyi_link
COMMENT ON COLUMN sku_jiangyi_link.id IS U&'\4e3b\952eID';
COMMENT ON COLUMN sku_jiangyi_link.sku_id IS U&'\5546\54c1SKU ID';
COMMENT ON COLUMN sku_jiangyi_link.jiangyi_product_id IS U&'\5c06\9091\5546\54c1 ID';
COMMENT ON COLUMN sku_jiangyi_link.jiangyi_name IS U&'\5c06\9091\5546\54c1\540d\79f0\5feb\7167';
COMMENT ON COLUMN sku_jiangyi_link.jiangyi_text_name IS U&'\5c06\9091 classes.txt \7c7b\522b\540d';
COMMENT ON COLUMN sku_jiangyi_link.bar_code IS U&'\5c06\9091\6761\7801\5feb\7167';
COMMENT ON COLUMN sku_jiangyi_link.sync_status IS U&'\540c\6b65\72b6\6001\ff08BOUND/CREATED/RETIRED\ff09';
COMMENT ON COLUMN sku_jiangyi_link.synced_at IS U&'\6700\8fd1\540c\6b65\65f6\95f4';
COMMENT ON COLUMN sku_jiangyi_link.created_at IS U&'\521b\5efa\65f6\95f4';
COMMENT ON COLUMN sku_jiangyi_link.updated_at IS U&'\66f4\65b0\65f6\95f4';
-- sku_scene_limit
COMMENT ON COLUMN sku_scene_limit.id IS U&'\4e3b\952eID';
COMMENT ON COLUMN sku_scene_limit.sku_id IS U&'\5546\54c1SKU ID';
COMMENT ON COLUMN sku_scene_limit.scene_type IS U&'\573a\666f\7c7b\578b\ff08\5b57\5178 device_scene_type\ff09';
COMMENT ON COLUMN sku_scene_limit.max_qty IS U&'\8be5\573a\666f\4e0b\67dc\5185\4e0a\67b6\5408\8ba1\4e0a\9650\ff08>0\ff09';
COMMENT ON COLUMN sku_scene_limit.created_at IS U&'\521b\5efa\65f6\95f4';
COMMENT ON COLUMN sku_scene_limit.updated_at IS U&'\66f4\65b0\65f6\95f4';
-- slot_template
COMMENT ON COLUMN slot_template.id IS U&'\4e3b\952eID';
COMMENT ON COLUMN slot_template.template_name IS U&'\6a21\677f\540d\79f0';
COMMENT ON COLUMN slot_template.created_by IS U&'\521b\5efa\4eba\7528\6237ID';
COMMENT ON COLUMN slot_template.created_at IS U&'\521b\5efa\65f6\95f4';
-- slot_template_line
COMMENT ON COLUMN slot_template_line.id IS U&'\4e3b\952eID';
COMMENT ON COLUMN slot_template_line.template_id IS U&'\6a21\677fID';
COMMENT ON COLUMN slot_template_line.slot_code IS U&'\8d27\9053\7f16\7801';
COMMENT ON COLUMN slot_template_line.row_no IS U&'\884c\53f7';
COMMENT ON COLUMN slot_template_line.col_no IS U&'\5217\53f7';
COMMENT ON COLUMN slot_template_line.sku_id IS U&'\5546\54c1SKU ID';
COMMENT ON COLUMN slot_template_line.price_cents IS U&'\5355\4ef7\ff08\5206\ff09';
COMMENT ON COLUMN slot_template_line.par_level IS U&'\6807\51c6\8865\8d27\6c34\4f4d';
-- staff_commission_daily
COMMENT ON COLUMN staff_commission_daily.id IS U&'\4e3b\952eID';
COMMENT ON COLUMN staff_commission_daily.staff_user_id IS U&'\8865\8d27\5458\ff08user_info.userId\ff09';
COMMENT ON COLUMN staff_commission_daily.route_id IS U&'\8def\7ebfID';
COMMENT ON COLUMN staff_commission_daily.biz_date IS U&'\4e1a\52a1\65e5\671f';
COMMENT ON COLUMN staff_commission_daily.device_id IS U&'\8bbe\5907ID';
COMMENT ON COLUMN staff_commission_daily.restock_qty IS U&'\5f53\65e5\8be5\67dc\5b9e\9645\8865\8d27\4ef6\6570\ff08\6309\8865\8d27\7559\75d5\ff09';
COMMENT ON COLUMN staff_commission_daily.commission_cents IS U&'\5f53\65e5\4f63\91d1\ff08\5206\ff09';
COMMENT ON COLUMN staff_commission_daily.status IS U&'\72b6\6001\ff08POSTED=\5df2\53d1\4f63\ff09';
COMMENT ON COLUMN staff_commission_daily.created_at IS U&'\521b\5efa\65f6\95f4';
COMMENT ON COLUMN staff_commission_daily.gmv_cents IS U&'\5f53\65e5 GMV \57fa\6570\ff08\5206\ff09\ff1bRESTOCK_QTY \6a21\5f0f\4e3a 0';
COMMENT ON COLUMN staff_commission_daily.commission_basis IS U&'\53d1\4f63\65f6\7684\63d0\6210\6a21\5f0f\ff08GMV/RESTOCK_QTY\ff09';
-- supplier_payable_entry
COMMENT ON COLUMN supplier_payable_entry.entry_id IS U&'\6761\76eeID';
COMMENT ON COLUMN supplier_payable_entry.supplier_id IS U&'\4f9b\5e94\5546ID';
COMMENT ON COLUMN supplier_payable_entry.payable_id IS U&'\5e94\4ed8\5355ID';
COMMENT ON COLUMN supplier_payable_entry.purchase_order_id IS U&'\91c7\8d2d\5355ID';
COMMENT ON COLUMN supplier_payable_entry.entry_type IS U&'\4e8b\4ef6\7c7b\578b\ff08RECEIVE/RETURN/OPENING\ff09';
COMMENT ON COLUMN supplier_payable_entry.amount_cents IS U&'\91d1\989d\ff08\5206\ff09';
COMMENT ON COLUMN supplier_payable_entry.operator_id IS U&'\64cd\4f5c\4eba\7528\6237ID';
COMMENT ON COLUMN supplier_payable_entry.notes IS U&'\5907\6ce8';
COMMENT ON COLUMN supplier_payable_entry.created_at IS U&'\521b\5efa\65f6\95f4';
-- system_config_history
COMMENT ON COLUMN system_config_history.history_id IS U&'\5386\53f2\8bb0\5f55ID';
-- unpaid_dunning_record
COMMENT ON COLUMN unpaid_dunning_record.user_id IS U&'\7528\6237ID';
COMMENT ON COLUMN unpaid_dunning_record.unpaid_count IS U&'\7d2f\8ba1\6b20\6b3e\5173\5355\6b21\6570\ff08\9636\68af\5224\636e\ff09';
COMMENT ON COLUMN unpaid_dunning_record.tier IS U&'\50ac\7f34\9636\68af\ff1a0 \65e0 / 1 \63d0\9192 / 2 \9650\5236\989d\5ea6 / 3 \9650\5236\514d\5bc6\5148\4eab';
COMMENT ON COLUMN unpaid_dunning_record.last_reminded_at IS U&'\6700\8fd1\63d0\9192\65f6\95f4';
COMMENT ON COLUMN unpaid_dunning_record.last_dunning_at IS U&'\6700\8fd1\50ac\7f34\65f6\95f4';
COMMENT ON COLUMN unpaid_dunning_record.updated_at IS U&'\66f4\65b0\65f6\95f4';
-- user_consent_log
COMMENT ON COLUMN user_consent_log.log_id IS U&'\65e5\5fd7ID';
COMMENT ON COLUMN user_consent_log.user_id IS U&'\7528\6237ID';
COMMENT ON COLUMN user_consent_log.consent_type IS U&'\540c\610f\7c7b\578b\ff08PRIVACY_POLICY/ACCOUNT_DELETE\ff09';
COMMENT ON COLUMN user_consent_log.policy_version IS U&'\653f\7b56\7248\672c';
COMMENT ON COLUMN user_consent_log.consented_at IS U&'\540c\610f\65f6\95f4';
-- wallet_account
COMMENT ON COLUMN wallet_account.balance_cents IS U&'\4f59\989d\ff08\5206\ff09';
COMMENT ON COLUMN wallet_account.updated_at IS U&'\66f4\65b0\65f6\95f4';
-- wallet_ledger
COMMENT ON COLUMN wallet_ledger.ledger_id IS U&'\6d41\6c34ID';
COMMENT ON COLUMN wallet_ledger.domain IS U&'\94b1\5305\57df\ff08MERCHANT/LINE/STAFF\ff09';
COMMENT ON COLUMN wallet_ledger.owner_id IS U&'\57df\5185\6240\6709\8005ID';
COMMENT ON COLUMN wallet_ledger.entry_type IS U&'\6d41\6c34\7c7b\578b';
COMMENT ON COLUMN wallet_ledger.amount_cents IS U&'\91d1\989d\ff08\5206\ff09';
COMMENT ON COLUMN wallet_ledger.balance_after IS U&'\53d8\52a8\540e\4f59\989d\ff08\5206\ff09';
COMMENT ON COLUMN wallet_ledger.frozen_after IS U&'\53d8\52a8\540e\51bb\7ed3\91d1\989d\ff08\5206\ff09';
COMMENT ON COLUMN wallet_ledger.ref_type IS U&'\5173\8054\4e1a\52a1\7c7b\578b';
COMMENT ON COLUMN wallet_ledger.ref_id IS U&'\5173\8054\4e1a\52a1ID';
COMMENT ON COLUMN wallet_ledger.remark IS U&'\5907\6ce8';
COMMENT ON COLUMN wallet_ledger.created_at IS U&'\521b\5efa\65f6\95f4';
-- warehouse_manager
COMMENT ON COLUMN warehouse_manager.manager_id IS U&'\4e3b\952eID';
-- warehouse_monthly_close
COMMENT ON COLUMN warehouse_monthly_close.close_id IS U&'\6708\7ed3\5355ID';
COMMENT ON COLUMN warehouse_monthly_close.warehouse_id IS U&'\4ed3\5e93ID';
COMMENT ON COLUMN warehouse_monthly_close.year_month IS U&'\8d26\671f\6708\4efd\ff08YYYY-MM\ff09';
COMMENT ON COLUMN warehouse_monthly_close.status IS U&'\72b6\6001\ff1aDRAFT/APPROVED';
COMMENT ON COLUMN warehouse_monthly_close.loss_qty IS U&'\635f\8017\6570\91cf\5408\8ba1';
COMMENT ON COLUMN warehouse_monthly_close.surplus_qty IS U&'\76d8\76c8\6570\91cf\5408\8ba1';
COMMENT ON COLUMN warehouse_monthly_close.loss_amount_cents IS U&'\635f\8017\91d1\989d\5408\8ba1\ff08\5206\ff09';
COMMENT ON COLUMN warehouse_monthly_close.surplus_amount_cents IS U&'\76d8\76c8\91d1\989d\5408\8ba1\ff08\5206\ff09';
COMMENT ON COLUMN warehouse_monthly_close.line_count IS U&'\660e\7ec6\884c\6570';
COMMENT ON COLUMN warehouse_monthly_close.approved_by IS U&'\5ba1\6279\4eba\7528\6237ID';
COMMENT ON COLUMN warehouse_monthly_close.approved_by_name IS U&'\5ba1\6279\4eba\59d3\540d';
COMMENT ON COLUMN warehouse_monthly_close.approved_at IS U&'\5ba1\6279\65f6\95f4';
COMMENT ON COLUMN warehouse_monthly_close.remark IS U&'\5907\6ce8';
COMMENT ON COLUMN warehouse_monthly_close.created_at IS U&'\521b\5efa\65f6\95f4';
COMMENT ON COLUMN warehouse_monthly_close.updated_at IS U&'\66f4\65b0\65f6\95f4';
COMMENT ON COLUMN warehouse_monthly_close.opening_source IS U&'\671f\521d\6765\6e90\ff1aPERIOD_CLOSE=\4e0a\671f\5b9e\76d8\7ed3\8f6c / BOOK=\8d26\9762\7ed3\8f6c';
-- warehouse_monthly_close_line
COMMENT ON COLUMN warehouse_monthly_close_line.line_id IS U&'\660e\7ec6\884cID';
COMMENT ON COLUMN warehouse_monthly_close_line.close_id IS U&'\6708\7ed3\5355ID';
COMMENT ON COLUMN warehouse_monthly_close_line.sku_id IS U&'\5546\54c1SKU ID';
COMMENT ON COLUMN warehouse_monthly_close_line.sku_name IS U&'\5546\54c1\540d\79f0';
COMMENT ON COLUMN warehouse_monthly_close_line.opening_qty IS U&'\671f\521d\6570\91cf';
COMMENT ON COLUMN warehouse_monthly_close_line.purchase_in_qty IS U&'\91c7\8d2d\5165\5e93\6570\91cf';
COMMENT ON COLUMN warehouse_monthly_close_line.transfer_in_qty IS U&'\8c03\62e8\5165\5e93\6570\91cf';
COMMENT ON COLUMN warehouse_monthly_close_line.transfer_out_qty IS U&'\8c03\62e8\51fa\5e93\6570\91cf';
COMMENT ON COLUMN warehouse_monthly_close_line.restock_qty IS U&'\4e0a\67dc\6570\91cf';
COMMENT ON COLUMN warehouse_monthly_close_line.return_qty IS U&'\9000\8d27\6570\91cf';
COMMENT ON COLUMN warehouse_monthly_close_line.loss_qty IS U&'\635f\8017\6570\91cf\5408\8ba1';
COMMENT ON COLUMN warehouse_monthly_close_line.expected_qty IS U&'\5e94\6709\6570\91cf';
COMMENT ON COLUMN warehouse_monthly_close_line.counted_qty IS U&'\5b9e\76d8\6570\91cf';
COMMENT ON COLUMN warehouse_monthly_close_line.gap_qty IS U&'\5dee\5f02\6570\91cf';
COMMENT ON COLUMN warehouse_monthly_close_line.gap_amount_cents IS U&'\5dee\5f02\91d1\989d\ff08\5206\ff09';
COMMENT ON COLUMN warehouse_monthly_close_line.gap_disposition IS U&'\5dee\5f02\5904\7f6e\ff08PENDING/CLAIM/NORMAL_LOSS/SURPLUS\ff09';
COMMENT ON COLUMN warehouse_monthly_close_line.claim_write_off_id IS U&'\5173\8054\7d22\8d54\62a5\635f\5355ID';
COMMENT ON COLUMN warehouse_monthly_close_line.created_at IS U&'\521b\5efa\65f6\95f4';
COMMENT ON COLUMN warehouse_monthly_close_line.rock_loss_qty IS U&'\6447\673a\635f\8017\6570\91cf';
COMMENT ON COLUMN warehouse_monthly_close_line.expired_loss_qty IS U&'\8fc7\671f\635f\8017\6570\91cf';
COMMENT ON COLUMN warehouse_monthly_close_line.guest_water_qty IS U&'\5ba2\60c5\7528\6c34\6570\91cf';
COMMENT ON COLUMN warehouse_monthly_close_line.fee_deduct_qty IS U&'\8d39\7528\62b5\6263\6570\91cf';
COMMENT ON COLUMN warehouse_monthly_close_line.unit_price_cents IS U&'\5355\4ef7\ff08\5206\ff09';
COMMENT ON COLUMN warehouse_monthly_close_line.edited_flag IS U&'\8349\7a3f\884c\662f\5426\4eba\5de5\7f16\8f91';
COMMENT ON COLUMN warehouse_monthly_close_line.line_remark IS U&'\884c\5907\6ce8';
