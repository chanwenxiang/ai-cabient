package com.aicabinet.trade.payment;

import com.aicabinet.trade.config.ProfitSharingProperties;
import com.aicabinet.trade.config.WeChatPayProperties;
import com.aicabinet.trade.domain.OrderRevenueSplit;
import com.aicabinet.trade.mapper.OrderRevenueSplitMapper;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 审计 R11-B-N1（M01 清列家族第 7 处）：persistSplit 是 refreshSplitStatus 的唯一落盘点。
 * MyBatis-Plus 全仓无 FieldStrategy 覆盖 ⇒ save/updateById 默认 NOT_NULL 策略跳 null ——
 * applyRemoteState 把 FAILED 单刷新成 FINISHED/PROCESSING 时 setFailureReason(null)
 * 不会真清列，旧失败文案残留库中与 WECHAT_SUBMITTED 自相矛盾。必须补 wrapper 真清列。
 */
@ExtendWith(MockitoExtension.class)
class WeChatProfitSharingPersistClearReasonTest {
    static {
        // persistSplit 的清列走 LambdaUpdateWrapper：纯 Mockito 环境需注册 TableInfo
        // （照 WeChatProfitSharingServiceZeroRateTest 同款）
        TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(new MybatisConfiguration(), ""),
                OrderRevenueSplit.class);
    }

    @Mock private WeChatPayV3Client client;
    @Mock private OrderRevenueSplitMapper splitRepository;

    private WeChatProfitSharingService service;

    @BeforeEach
    void setUp() {
        ProfitSharingProperties sharing = new ProfitSharingProperties(true, false, true, 20);
        WeChatPayProperties pay = new WeChatPayProperties(
                true, "wx-appid", "1900000109", "https://example.test/notify",
                "0123456789abcdef0123456789abcdef", "SERIAL-1", "PRIVATE-PEM", null, false);
        service = new WeChatProfitSharingService(sharing, pay, client, splitRepository, null, null);
    }

    @Test
    void persistWithNullFailureReason_clearsStaleColumn() {
        OrderRevenueSplit s = new OrderRevenueSplit();
        s.setSplitId("S-1");
        s.setOrderId("O-1");
        s.setMerchantId("M-1");
        s.setStatus("WECHAT_SUBMITTED");
        s.setFailureReason(null);
        when(splitRepository.save(any(OrderRevenueSplit.class))).thenAnswer(inv -> inv.getArgument(0));

        service.persistSplit(s);

        // save 后必须补 update(entity=null, wrapper) 把 failure_reason 真清掉
        verify(splitRepository).update(isNull(), any(com.baomidou.mybatisplus.core.conditions.Wrapper.class));
    }

    @Test
    void persistWithNonNullReason_doesNotTouchColumn() {
        OrderRevenueSplit s = new OrderRevenueSplit();
        s.setSplitId("S-2");
        s.setOrderId("O-2");
        s.setMerchantId("M-1");
        s.setStatus("WECHAT_FAILED");
        s.setFailureReason("insufficient balance");
        when(splitRepository.save(any(OrderRevenueSplit.class))).thenAnswer(inv -> inv.getArgument(0));

        service.persistSplit(s);

        // 有失败文案时无需清列（catch 路径写非空 reason 不受影响）
        verify(splitRepository, never()).update(any(), any(com.baomidou.mybatisplus.core.conditions.Wrapper.class));
    }
}
