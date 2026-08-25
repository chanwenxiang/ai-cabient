package com.aicabinet.trade.mapper;

import com.aicabinet.trade.domain.NotificationTemplate;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.apache.ibatis.annotations.Mapper;

import java.util.Optional;

@Mapper
public interface NotificationTemplateMapper extends BaseTradeMapper<NotificationTemplate> {

    default Optional<NotificationTemplate> findByCode(String templateCode) {
        return Optional.ofNullable(selectOne(Wrappers.<NotificationTemplate>lambdaQuery()
                .eq(NotificationTemplate::getTemplateCode, templateCode)
                .eq(NotificationTemplate::getStatus, "ACTIVE")));
    }
}
