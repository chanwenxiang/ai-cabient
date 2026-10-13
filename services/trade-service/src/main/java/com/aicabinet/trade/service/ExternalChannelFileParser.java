package com.aicabinet.trade.service;

import com.aicabinet.trade.domain.ExternalImportTemplate;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.DateUtil;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.springframework.stereotype.Component;

import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.nio.charset.Charset;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 外部渠道对账文件解析器（CB-036，2026-10-13 六渠道结构实证定稿）。
 *
 * <p>模板驱动：文件类型（CSV_GBK / XLSX / XLS-OLE2）、sheet 策略（FIRST / ALL）、
 * 数量语义（DIRECT / BY_DIFF / SALES）、聚合键（PRODUCT_ID / NAME）、人员归属
 * （SHEET_NAME / COLUMN / FILE_NAME / MANUAL）。
 *
 * <p>🔴 容忍格式漂移（实证：大黄鹅 202606=CSV/GBK 无补货数量列 → 202609=xlsx 多 sheet 有直接列）：
 * 列按表头名定位（非列序）；语义列缺失时按数量语义降级（DIRECT 缺列 → BY_DIFF；
 * BY_DIFF 缺任一列 → 报错行）；脏行不中断整体解析，进 {@code errors} 预览展示。
 */
@Component
public class ExternalChannelFileParser {

    /** 乐摇摇补货人员列=「姓名+手机掩码」（如 李进学138****1234）——剥离数字/掩码取姓名。 */
    private static final String PHONE_MASK_CHARS = "[0-9*＊\\-—\\s]";

    private static final List<DateTimeFormatter> DATE_FORMATS = List.of(
            DateTimeFormatter.ofPattern("yyyy-M-d"),
            DateTimeFormatter.ofPattern("yyyy/M/d"),
            DateTimeFormatter.ofPattern("yyyy-M-d H:m"),
            DateTimeFormatter.ofPattern("yyyy/M/d H:m"),
            DateTimeFormatter.ofPattern("yyyy年M月d日"));

    private final ObjectMapper objectMapper;

    public ExternalChannelFileParser(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /** 解析行（内部形态：比 API DTO 多 brand/spec，供外部商品建档）。 */
    public record ParsedRow(String person, String extKey, String extName, String brand, String spec,
                            Long unitPriceCents, int qty, LocalDate happenedAt, int srcLine) {
    }

    public record ParseResult(List<ParsedRow> rows, List<String> errors) {
    }

    public ParseResult parse(byte[] content, String fileName, ExternalImportTemplate template,
                             String personOverride) {
        Map<String, String> colMap = readColumnMap(template);
        List<String> errors = new ArrayList<>();
        ParseResult result = switch (template.getFileType()) {
            case ExternalImportTemplate.FILE_CSV_GBK -> parseCsv(content, template, personOverride, colMap, errors);
            case ExternalImportTemplate.FILE_XLSX, ExternalImportTemplate.FILE_XLS ->
                    parseExcel(content, template, personOverride, colMap, errors);
            default -> throw new IllegalArgumentException("不支持的文件类型：" + template.getFileType());
        };
        if (result.rows().isEmpty() && errors.isEmpty()) {
            throw new IllegalArgumentException(
                    "未解析到任何数据行：请确认文件类型（当前模板 " + template.getFileType() + "）与表头是否匹配渠道「"
                            + template.getChannelName() + "」的结构");
        }
        return result;
    }

    // ── CSV（GB18030 超集兼容 GBK；大黄鹅 202606 实证格式）───────────────────────────────

    private ParseResult parseCsv(byte[] content, ExternalImportTemplate template, String personOverride,
                                 Map<String, String> colMap, List<String> errors) {
        String text = new String(content, Charset.forName("GB18030"));
        List<List<String>> table = parseCsvTable(text);
        return parseTable(table, template, personOverride, colMap, errors);
    }

    /** RFC4180 子集：双引号包裹可含逗号/换行，"" 转义。 */
    private List<List<String>> parseCsvTable(String text) {
        List<List<String>> table = new ArrayList<>();
        List<String> row = new ArrayList<>();
        StringBuilder cell = new StringBuilder();
        boolean inQuotes = false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (inQuotes) {
                if (c == '"') {
                    if (i + 1 < text.length() && text.charAt(i + 1) == '"') {
                        cell.append('"');
                        i++;
                    } else {
                        inQuotes = false;
                    }
                } else {
                    cell.append(c);
                }
            } else if (c == '"') {
                inQuotes = true;
            } else if (c == ',') {
                row.add(cell.toString());
                cell.setLength(0);
            } else if (c == '\r') {
                // 忽略，\n 处理换行
            } else if (c == '\n') {
                row.add(cell.toString());
                cell.setLength(0);
                table.add(row);
                row = new ArrayList<>();
            } else {
                cell.append(c);
            }
        }
        if (cell.length() > 0 || !row.isEmpty()) {
            row.add(cell.toString());
            table.add(row);
        }
        return table;
    }

