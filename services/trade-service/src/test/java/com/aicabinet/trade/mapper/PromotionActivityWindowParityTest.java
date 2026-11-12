package com.aicabinet.trade.mapper;

import com.aicabinet.trade.domain.PromotionActivity;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.AbstractWrapper;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import java.time.Instant;
import java.util.List;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * P3-5（2026-11-12）：活动「生效中」的三处判据必须与定价 SQL 同口径。
 *
 * <p>修前 {@code findByStatusAndStartTimeBeforeAndEndTimeAfter} 用 MP 派生的
 * {@code lt(start).gt(end)}，与 {@code findPricedCandidates} 的注解 SQL 差两点：
 * ① start 是 {@code <} 而 SQL 是 {@code <=}（起始那一瞬列表判未开始，领券却放行）；
 * ② 两侧都是「列 比较 值」，SQL 是 {@code col IS NULL OR ...} ⇒ **不设起止的发券活动**在运营
 *    「进行中活动」列表里整条消失，而 {@code ConsumerMarketingService#doClaimCampaign}
 *    对 NULL 放行 ⇒ 用户照领、运营看不见。
 *
 * <p>本类真调生产 default 方法并捕获它交给 {@code selectList} 的 wrapper，
 * 断的是**生成出来的 SQL 片段**而不是源码文本——把 {@code le} 改回 {@code lt}、
 * 或删掉任一 {@code isNull} 都会转红。TableInfo 初始化理由同
 * {@link PaymentOperationNetCompletedFilterTest}（纯 Mockito 环境无 SqlSession）。
 */
@ExtendWith(MockitoExtension.class)
class PromotionActivityWindowParityTest {

    static {
        TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(new MybatisConfiguration(), ""),
                PromotionActivity.class);
    }

    @Mock
    PromotionActivityMapper mapper;

    @SuppressWarnings({"unchecked", "rawtypes"})
    private AbstractWrapper captureWindowWrapper() {
        when(mapper.findActiveInWindow(anyString(), any(Instant.class))).thenCallRealMethod();
        when(mapper.selectList(any())).thenReturn(List.of());
        mapper.findActiveInWindow("ACTIVE", Instant.parse("2026-11-12T12:00:00Z"));
        ArgumentCaptor<Wrapper<PromotionActivity>> captor =
                (ArgumentCaptor<Wrapper<PromotionActivity>>) (ArgumentCaptor<?>) ArgumentCaptor.forClass(Wrapper.class);
        verify(mapper).selectList(captor.capture());
        AbstractWrapper wrapper = (AbstractWrapper) captor.getValue();
        wrapper.getSqlSegment(); // 触发列名与参数惰性 format
        return wrapper;
    }

    @Test
    void unboundedActivitiesStayVisible_insteadOfBeingFilteredOutByNullComparison() {
        String sql = captureWindowWrapper().getSqlSegment();
        assertTrue(sql.contains("start_time IS NULL"), "P3-5：未设起始时间的活动不能被 NULL 比较洗掉");
        assertTrue(sql.contains("end_time IS NULL"), "P3-5：未设结束时间的活动不能被 NULL 比较洗掉");
        assertTrue(sql.contains(" OR "), "P3-5：NULL 分支必须是 OR 连到时间比较分支上");
    }

    @Test
    void windowEndpointsMatchPricingSql_startInclusiveEndExclusive() {
        String sql = captureWindowWrapper().getSqlSegment();
        assertTrue(sql.contains("start_time <= "), "P3-5：起点含端点，对齐 SQL 的 start_time <= now()");
        assertTrue(sql.contains("end_time > "), "P3-5：终点不含端点（到点即止），对齐 SQL 的 end_time > now()");
        assertFalse(sql.contains("start_time < "),
                "P3-5：起点退回严格小于会与定价 SQL 在起始那一瞬给出相反结论");
    }

    @Test
    void stillPinsActiveStatusAndSingleClock() {
        AbstractWrapper wrapper = captureWindowWrapper();
        String sql = wrapper.getSqlSegment();
        assertTrue(sql.contains("status"), "活动列表仍必须钉 ACTIVE 状态，不能靠时间窗放行停用活动");
        assertTrue(wrapper.getParamNameValuePairs().values().contains("ACTIVE"));
        long nowCount = wrapper.getParamNameValuePairs().values().stream()
                .filter(v -> v instanceof Instant).count();
        assertTrue(nowCount == 2, "起止两侧必须用同一个 now（各传一次），不能出现两个时钟取值");
    }
}
