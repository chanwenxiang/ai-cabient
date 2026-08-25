package com.aicabinet.trade.mapper;

import com.aicabinet.trade.domain.OpsDepartment;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.apache.ibatis.annotations.Mapper;

import java.util.List;
import java.util.Optional;

@Mapper
public interface OpsDepartmentMapper extends BaseTradeMapper<OpsDepartment> {

    default List<OpsDepartment> findAllOrderBySort() {
        return selectList(Wrappers.<OpsDepartment>lambdaQuery()
                .orderByAsc(OpsDepartment::getSortOrder)
                .orderByAsc(OpsDepartment::getDeptId));
    }

    default Optional<OpsDepartment> findByDeptKey(String deptKey) {
        return selectList(Wrappers.<OpsDepartment>lambdaQuery()
                .eq(OpsDepartment::getDeptKey, deptKey)
                .last("LIMIT 1")).stream().findFirst();
    }
}