    // ── Excel（POI WorkbookFactory 同时吃 XSSF 与 HSSF/OLE2——乐摇摇 .xls）──────────────────

    private ParseResult parseExcel(byte[] content, ExternalImportTemplate template, String personOverride,
                                   Map<String, String> colMap, List<String> errors) {
        try (Workbook wb = WorkbookFactory.create(new ByteArrayInputStream(content))) {
            List<List<String>> table = new ArrayList<>();
            List<Integer> sheetFirstDataRow = new ArrayList<>();
            List<String> sheetNames = new ArrayList<>();
            boolean all = ExternalImportTemplate.SHEET_ALL.equals(template.getSheetStrategy());
            for (int s = 0; s < wb.getNumberOfSheets(); s++) {
                if (!all && s > 0) {
                    break;
                }
                Sheet sheet = wb.getSheetAt(s);
                int start = appendSheetTable(sheet, table);
                sheetFirstDataRow.add(start);
                sheetNames.add(sheet.getSheetName());
            }
            return parseExcelTable(table, sheetFirstDataRow, sheetNames, template, personOverride, colMap, errors);
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalArgumentException("文件解析失败：请确认是有效的 "
                    + template.getFileType() + " 文件（" + e.getClass().getSimpleName() + "）");
        }
    }

    /** 把一个 sheet 追加进 table，返回该 sheet 首个数据行在 table 中的下标（表头行定位用）。 */
    private int appendSheetTable(Sheet sheet, List<List<String>> table) {
        int start = table.size();
        for (Row row : sheet) {
            List<String> cells = new ArrayList<>();
            short last = row.getLastCellNum();
            for (int c = 0; c < last; c++) {
                cells.add(cellText(row.getCell(c)));
            }
            table.add(cells);
        }
        return start;
    }

    // ── 表格 → 语义行（CSV 与 Excel 共用；sheet 名人员归属只在 Excel 传入）─────────────────

    private ParseResult parseTable(List<List<String>> table, ExternalImportTemplate template,
                                   String personOverride, Map<String, String> colMap, List<String> errors) {
        return parseExcelTable(table, List.of(0), List.of("CSV"), template, personOverride, colMap, errors);
    }

    private ParseResult parseExcelTable(List<List<String>> table, List<Integer> sheetStarts,
                                        List<String> sheetNames, ExternalImportTemplate template,
                                        String personOverride, Map<String, String> colMap,
                                        List<String> errors) {
        List<ParsedRow> rows = new ArrayList<>();
        // 每 sheet 独立定位表头（ALL 策略下每线长 sheet 结构相同但列序可能不同）
        for (int s = 0; s < sheetStarts.size(); s++) {
            int start = sheetStarts.get(s);
            int end = s + 1 < sheetStarts.size() ? sheetStarts.get(s + 1) : table.size();
            String sheetPerson = ExternalImportTemplate.PERSON_SHEET_NAME.equals(template.getPersonSource())
                    ? sheetNames.get(s) : null;
            parseSheetSegment(table, start, end, sheetPerson, template, personOverride, colMap, errors, rows);
        }
        return new ParseResult(rows, errors);
    }

    private void parseSheetSegment(List<List<String>> table, int start, int end, String sheetPerson,
                                   ExternalImportTemplate template, String personOverride,
                                   Map<String, String> colMap, List<String> errors, List<ParsedRow> out) {
        Set<String> wantedHeaders = colMap.keySet();
        int headerIdx = -1;
        Map<String, Integer> headerCols = Map.of();
        for (int r = start; r < Math.min(end, start + 30); r++) {
            Map<String, Integer> found = matchHeaders(table.get(r), colMap);
            if (found.size() >= Math.min(2, wantedHeaders.size())) {
                headerIdx = r;
                headerCols = found;
                break;
            }
        }
        if (headerIdx < 0) {
            if (segmentHasData(table, start, end)) {
                errors.add("sheet「" + (sheetPerson == null ? "数据" : sheetPerson) + "」未识别到表头行（需含列头："
                        + String.join("、", colMap.values()) + "），已跳过");
            }
            return;
        }
        for (int r = headerIdx + 1; r < end; r++) {
            List<String> cells = table.get(r);
            if (cells.stream().allMatch(v -> v == null || v.isBlank())) {
                continue;
            }
            String extName = value(cells, headerCols, "extName");
            String extId = value(cells, headerCols, "extId");
            if ((extName == null || extName.isBlank()) && (extId == null || extId.isBlank())) {
                continue; // 无商品信息行（合计行/空行）
            }
            try {
                out.add(toRow(cells, headerCols, extName, extId, r + 1, sheetPerson, template, personOverride));
            } catch (IllegalArgumentException e) {
                errors.add("第 " + (r + 1) + " 行：" + e.getMessage());
            }
        }
    }

