package com.aicabinet.trade.mapper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;

class ColumnMapRowsTest {

    @Test
    void readsExplicitCnKeysEvenWhenMapIsAlphabeticallyOrdered() {
        // TreeMap / alphabetized keys put c10 before c2
        Map<String, Object> row = new TreeMap<>();
        row.put("c0", "batch-1");
        row.put("c1", "MCH-1");
        row.put("c2", "2026-07-28");
        row.put("c10", 3L);
        row.put("c3", null);
        for (int i = 4; i <= 9; i++) {
            row.put("c" + i, (long) i);
        }

        List<Object[]> rows = ColumnMapRows.toObjectRows(List.of(row), 11);
        assertEquals(1, rows.size());
        Object[] arr = rows.get(0);
        assertEquals("batch-1", arr[0]);
        assertEquals("MCH-1", arr[1]);
        assertEquals("2026-07-28", arr[2]);
        assertNull(arr[3]);
        assertEquals(4L, arr[4]);
        assertEquals(3L, arr[10]);
    }

    @Test
    void linkedHashMapInsertionOrderIsNotRequired() {
        LinkedHashMap<String, Object> row = new LinkedHashMap<>();
        // Insert out of numeric order on purpose
        row.put("c1", 10);
        row.put("c0", "SKU-1");
        List<Object[]> rows = ColumnMapRows.toObjectRows(List.of(row), 2);
        assertEquals("SKU-1", rows.get(0)[0]);
        assertEquals(10, rows.get(0)[1]);
    }
}
