package com.aicabinet.trade.mapper;

import com.aicabinet.trade.domain.Franchise;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import java.util.List;
import java.util.Optional;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface FranchiseMapper extends BaseTradeMapper<Franchise> {

    default Optional<Franchise> findByFranchiseCode(String franchiseCode) {
    return Optional.ofNullable(selectOne(Wrappers.<Franchise>lambdaQuery().eq(Franchise::getFranchiseCode, franchiseCode)));
    }

    default List<Franchise> findByStatus(String status) {
    return selectList(Wrappers.<Franchise>lambdaQuery().eq(Franchise::getStatus, status));
    }

    default List<Franchise> findByProvinceAndCity(String province, String city) {
    return selectList(Wrappers.<Franchise>lambdaQuery().eq(Franchise::getProvince, province).eq(Franchise::getCity, city));
    }

        List<Franchise> findActiveWithValidContract();

}