    private ParsedRow toRow(List<String> cells, Map<String, Integer> headerCols, String extName, String extId,
                            int srcLine, String sheetPerson, ExternalImportTemplate template,
                            String personOverride) {
        String name = extName == null ? "" : extName.trim();
        String key;
        if (ExternalImportTemplate.KEY_PRODUCT_ID.equals(template.getKeyField())) {
            key = extId == null || extId.isBlank() ? name : extId.trim();
            if (key.isBlank()) {
                throw new IllegalArgumentException("商品ID与商品名称均为空");
            }
        } else {
            key = name;
            if (key.isBlank()) {
                throw new IllegalArgumentException("商品名称为空");
            }
        }
        int qty = resolveQty(cells, headerCols, template, srcLine);
        String person = resolvePerson(cells, headerCols, sheetPerson, template, personOverride);
        return new ParsedRow(person, key, name,
                trimToNull(value(cells, headerCols, "brand")),
                trimToNull(value(cells, headerCols, "spec")),
                parsePriceCents(value(cells, headerCols, "unitPrice")),
                qty,
                parseDate(value(cells, headerCols, "happenedAt")),
                srcLine);
    }

    private int resolveQty(List<String> cells, Map<String, Integer> headerCols,
                           ExternalImportTemplate template, int srcLine) {
        switch (template.getQtySemantics()) {
            case ExternalImportTemplate.QTY_BY_DIFF -> {
                Long after = parseLong(value(cells, headerCols, "qtyAfter"));
                Long before = parseLong(value(cells, headerCols, "qtyBefore"));
                if (after == null || before == null) {
                    throw new IllegalArgumentException("补货后/补货前库存缺失，无法按「后-前」折算数量");
                }
                int qty = (int) (after - before);
                if (qty < 0) {
                    throw new IllegalArgumentException("补货后(" + after + ")小于补货前(" + before + ")，数量为负");
                }
                return qty;
            }
            case ExternalImportTemplate.QTY_SALES -> {
                Long qty = parseLong(value(cells, headerCols, "qty"));
                if (qty == null) {
                    throw new IllegalArgumentException("销量列为空");
                }
                return (int) Math.abs(qty);
            }
            default -> {
                String raw = value(cells, headerCols, "qty");
                if (raw == null || raw.isBlank()) {
                    // 直接列缺失 → BY_DIFF 降级（大黄鹅 202606 漂移实证）
                    Long after = parseLong(value(cells, headerCols, "qtyAfter"));
                    Long before = parseLong(value(cells, headerCols, "qtyBefore"));
                    if (after != null && before != null) {
                        int qty = (int) (after - before);
                        if (qty < 0) {
                            throw new IllegalArgumentException("补货后(" + after + ")小于补货前(" + before + ")，数量为负");
                        }
                        return qty;
                    }
                    throw new IllegalArgumentException("数量列为空");
                }
                Long qty = parseLong(raw);
                if (qty == null) {
                    throw new IllegalArgumentException("数量「" + raw + "」不是有效整数");
                }
                if (qty < 0) {
                    throw new IllegalArgumentException("数量为负（" + qty + "）");
                }
                return qty.intValue();
            }
        }
    }

    private String resolvePerson(List<String> cells, Map<String, Integer> headerCols, String sheetPerson,
                                 ExternalImportTemplate template, String personOverride) {
        return switch (template.getPersonSource()) {
            case ExternalImportTemplate.PERSON_SHEET_NAME -> normalizePerson(sheetPerson);
            case ExternalImportTemplate.PERSON_COLUMN -> normalizePerson(value(cells, headerCols, "person"));
            case ExternalImportTemplate.PERSON_FILE_NAME -> null; // 预留：文件名归属暂无实证渠道
            default -> normalizePerson(personOverride); // MANUAL
        };
    }

