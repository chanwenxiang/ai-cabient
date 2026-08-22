package com.aicabinet.trade.mapper;

import com.aicabinet.trade.domain.SysDictData;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import java.util.List;
import java.util.Optional;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface SysDictDataMapper extends BaseTradeMapper<SysDictData> {

    SysDictData _findByIdForUpdateRaw(@Param("dictDataId") Long dictDataId);

    default Optional<SysDictData> findByIdForUpdate(Long dictDataId) {
        return Optional.ofNullable(_findByIdForUpdateRaw(dictDataId));
    }

    SysDictData _findByDictTypeAndDictValueForUpdateRaw(
            @Param("dictType") String dictType, @Param("dictValue") String dictValue);

    default Optional<SysDictData> findByDictTypeAndDictValueForUpdate(String dictType, String dictValue) {
        return Optional.ofNullable(_findByDictTypeAndDictValueForUpdateRaw(dictType, dictValue));
    }

    default List<SysDictData> findByDictTypeOrderBySortOrderAscDictValueAsc(String dictType) {
    return selectList(Wrappers.<SysDictData>lambdaQuery().eq(SysDictData::getDictType, dictType).orderByAsc(SysDictData::getSortOrder).orderByAsc(SysDictData::getDictValue));
    }

    default List<SysDictData> findByStatusOrderByDictTypeAscSortOrderAsc(String status) {
    return selectList(Wrappers.<SysDictData>lambdaQuery().eq(SysDictData::getStatus, status).orderByAsc(SysDictData::getDictType).orderByAsc(SysDictData::getSortOrder));
    }

    default Optional<SysDictData> findByDictTypeAndDictValue(String dictType, String dictValue) {
    return Optional.ofNullable(selectOne(Wrappers.<SysDictData>lambdaQuery().eq(SysDictData::getDictType, dictType).eq(SysDictData::getDictValue, dictValue)));
    }

    default long countByDictType(String dictType) {
    Long c = selectCount(Wrappers.<SysDictData>lambdaQuery().eq(SysDictData::getDictType, dictType));
    return c == null ? 0 : c;
    }

    default void deleteByDictType(String dictType) {
    delete(Wrappers.<SysDictData>lambdaQuery().eq(SysDictData::getDictType, dictType));
    }

}
