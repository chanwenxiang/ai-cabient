package com.aicabinet.trade.repository;

import com.aicabinet.trade.domain.SysDictType;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface SysDictTypeRepository extends JpaRepository<SysDictType, String> {
    List<SysDictType> findAllByOrderBySortOrderAscDictTypeAsc();
}
