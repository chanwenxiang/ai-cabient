package com.aicabinet.trade.service;

import com.aicabinet.common.dto.WarehouseMonthlyCloseDto;
import com.aicabinet.trade.domain.ExternalChannelFlow;
import com.aicabinet.trade.domain.Warehouse;
import com.aicabinet.trade.mapper.DeviceInfoMapper;
import com.aicabinet.trade.mapper.ExternalChannelFlowMapper;
import com.aicabinet.trade.mapper.InventoryWriteOffMapper;
import com.aicabinet.trade.mapper.SkuCatalogMapper;
import com.aicabinet.trade.mapper.UserInfoMapper;
import com.aicabinet.trade.mapper.WarehouseMapper;
import com.aicabinet.trade.mapper.WarehouseMonthlyCloseLineMapper;
import com.aicabinet.trade.mapper.WarehouseMonthlyCloseSheetMapper;
import com.aicabinet.trade.mapper.WarehouseMovementMapper;
import com.aicabinet.trade.mapper.WarehouseOutboundLineMapper;
import com.aicabinet.trade.mapper.WarehouseStocktakeLineMapper;
import com.aicabinet.trade.mapper.WarehouseStocktakeMapper;
import com.aicabinet.trade.domain.DeviceInfo;
import com.aicabinet.trade.domain.InventoryWriteOff;
import com.aicabinet.trade.domain.WarehouseStocktake;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.AbstractWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * MA-268：月结「期初外部盘点流水」必须取本期标签 {@code periodLabel}。
 *
 * <p>导入口径（{@code flowStocktakeBySku} javadoc）：N 期末盘点填<b>下一账期</b>。
 * 于是本期期末在 {@code nextLabel}，上期期末（= 本期期初）在 {@code periodLabel}。
 * 原实现写成 {@code period.minusMonths(1)}（prevLabel），拿到的是「上期期初」的旧盘点，
 * 且仅在上期未关账、需要外部流水兜底时才触发 —— 症状隐蔽但期初数会整整错一个月。
 */
class WarehouseMonthlyCloseOpeningLabelTest {

    private static final String WH = "WH-1";
    private static final String YEAR_MONTH = "2026-10"; // closeSheet 入参（yyyy-MM）
    // PERIOD_FMT = yyyyMM，外部流水的 periodLabel 是紧凑格式（实测 MPGENVAL1=202610）
    private static final String MONTH = "202610"; // periodLabel = 本期
    private static final String PREV = "202609";
    private static final String NEXT = "202611";

    private ExternalChannelFlowMapper flowMapper;
    private WarehouseMonthlyCloseService service;

    @BeforeEach
    void setUp() {
        // 纯单测里没人初始化 MyBatis-Plus 的 lambda 列缓存：closeSheet 用
        // Wrappers.<Entity>lambdaQuery().eq(Entity::getXxx, …) 时要查列名，
        // 缺缓存直接抛「can not find lambda cache」（同 ApprovalNodeUpsertTest 的坑）。
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "");
        for (Class<?> entity : List.of(ExternalChannelFlow.class, DeviceInfo.class,
                InventoryWriteOff.class, WarehouseStocktake.class)) {
            TableInfoHelper.initTableInfo(assistant, entity);
        }
        WarehouseMapper warehouseMapper = mock(WarehouseMapper.class);
        WarehouseMovementMapper movementMapper = mock(WarehouseMovementMapper.class);
        flowMapper = mock(ExternalChannelFlowMapper.class);

        Warehouse warehouse = new Warehouse();
        warehouse.setWarehouseId(WH);
        warehouse.setWarehouseName("测试仓");
        when(warehouseMapper.findById(WH)).thenReturn(Optional.of(warehouse));
        when(movementMapper.findByWarehouseId(WH)).thenReturn(List.of());

        service = new WarehouseMonthlyCloseService(
                warehouseMapper, movementMapper,
                mock(WarehouseStocktakeMapper.class), mock(WarehouseStocktakeLineMapper.class),
                mock(SkuCatalogMapper.class), mock(WarehouseMonthlyCloseSheetMapper.class),
                mock(WarehouseMonthlyCloseLineMapper.class), mock(InventoryWriteOffMapper.class),
                flowMapper, mock(UserInfoMapper.class),
                mock(DeviceInfoMapper.class), mock(WarehouseOutboundLineMapper.class));
    }

    private static ExternalChannelFlow flow(String label, String sku, int qty) {
        ExternalChannelFlow f = new ExternalChannelFlow();
        f.setPeriodLabel(label);
        f.setSkuId(sku);
        f.setQty(qty);
        return f;
    }

    /** 第一次取外部盘点流水必须是本期标签，不是上期标签。 */
    @Test
    void openingStocktakeQueryUsesCurrentPeriodLabel() {
        when(flowMapper.selectList(any())).thenReturn(List.of());

        service.closeSheet(WH, YEAR_MONTH);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Wrapper<ExternalChannelFlow>> captor =
                ArgumentCaptor.forClass((Class) Wrapper.class);
        verify(flowMapper, atLeastOnce()).selectList(captor.capture());
        assertTrue(captor.getAllValues().size() >= 1, "closeSheet 应至少查一次外部盘点流水");
        AbstractWrapper<?, ?, ?> first = (AbstractWrapper<?, ?, ?>) captor.getAllValues().get(0);
        // getSqlSegment() 才会触发 lambda 列解析并把 .eq 的值写进 paramNameValuePairs；
        // 直接读 getParamNameValuePairs() 得到的是空 Map。
        first.getSqlSegment();
        String params = String.valueOf(first.getParamNameValuePairs());
        assertTrue(params.contains(MONTH),
                "期初外部盘点必须按本期标签 periodLabel 取（导入口径：上期期末填下一账期）：" + params);
        assertFalse(params.contains(PREV),
                "取到 prevLabel 就是 MA-268 的原缺陷（拿到上期期初的旧盘点）：" + params);
    }

    /**
     * 端到端：本期标签里有 42、上期标签里有 99、下期标签里是期末盘点 7。
     * 修后期初应取 42；修前会取 99。
     */
    @Test
    void openingQtyComesFromCurrentPeriodStocktakeNotPrevious() {
        when(flowMapper.selectList(any())).thenAnswer(inv -> {
            AbstractWrapper<?, ?, ?> w = inv.getArgument(0);
            w.getSqlSegment(); // 触发参数填充
            String params = String.valueOf(w.getParamNameValuePairs());
            if (params.contains(PREV)) return List.of(flow(PREV, "SKU-A", 99));
            if (params.contains(NEXT)) return List.of(flow(NEXT, "SKU-A", 7));
            if (params.contains(MONTH)) return List.of(flow(MONTH, "SKU-A", 42));
            return List.of();
        });

        WarehouseMonthlyCloseDto dto = service.closeSheet(WH, YEAR_MONTH);

        assertEquals(1, dto.lines().size(), "期末盘点流水应把 SKU-A 带进月结行");
        assertEquals(42, dto.lines().get(0).openingQty(),
                "期初应取本期标签 periodLabel 的 42；取到 99 说明又用了 prevLabel");
    }
}
