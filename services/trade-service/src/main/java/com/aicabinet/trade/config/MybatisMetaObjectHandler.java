package com.aicabinet.trade.config;

import com.baomidou.mybatisplus.core.handlers.MetaObjectHandler;
import org.apache.ibatis.reflection.MetaObject;
import org.springframework.stereotype.Component;

import java.time.Instant;

@Component
public class MybatisMetaObjectHandler implements MetaObjectHandler {

    @Override
    public void insertFill(MetaObject metaObject) {
        Instant now = Instant.now();
        strictInsertFill(metaObject, "createdAt", Instant.class, now);
        strictInsertFill(metaObject, "updatedAt", Instant.class, now);
    }

    @Override
    public void updateFill(MetaObject metaObject) {
        // strictUpdateFill 只在字段为 null 时填充；实体多由 select 读出、updatedAt 已非空，
        // 会原值写回 ⇒ updated_at 长期冻结在创建时刻（2026-10-20 会话列表按更新时间排序时暴露）。
        setFieldValByName("updatedAt", Instant.now(), metaObject);
    }
}
