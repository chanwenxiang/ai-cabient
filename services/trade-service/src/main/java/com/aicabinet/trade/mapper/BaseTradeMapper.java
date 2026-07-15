package com.aicabinet.trade.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.metadata.TableInfo;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.baomidou.mybatisplus.core.toolkit.ReflectionKit;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

/**
 * Compatibility helpers so services can keep Spring Data-style calls
 * (findById / save / findAll / …) while using MyBatis-Plus.
 * Does not override BaseMapper CRUD method names used by MyBatis proxies.
 */
public interface BaseTradeMapper<T> extends BaseMapper<T> {

    default Optional<T> findById(Serializable id) {
        return Optional.ofNullable(selectById(id));
    }

    default List<T> findAll() {
        return selectList(null);
    }

    default List<T> findAllById(Collection<? extends Serializable> ids) {
        if (ids == null || ids.isEmpty()) {
            return Collections.emptyList();
        }
        return selectBatchIds(ids);
    }

    default boolean existsById(Serializable id) {
        return selectById(id) != null;
    }

    default long count() {
        Long c = selectCount(null);
        return c == null ? 0L : c;
    }

    default void delete(T entity) {
        Object id = extractId(entity);
        if (id != null) {
            deleteById((Serializable) id);
        }
    }

    default void deleteAll(Iterable<? extends T> entities) {
        for (T entity : entities) {
            delete(entity);
        }
    }

    default T save(T entity) {
        Object id = extractId(entity);
        if (id == null) {
            insert(entity);
            return entity;
        }
        T existing = selectById((Serializable) id);
        if (existing == null) {
            insert(entity);
        } else {
            updateById(entity);
        }
        return entity;
    }

    default List<T> saveAll(Iterable<T> entities) {
        List<T> saved = new ArrayList<>();
        for (T entity : entities) {
            saved.add(save(entity));
        }
        return saved;
    }

    /** No-op for MyBatis (auto-commit / Tx sync); keeps Spring Data call sites compiling. */
    default void flush() {
    }

    default T saveAndFlush(T entity) {
        return save(entity);
    }

    private static Object extractId(Object entity) {
        if (entity == null) {
            return null;
        }
        // Prefer MyBatis-Plus @TableId metadata — heuristic field names (e.g. deviceId)
        // must not override the real primary key (OpsException.exceptionId, etc.).
        TableInfo tableInfo = TableInfoHelper.getTableInfo(entity.getClass());
        if (tableInfo != null && tableInfo.getKeyProperty() != null) {
            try {
                return ReflectionKit.getFieldValue(entity, tableInfo.getKeyProperty());
            } catch (Exception ignored) {
                // fall through
            }
        }
        for (String name : List.of(
                "id", "exceptionId", "userId", "orderId", "sessionId", "deviceId", "merchantId",
                "skuId", "configKey", "key", "snapshotDate", "className", "ticketId", "splitId",
                "txId", "opId", "lotId", "warehouseId", "dictType")) {
            try {
                Object v = ReflectionKit.getFieldValue(entity, name);
                if (v != null) {
                    return v;
                }
            } catch (Exception ignored) {
                // field may not exist
            }
        }
        return null;
    }
}
