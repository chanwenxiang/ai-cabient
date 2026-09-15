package com.aicabinet.trade.support;

import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/** 乐观锁：客户端 expectedVersion 与当前行版本比对。 */
public final class OptimisticLocking {

    private OptimisticLocking() {
    }

    public static void requireMatchingExpectedVersion(Long expectedVersion, long actualVersion) {
        if (expectedVersion == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ApiMessages.EXPECTED_VERSION_REQUIRED);
        }
        if (expectedVersion != actualVersion) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, ApiMessages.OPTIMISTIC_LOCK_CONFLICT);
        }
    }

    /** DeviceInventoryDto.inventoryVersion 为 long；已有行时按值比对。 */
    public static void requireMatchingInventoryVersion(long expectedVersion, long actualVersion) {
        requireMatchingExpectedVersion(expectedVersion, actualVersion);
    }
}
