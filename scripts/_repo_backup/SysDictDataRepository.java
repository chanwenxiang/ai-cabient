package com.aicabinet.trade.repository;

import com.aicabinet.trade.domain.SysDictData;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface SysDictDataRepository extends JpaRepository<SysDictData, Long> {
    List<SysDictData> findByDictTypeOrderBySortOrderAscDictValueAsc(String dictType);

    List<SysDictData> findByStatusOrderByDictTypeAscSortOrderAsc(String status);

    Optional<SysDictData> findByDictTypeAndDictValue(String dictType, String dictValue);

    long countByDictType(String dictType);

    void deleteByDictType(String dictType);
}
