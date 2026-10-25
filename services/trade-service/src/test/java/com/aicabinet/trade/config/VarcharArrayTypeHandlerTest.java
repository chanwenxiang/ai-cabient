package com.aicabinet.trade.config;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

/** VarcharArrayTypeHandler：PG varchar[] 字面量 {@code {a,b}} 与 String[] 互转。 */
class VarcharArrayTypeHandlerTest {

    private final VarcharArrayTypeHandler handler = new VarcharArrayTypeHandler();

    @Test
    void setParameter_writesPgArrayLiteral() throws SQLException {
        java.sql.PreparedStatement ps = Mockito.mock(java.sql.PreparedStatement.class);
        handler.setNonNullParameter(ps, 1, new String[] { "EG1", "EG2" }, null);
        Mockito.verify(ps).setObject(Mockito.eq(1), Mockito.eq("{EG1,EG2}"), Mockito.eq(java.sql.Types.OTHER));
    }

    @Test
    void setParameter_emptyArray_writesEmptyBraces() throws SQLException {
        java.sql.PreparedStatement ps = Mockito.mock(java.sql.PreparedStatement.class);
        handler.setNonNullParameter(ps, 1, new String[0], null);
        Mockito.verify(ps).setObject(Mockito.eq(1), Mockito.eq("{}"), Mockito.eq(java.sql.Types.OTHER));
    }

    @Test
    void read_parsesPgLiteral() throws SQLException {
        ResultSet rs = Mockito.mock(ResultSet.class);
        Mockito.when(rs.getObject("col")).thenReturn("{EG1,EG2}");
        assertArrayEquals(new String[] { "EG1", "EG2" }, handler.getNullableResult(rs, "col"));
    }

    @Test
    void read_nullAndEmptySafe() throws SQLException {
        ResultSet rs = Mockito.mock(ResultSet.class);
        Mockito.when(rs.getObject("col")).thenReturn(null);
        assertNull(handler.getNullableResult(rs, "col"));
        Mockito.when(rs.getObject("col")).thenReturn("{}");
        assertEquals(0, handler.getNullableResult(rs, "col").length);
        Mockito.when(rs.getObject("col")).thenReturn("");
        assertEquals(0, handler.getNullableResult(rs, "col").length);
    }

    @Test
    void read_fromPgArrayObject() throws SQLException {
        ResultSet rs = Mockito.mock(ResultSet.class);
        java.sql.Array arr = Mockito.mock(java.sql.Array.class);
        Mockito.when(arr.getArray()).thenReturn(new String[] { "A", "B" });
        Mockito.when(rs.getObject("col")).thenReturn(arr);
        assertArrayEquals(new String[] { "A", "B" }, handler.getNullableResult(rs, "col"));
    }

    @Test
    void read_fromStringColumn() throws SQLException {
        ResultSet rs = Mockito.mock(ResultSet.class);
        Mockito.when(rs.getString("col")).thenReturn("{A,B}");
        Mockito.when(rs.getObject("col")).thenReturn("{A,B}");
        assertArrayEquals(new String[] { "A", "B" }, handler.getNullableResult(rs, "col"));
    }
}
