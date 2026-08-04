package com.aicabinet.trade.mapper;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Convert MyBatis {@code LinkedHashMap} aggregate rows (aliases {@code c0..cN}) into
 * positional {@code Object[]} without relying on {@code Map.values()} order.
 * <p>
 * Some MyBatis/driver paths may expose keys in alphabetical order, so {@code c10}
 * sorts before {@code c2} and silently corrupts column indexes.
 */
final class ColumnMapRows {

    private ColumnMapRows() {
    }

    static List<Object[]> toObjectRows(List<? extends Map<String, Object>> rows, int width) {
        if (rows == null || rows.isEmpty()) {
            return List.of();
        }
        ArrayList<Object[]> out = new ArrayList<>(rows.size());
        for (Map<String, Object> row : rows) {
            Object[] arr = new Object[width];
            for (int i = 0; i < width; i++) {
                arr[i] = cell(row, i);
            }
            out.add(arr);
        }
        return out;
    }

    private static Object cell(Map<String, Object> row, int index) {
        if (row == null) {
            return null;
        }
        Object v = row.get("c" + index);
        if (v != null) {
            return v;
        }
        v = row.get("C" + index);
        if (v != null) {
            return v;
        }
        String want = "c" + index;
        for (Map.Entry<String, Object> e : row.entrySet()) {
            if (e.getKey() != null && e.getKey().equalsIgnoreCase(want)) {
                return e.getValue();
            }
        }
        return null;
    }
}
