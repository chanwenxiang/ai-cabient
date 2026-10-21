package com.aicabinet.trade.config;

import com.aicabinet.trade.domain.ShoppingSession;
import org.apache.ibatis.reflection.SystemMetaObject;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertTrue;

class MybatisMetaObjectHandlerTest {

    /** 实体从库里带出的 updatedAt 非空时也必须刷新——否则 update_at 冻结在创建时刻。 */
    @Test
    void updateFill_overwritesStaleUpdatedAtLoadedFromDb() {
        ShoppingSession session = new ShoppingSession();
        session.setUpdatedAt(Instant.EPOCH);

        new MybatisMetaObjectHandler().updateFill(SystemMetaObject.forObject(session));

        assertTrue(session.getUpdatedAt().isAfter(Instant.EPOCH));
    }
}
