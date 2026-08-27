package com.aicabinet.trade.mapper;

import com.aicabinet.trade.domain.MemberLevelRule;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import java.util.List;
import java.util.Optional;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface MemberLevelRuleMapper extends BaseTradeMapper<MemberLevelRule> {

    MemberLevelRule findByIdForUpdateRaw(@Param("id") Long id);

    default Optional<MemberLevelRule> findByIdForUpdate(Long id) {
        return Optional.ofNullable(findByIdForUpdateRaw(id));
    }

    MemberLevelRule findByLevelCodeForUpdateRaw(@Param("levelCode") String levelCode);

    default Optional<MemberLevelRule> findByLevelCodeForUpdate(String levelCode) {
        return Optional.ofNullable(findByLevelCodeForUpdateRaw(levelCode));
    }

    default Optional<MemberLevelRule> findByLevelCode(String levelCode) {
    return Optional.ofNullable(selectOne(Wrappers.<MemberLevelRule>lambdaQuery().eq(MemberLevelRule::getLevelCode, levelCode)));
    }

    default List<MemberLevelRule> findByStatus(String status) {
    return selectList(Wrappers.<MemberLevelRule>lambdaQuery().eq(MemberLevelRule::getStatus, status));
    }

    default List<MemberLevelRule> findByStatusOrderBySortorderAsc(String status) {
    return selectList(Wrappers.<MemberLevelRule>lambdaQuery().eq(MemberLevelRule::getStatus, status).orderByAsc(MemberLevelRule::getSortorder));
    }

}
