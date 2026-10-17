package com.aicabinet.trade.mapper;

import com.aicabinet.trade.domain.PaymentOperation;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.AbstractWrapper;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Collection;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 审计 F-10（2026-10-17）：净额三原语（{@code netCompletedCents} / {@code chargedCompletedCents} /
 * {@code refundedCompletedCents}）共用 {@link PaymentOperationMapper#findCompletedPaymentOpsByOrderId}
 * 的 SQL 过滤，而过滤的 {@code IN} 清单漏了 {@code PREAUTH_CAPTURE}——
 * 两个 switch 里的 PREAUTH_CAPTURE 分支与三处注释（F1-A「冲抵行挂单，供净额可见」）全是死代码，
 * 于是 F1 竞态护栏 {@code UnpaidOrderService:382} 与补扣净额 {@code OrderPaymentService:418}
 * 对冲抵部分失明（会按全额重复扣）。
 *
 * <p>本类<b>真调生产 default 方法</b>：负向用例捕获生产 wrapper 并断言其参数值集合
 * （MP 3.5.5 参数惰性 format，须先 {@code getSqlSegment()} 触发才有 pairs，教训同
 * {@code SupplierReconciliationServiceTest:122}）。修前 {@code IN} 清单无 PREAUTH_CAPTURE ⇒ 必红；
 * 若只 stub {@code selectList} 返回行再断净额，switch 早已认该类型 ⇒ 恒绿，测不出本缺陷。
 */
@ExtendWith(MockitoExtension.class)
class PaymentOperationNetCompletedFilterTest {

    static {
        // 纯 Mockito 环境无 SqlSession ⇒ LambdaQueryWrapper 解析列名需要 TableInfo 缓存
        TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(new MybatisConfiguration(), ""),
                PaymentOperation.class);
    }

    @Mock PaymentOperationMapper mapper;

    /** 真调生产 default 方法，捕获它交给 SQL 的 wrapper。 */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private Collection<Object> captureQueryParams() {
        // mock 默认吞掉 default 方法 ⇒ 须显式 thenCallRealMethod 才走生产实现
        when(mapper.findCompletedPaymentOpsByOrderId(anyString())).thenCallRealMethod();
        when(mapper.selectList(any())).thenReturn(List.of());
        mapper.findCompletedPaymentOpsByOrderId("O-F10");
        ArgumentCaptor<Wrapper<PaymentOperation>> captor =
                (ArgumentCaptor<Wrapper<PaymentOperation>>) (ArgumentCaptor<?>) ArgumentCaptor.forClass(Wrapper.class);
        verify(mapper).selectList(captor.capture());
        // pairs 只在 AbstractWrapper 上暴露（生产用的 LambdaQueryWrapper 即其子类）
        AbstractWrapper wrapper = (AbstractWrapper) captor.getValue();
        wrapper.getSqlSegment();
        return wrapper.getParamNameValuePairs().values();
    }

    @Test
    void completedOpsQuery_coversPreauthCaptureRows() {
        Collection<Object> params = captureQueryParams();
        assertTrue(params.contains("PREAUTH_CAPTURE"),
                "F-10：冲抵行已挂 orderId，净额查询的 IN 清单必须含 PREAUTH_CAPTURE，否则补扣护栏失明");
    }

    @Test
    void completedOpsQuery_keepsChargeAdjustAndRefund() {
        Collection<Object> params = captureQueryParams();
        assertTrue(params.containsAll(List.of("CHARGE", "ADJUST_CHARGE", "REFUND")),
                "查询清单不得只加不减：购物扣款/调整/退款三类仍是净额口径的组成");
    }

    @Test
    void netCompletedCents_countsPreauthCaptureAsIncome() {
        PaymentOperation capture = op("PREAUTH_CAPTURE", 60);
        PaymentOperation refund = op("REFUND", 20);
        when(mapper.selectList(any())).thenReturn(List.of(capture, refund));
        when(mapper.findCompletedPaymentOpsByOrderId("O-F10")).thenCallRealMethod();
        when(mapper.netCompletedCents("O-F10")).thenCallRealMethod();

        assertEquals(40, mapper.netCompletedCents("O-F10"),
                "switch 侧口径：冲抵计正、退款计负（本用例钉住 Java 侧，SQL 侧见上两条）");
    }

    private static PaymentOperation op(String type, int cents) {
        PaymentOperation op = new PaymentOperation();
        op.setOrderId("O-F10");
        op.setOperationType(type);
        op.setAmountCents(cents);
        op.setStatus("COMPLETED");
        return op;
    }
}
