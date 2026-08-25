package com.aicabinet.trade.mapper;

import com.aicabinet.trade.domain.OpsUserDepartment;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface OpsUserDepartmentMapper extends BaseTradeMapper<OpsUserDepartment> {

    default List<OpsUserDepartment> findByDeptId(Long deptId) {
        return selectList(Wrappers.<OpsUserDepartment>lambdaQuery()
                .eq(OpsUserDepartment::getDeptId, deptId));
    }

    default List<OpsUserDepartment> findByUserId(Long userId) {
        return selectList(Wrappers.<OpsUserDepartment>lambdaQuery()
                .eq(OpsUserDepartment::getUserId, userId));
    }

    default void deleteByDeptId(Long deptId) {
        delete(Wrappers.<OpsUserDepartment>lambdaQuery().eq(OpsUserDepartment::getDeptId, deptId));
    }

    default void deleteByUserId(Long userId) {
        delete(Wrappers.<OpsUserDepartment>lambdaQuery().eq(OpsUserDepartment::getUserId, userId));
    }

    default long countByDeptId(Long deptId) {
        Long c = selectCount(Wrappers.<OpsUserDepartment>lambdaQuery()
                .eq(OpsUserDepartment::getDeptId, deptId));
        return c == null ? 0L : c;
    }

    @Select("""
            SELECT DISTINCT ud.user_id
            FROM ops_user_department ud
            JOIN ops_department d ON d.dept_id = ud.dept_id
            JOIN user_info u ON u.user_id = ud.user_id
            WHERE d.dept_key = #{deptKey}
              AND d.status = 'ACTIVE'
              AND u.status = 'ACTIVE'
              AND ud.user_id >= 100000001
            ORDER BY ud.user_id
            """)
    List<Long> findUserIdsByDeptKey(@Param("deptKey") String deptKey);
}
