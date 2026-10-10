package com.aicabinet.trade.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;

/**
 * 将邑模型 classes 映射文件解析器（CB-023，采集文档 §4.4.4 modelTextUrl）。
 *
 * <p>classes.txt 每行 = 一个商品 textName；classId 与行号的对应方向（0/1-based）文档
 * 未明示（PDF 无此说明），以 {@code classIdBase} 可配 + admin 预览人工确认为兜底
 * （CB-023 台账局限 1）。行数必须等于模型 quantity，不符即拒绝预生成（负向：宁可
 * 人工介入，不预生成错位映射——错位映射一旦激活会直接错误扣款）。</p>
 *
 * <p>classesVersion = 内容 sha256 前 12 位（我方计算；设备不报版本，作为下发审计
 * 与设备登记的 classes 指纹）。</p>
 */
public final class JiangyiClassesParser {

    private JiangyiClassesParser() {}

    /** 单行解析产物：classId = 行号 + classIdBase。 */
    public record ParsedClassRow(int classId, String textName, int lineNumber) {}

    public record ParsedClasses(List<ParsedClassRow> rows, String classesVersion) {}

    public static final int CLASS_ID_BASE_ZERO = 0;
    public static final int CLASS_ID_BASE_ONE = 1;

    /**
     * @param content          classes.txt 全文（UTF-8）
     * @param expectedQuantity 模型声明的商品数量（§4.4.4 quantity）
     * @param classIdBase      0 = classId 从 0 起（行下标），1 = 从 1 起（行号）
     * @throws ClassesParseRejectException 行数与 quantity 不符（含前后 3 行 diff 摘要）
     */
    public static ParsedClasses parse(String content, int expectedQuantity, int classIdBase) {
        if (content == null || content.isBlank()) {
            throw new ClassesParseRejectException("classes 文件内容为空");
        }
        if (classIdBase != CLASS_ID_BASE_ZERO && classIdBase != CLASS_ID_BASE_ONE) {
            throw new ClassesParseRejectException("classIdBase 仅允许 0 或 1：" + classIdBase);
        }
        List<ParsedClassRow> rows = new ArrayList<>();
        String[] lines = content.split("\r?\n");
        int lineNo = 0;
        for (String raw : lines) {
            String text = raw.strip();
            if (text.isEmpty()) {
                continue;
            }
            rows.add(new ParsedClassRow(lineNo + classIdBase, text, lineNo));
            lineNo++;
        }
        if (expectedQuantity > 0 && rows.size() != expectedQuantity) {
            throw new ClassesParseRejectException(
                    "classes 行数 %d ≠ 模型 quantity %d（前 3 行：%s / 末 3 行：%s）".formatted(
                            rows.size(), expectedQuantity,
                            headSummary(rows), tailSummary(rows)));
        }
        return new ParsedClasses(List.copyOf(rows), sha256Prefix(content));
    }

    private static String headSummary(List<ParsedClassRow> rows) {
        return rows.stream().limit(3).map(ParsedClassRow::textName).toList().toString();
    }

    private static String tailSummary(List<ParsedClassRow> rows) {
        int from = Math.max(0, rows.size() - 3);
        return rows.subList(from, rows.size()).stream().map(ParsedClassRow::textName).toList().toString();
    }

    static String sha256Prefix(String content) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(content.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest, 0, 6);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }

    /** 预生成拒绝（调用方转 409/400 中文提示）。 */
    public static class ClassesParseRejectException extends IllegalArgumentException {
        public ClassesParseRejectException(String message) {
            super(message);
        }
    }
}