    /** 姓名 + 手机掩码 → 姓名前缀；纯姓名原样。 */
    static String normalizePerson(String raw) {
        if (raw == null) {
            return null;
        }
        String name = raw.replaceAll(PHONE_MASK_CHARS, "").trim();
        return name.isBlank() ? null : name;
    }

    // ── 单元格取值与类型转换 ────────────────────────────────────────────────────────────────

    private String cellText(Cell cell) {
        if (cell == null) {
            return "";
        }
        if (cell.getCellType() == CellType.NUMERIC) {
            if (DateUtil.isCellDateFormatted(cell)) {
                return cell.getLocalDateTimeCellValue().toString();
            }
            double v = cell.getNumericCellValue();
            if (v == Math.floor(v)) {
                return String.valueOf((long) v);
            }
            return BigDecimal.valueOf(v).stripTrailingZeros().toPlainString();
        }
        if (cell.getCellType() == CellType.FORMULA) {
            try {
                return switch (cell.getCachedFormulaResultType()) {
                    case NUMERIC -> String.valueOf((long) cell.getNumericCellValue());
                    case STRING -> cell.getStringCellValue();
                    default -> "";
                };
            } catch (Exception e) {
                return "";
            }
        }
        return cell.getCellType() == CellType.STRING ? cell.getStringCellValue() : "";
    }

    private Map<String, Integer> matchHeaders(List<String> headerRow, Map<String, String> colMap) {
        Map<String, Integer> found = new LinkedHashMap<>();
        for (int c = 0; c < headerRow.size(); c++) {
            String header = headerRow.get(c);
            if (header == null) {
                continue;
            }
            String normalized = header.trim();
            for (Map.Entry<String, String> e : colMap.entrySet()) {
                if (normalized.equals(e.getValue()) && !found.containsKey(e.getKey())) {
                    found.put(e.getKey(), c);
                }
            }
        }
        return found;
    }

    private String value(List<String> cells, Map<String, Integer> headerCols, String field) {
        Integer idx = headerCols.get(field);
        if (idx == null || idx >= cells.size()) {
            return null;
        }
        String v = cells.get(idx);
        return v == null || v.isBlank() ? null : v.trim();
    }

    private Long parseLong(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return new BigDecimal(raw.trim()).setScale(0, java.math.RoundingMode.HALF_UP).longValueExact();
        } catch (Exception e) {
            return null;
        }
    }

    private Long parsePriceCents(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String numeric = raw.replaceAll("[^0-9.]", "");
        if (numeric.isBlank()) {
            return null;
        }
        try {
            return new BigDecimal(numeric).multiply(BigDecimal.valueOf(100))
                    .setScale(0, java.math.RoundingMode.HALF_UP).longValueExact();
        } catch (Exception e) {
            return null;
        }
    }

    private LocalDate parseDate(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String v = raw.trim();
        try {
            if (v.length() >= 10 && v.charAt(4) == '-' && v.charAt(7) == '-') {
                return LocalDate.parse(v.substring(0, 10));
            }
            if (v.contains("T")) {
                return LocalDateTime.parse(v).toLocalDate();
            }
            for (DateTimeFormatter f : DATE_FORMATS) {
                try {
                    return LocalDate.parse(v, f);
                } catch (Exception ignored) {
                    // 尝试下一格式
                }
            }
        } catch (Exception ignored) {
            // 日期列脏值不致命
        }
        return null;
    }

    private String trimToNull(String v) {
        return v == null || v.isBlank() ? null : v.trim();
    }

    private boolean segmentHasData(List<List<String>> table, int start, int end) {
        for (int r = start; r < Math.min(end, table.size()); r++) {
            for (String cell : table.get(r)) {
                if (cell != null && !cell.isBlank()) {
                    return true;
                }
            }
        }
        return false;
    }

    private Map<String, String> readColumnMap(ExternalImportTemplate template) {
        try {
            Map<String, String> map = objectMapper.readValue(template.getColumnMap(),
                    objectMapper.getTypeFactory().constructMapType(LinkedHashMap.class, String.class, String.class));
            if (map == null || map.isEmpty()) {
                throw new IllegalStateException("空 columnMap");
            }
            return map;
        } catch (Exception e) {
            throw new IllegalStateException("渠道模板列映射解析失败：" + template.getChannelCode());
        }
    }

    /** Excel 日期序列兜底（纯数字日期列）：给 parseDate 之外的路径留扩展点，当前未启用。 */
    static LocalDate fromExcelSerial(double serial) {
        return Instant.ofEpochMilli(Math.round((serial - 25569) * 86400d * 1000d))
                .atZone(ZoneId.of("Asia/Shanghai")).toLocalDate();
    }
}
