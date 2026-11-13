#!/usr/bin/env node
/**
 * R18–R22 修复「在位性」批检（一次性核查工具，非门禁）：全部基于当前 HEAD 实测。
 * 路径与形态均已按实际勘测修正（test 目录层级 / record 属性 / 真实落点文件）。
 */
import { existsSync, readFileSync } from 'node:fs';
import { resolve, dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

const ROOT = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const read = (rel) => (existsSync(join(ROOT, rel)) ? readFileSync(join(ROOT, rel), 'utf8') : null);

const S = 'services/trade-service/src/main/java/com/aicabinet/trade';
const G = 'services/jiangyi-gateway/src/main/java/com/aicabinet/jiangyi';
const D = 'services/device-service/src/main/java/com/aicabinet/device';
const T = 'services/trade-service/src/test/java/com/aicabinet/trade';

const CHECKS = [
  { id: 'R18-1 watchdog 阈值配置化', file: `${G}/tracker/SessionWatchdog.java`, re: /open-timeout-ms|openTimeoutMs/ },
  { id: 'R18-1 默认 70s', file: 'services/jiangyi-gateway/src/main/resources/application.yml', re: /open-timeout-ms:/ },
  { id: 'R18-1 SessionWatchdogTest 钉契约', file: 'services/jiangyi-gateway/src/test/java/com/aicabinet/jiangyi/tracker/SessionWatchdogTest.java', re: /open-timeout|threshold/ },
  { id: 'R18-3 V458 种模板', file: 'services/trade-service/src/main/resources/db/migration/V458__dispute_created_template.sql', re: /dispute_created/ },
  { id: 'R18-3 模板种库门禁', file: 'scripts/check-notification-template-seeded.mjs', re: /notifyConsumer|send/ },
  { id: 'R18-2/4/5 increaseQuantityAtomic', file: `${S}/mapper/DeviceSkuLotMapper.java`, re: /increaseQuantityAtomic/ },
  { id: 'R18-2/4/5 updateStatusAndSlotById', file: `${S}/mapper/DeviceSkuLotMapper.java`, re: /updateStatusAndSlotById/ },
  { id: 'R18-5 applyDelta 原子/CAS', file: `${S}/mapper/DeviceSkuInventoryMapper.java`, re: /version = version \+ 1/ },
  { id: 'R18-5 InventoryConsistencyR18IT', file: `${T}/integration/InventoryConsistencyR18IT.java`, re: /@Testcontainers|@Test/ },
  { id: 'R18-6 finish try/finally', file: `${S}/service/ScheduledTaskService.java`, re: /finally/ },
  { id: 'R18-7 renew 三态', file: `${S}/service/DistributedLockService.java`, re: /RENEWED|LOST|TRANSIENT/ },
  { id: 'R18-8 心跳带身份摘除', file: `${S}/service/ScheduledTaskService.java`, re: /remove\(.*myFuture|remove\(key/ },
  { id: 'R18-8 DistributedLockServiceRenewTest', file: `${T}/service/DistributedLockServiceRenewTest.java`, re: /@Test/ },
  { id: 'R19-15 收款账户商户闸', file: `${S}/service/MerchantScopeService.java`, re: /requireMerchantAccess/ },
  { id: 'R19-15 OpsPayoutAccountScopeTest', file: `${T}/api/OpsPayoutAccountScopeTest.java`, re: /@Test/ },
  { id: 'R19-16 compose-image-tags 门禁', file: 'scripts/check-compose-image-tags.mjs', re: /selfTest/ },
  { id: 'R19-18 ZREM 抢占令牌', file: `${D}/service/DeviceCommandTracker.java`, re: /zrem|ZREM/ },
  { id: 'R19-18 专用线程池', file: `${D}/service/DeviceCommandTracker.java`, re: /new ThreadPoolExecutor|CallerRunsPolicy/ },
  { id: 'R19-18 DeviceCommandTrackerClaimTest', file: 'services/device-service/src/test/java/com/aicabinet/device/service/DeviceCommandTrackerClaimTest.java', re: /@Test/ },
  { id: 'R19-19 watchdog UUID token + Lua 释放', file: `${G}/tracker/SessionWatchdog.java`, re: /RELEASE_IF_OWNER|UUID/ },
  { id: 'R19-20 池容量判据 ×2', file: 'scripts/check-scheduling-vs-db-pool.mjs', re: /CONNECTIONS_PER_TASK = 2/ },
  { id: 'R19-22 XXL executor_timeout 上限判据', file: 'scripts/check-xxl-job-wiring.mjs', re: /executor_?[Tt]imeout|3\.12/ },
  { id: 'R19-23 rbac-unavailable 页', file: 'clients/admin-vue/src/views/error/RbacUnavailableView.vue', re: /retry|重试/ },
  { id: 'R19-27 nginx 三份同源门禁', re: /log_format ws_noargs|selfTest/, file: 'scripts/check-nginx-gateway-parity.mjs' },
  { id: 'R19-29 备份告警 alert()', file: 'scripts/backup-postgres.sh', re: /BACKUP_ALERT_CMD|last-success-epoch/ },
  { id: 'R19-30 rangeByScore 批上限', file: `${D}/service/DeviceCommandTracker.java`, re: /limit|LIMIT|批/ },
  { id: 'R20-P2-1 isFulfillableStatus 白名单', file: `${S}/service/ReplenishmentService.java`, re: /isFulfillableStatus/ },
  { id: 'R20-P2-2 cancelIfStatus 条件更新', file: `${S}/mapper/ReplenishmentTaskMapper.java`, re: /cancelIfStatus/ },
  { id: 'R20-P2-3 闸在 prepareConfirmDispute', file: `${S}/service/SettlementConfirmDisputeService.java`, re: /isNonConsumerSession/ },
  { id: 'R20-P2-4 doCreditRechargeAfterChannelPaid', file: `${S}/service/PaymentService.java`, re: /doCreditRechargeAfterChannelPaid/ },
  { id: 'R20-P2-5 PayScore 只进不退', file: `${S}/service/PayScoreOrderNotifyService.java`, re: /setOrderState/ },
  { id: 'R20-P2-6 syncChannelSideRefund 幂等闸', file: `${S}/service/PaymentService.java`, re: /syncChannelSideRefund/ },
  { id: 'R20-P2-7 对账渠道过滤', file: `${S}/mapper/PaymentReconciliationMapper.java`, re: /findPaidOrderIdsBetween|channel/ },
  { id: 'R20-P2-8 充值退净额', file: `${S}/service/PaymentService.java`, re: /min\(|prepareChannelPartialRefund/ },
  { id: 'R20-1 退款 ABNORMAL 不入账', file: `${S}/service/PaymentService.java`, re: /REFUND_FAILED|ABNORMAL/ },
  { id: 'R20-5 V459 积分过期列', file: 'services/trade-service/src/main/resources/db/migration/V459__member_points_expired_points.sql', re: /expired_points/ },
  { id: 'R20-P2-7 RechargeOrderMapperXmlTest', file: `${T}/mapper/RechargeOrderMapperXmlTest.java`, re: /getBoundSql|XMLMapperBuilder/ },
  { id: 'R21-3 锁边界 afterCompletion', file: `${S}/service/InventoryService.java`, re: /afterCompletion/ },
  { id: 'R22-1 reason 拼 #orderId', file: `${S}/service/PaymentService.java`, re: /#/ },
  { id: 'R22-2 短信额度后置', file: `${S}/sms/SmsCodeService.java`, re: /先发送后计额度/ },
  { id: 'R22-5 资金表删除保护', file: `${S}/service/AdminDataManageService.java`, re: /FUNDS_LEDGER_TABLES|deleteProtectedTables/ },
  { id: 'R22-6 CHARGE_PENDING 巡检不短路', file: `${S}/service/UnpaidOrderService.java`, re: /alertStaleChargePendingOps/ },
  { id: 'R22-7 采集 start 先落意图', file: `${S}/service/JiangyiCabinetCaptureService.java`, re: /意图|FAILED/ },
  { id: 'R22-8 支付宝空账单 fail-fast', file: `${S}/reconciliation/AlipayPlatformBillProvider.java`, re: /avoid silent MATCHED/ },
  { id: 'R22-9 遥测保留窗', file: `${S}/service/GrowthLogArchiveScheduler.java`, re: /temperature_days|reading_id/ },
  { id: 'R22-10 业务日统一 ScheduleZones', file: `${S}/support/ScheduleZones.java`, re: /ZONE_ID/ }
];

let pass = 0;
const fails = [];
for (const c of CHECKS) {
  const src = read(c.file);
  const ok = src !== null && c.re.test(src);
  if (ok) {
    pass++;
    console.log(`PASS ${c.id}`);
  } else {
    fails.push(c.id + (src === null ? ' [文件不存在]' : ''));
    console.log(`FAIL ${c.id}${src === null ? ' [文件不存在]' : ''} (${c.file})`);
  }
}
console.log(`\n==== ${pass}/${CHECKS.length} PASS，FAIL ${fails.length} 条`);
if (fails.length) console.log('FAIL 清单：\n  - ' + fails.join('\n  - '));
