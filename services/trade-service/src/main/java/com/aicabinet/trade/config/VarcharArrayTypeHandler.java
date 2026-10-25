package com.aicabinet.trade.config;

import java.sql.Array;
import java.sql.CallableStatement;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.util.Arrays;
import org.apache.ibatis.type.BaseTypeHandler;
import org.apache.ibatis.type.JdbcType;
import org.apache.ibatis.type.MappedTypes;

/**
 * PG varchar[] 数组字段 handler（G6 发现修复）：CouponDefinition.scopeDeviceIds
 * 映射 {@code scope_device_ids VARCHAR(64)[]}。此前 String[] 无 typeHandler ⇒
 * 带 scopeDeviceIds 的 insert 直接 IllegalStateException 500（券定义 DEVICE 范围
 * 写入路径自 V319 以来首跑即炸——见 UAT §7.24.1）。
 *
 * <p>写：Arrays.toString 形态 {@code [a, b]}？——不，用 PG 数组字面量 {@code {a,b}}，
 * 配合 setObject(i, value, Types.OTHER) 让 PG 按 varchar[] 解析。
 * 读：PG 返回 java.sql.Array，取字符串后剥花括号拆分；null/空集安全。
 */
@MappedTypes(String[].class)
public class VarcharArrayTypeHandler extends BaseTypeHandler<String[]> {

    private static final String[] EMPTY = new String[0];

    @Override
    public void setNonNullParameter(PreparedStatement ps, int i, String[] parameter, JdbcType jdbcType)
            throws SQLException {
        StringBuilder sb = new StringBuilder("{");
        for (int k = 0; k < parameter.length; k++) {
            if (k > 0) {
                sb.append(',');
            }
            sb.append(parameter[k]);
        }
        sb.append('}');
        ps.setObject(i, sb.toString(), Types.OTHER);
    }

    @Override
    public String[] getNullableResult(ResultSet rs, String columnName) throws SQLException {
        return fromObject(rs.getObject(columnName));
    }

    @Override
    public String[] getNullableResult(ResultSet rs, int columnIndex) throws SQLException {
        return fromObject(rs.getObject(columnIndex));
    }

    private String[] fromObject(Object v) throws SQLException {
        if (v == null) {
            return null;
        }
        if (v instanceof java.sql.Array arr) {
            String[] raw = (String[]) arr.getArray();
            return raw == null ? EMPTY : raw;
        }
        return parse(String.valueOf(v));
    }

    @Override
    public String[] getNullableResult(CallableStatement cs, int columnIndex) throws SQLException {
        return parse(cs.getString(columnIndex));
    }

    private String[] parse(String pgArray) {
        if (pgArray == null || pgArray.isBlank() || "{}".equals(pgArray.trim())) {
            return EMPTY;
        }
        String body = pgArray.trim();
        if (body.startsWith("{") && body.endsWith("}")) {
            body = body.substring(1, body.length() - 1);
        }
        if (body.isBlank()) {
            return EMPTY;
        }
        // 本项目场景元素为柜机编号（字母数字），无引号/逗号转义需求
        return body.split(",");
    }
}
