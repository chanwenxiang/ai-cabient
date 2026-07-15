package com.aicabinet.trade.mapper;

import com.aicabinet.trade.domain.LineManager;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import java.util.List;
import java.util.Optional;
import org.apache.ibatis.annotations.Mapper;
import org.springframework.stereotype.Repository;

@Mapper
public interface LineManagerMapper extends BaseTradeMapper<LineManager> {

    default Optional<LineManager> findByPhone(String phone) {
    return Optional.ofNullable(selectOne(Wrappers.<LineManager>lambdaQuery().eq(LineManager::getPhone, phone)));
    }

    default Optional<LineManager> findByEmployeeId(String employeeId) {
    return Optional.ofNullable(selectOne(Wrappers.<LineManager>lambdaQuery().eq(LineManager::getEmployeeId, employeeId)));
    }

    default List<LineManager> findByFranchiseId(Long franchiseId) {
    return selectList(Wrappers.<LineManager>lambdaQuery().eq(LineManager::getFranchiseId, franchiseId));
    }

    default List<LineManager> findByStatus(String status) {
    return selectList(Wrappers.<LineManager>lambdaQuery().eq(LineManager::getStatus, status));
    }

}
