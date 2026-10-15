package com.aicabinet.trade.mapper;

import com.aicabinet.trade.domain.SlotTemplateLine;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.apache.ibatis.annotations.Mapper;

import java.util.List;

@Mapper
public interface SlotTemplateLineMapper extends BaseMapper<SlotTemplateLine> {

    default List<SlotTemplateLine> findByTemplateId(Long templateId) {
        return selectList(Wrappers.<SlotTemplateLine>lambdaQuery()
                .eq(SlotTemplateLine::getTemplateId, templateId)
                .orderByAsc(SlotTemplateLine::getSlotCode));
    }

    default void deleteByTemplateId(Long templateId) {
        delete(Wrappers.<SlotTemplateLine>lambdaQuery()
                .eq(SlotTemplateLine::getTemplateId, templateId));
    }
}
