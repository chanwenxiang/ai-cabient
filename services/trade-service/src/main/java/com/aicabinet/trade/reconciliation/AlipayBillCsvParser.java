package com.aicabinet.trade.reconciliation;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

public final class AlipayBillCsvParser {

    private static final Logger log = LoggerFactory.getLogger(AlipayBillCsvParser.class);
    private static final DateTimeFormatter FINISH_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    /** 支付宝对账 zip：限制条目数与单文件体积，防止 zip bomb / 路径穿越 */
    private static final int MAX_ZIP_ENTRIES = 32;
    private static final long MAX_ENTRY_UNCOMPRESSED_BYTES = 32L * 1024 * 1024;

    private AlipayBillCsvParser() {}

    public static List<PlatformBillLine> parseDownloadedBytes(byte[] data, LocalDate billDate) {
        if (data == null || data.length == 0) {
            return List.of();
        }
        if (data.length >= 2 && data[0] == 'P' && data[1] == 'K') {
            return parseZip(data, billDate);
        }
        return parseCsv(new String(data, StandardCharsets.UTF_8), billDate);
    }

    static List<PlatformBillLine> parseZip(byte[] zipBytes, LocalDate billDate) {
        List<PlatformBillLine> all = new ArrayList<>();
        try (ZipInputStream zis = new ZipInputStream(new ByteArrayInputStream(zipBytes))) {
            ZipEntry entry;
            int entryCount = 0;
            while ((entry = zis.getNextEntry()) != null) {
                entryCount++;
                if (entryCount > MAX_ZIP_ENTRIES) {
                    throw new IllegalStateException("alipay bill zip has too many entries");
                }
                String name = entry.getName() == null ? "" : entry.getName().replace('\\', '/');
                if (!name.contains("..") && !name.startsWith("/") && !entry.isDirectory()
                        && name.toLowerCase(Locale.ROOT).endsWith(".csv")) {
                    byte[] csvBytes = readZipEntryBounded(zis, MAX_ENTRY_UNCOMPRESSED_BYTES);
                    all.addAll(parseCsv(new String(csvBytes, StandardCharsets.UTF_8), billDate));
                }
            }
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("failed to unzip alipay bill", e);
        }
        return all;
    }

    private static byte[] readZipEntryBounded(ZipInputStream zis, long maxBytes) throws IOException {
        byte[] buf = new byte[8192];
        int read;
        long total = 0;
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        while ((read = zis.read(buf)) >= 0) {
            total += read;
            if (total > maxBytes) {
                throw new IllegalStateException("alipay bill zip entry exceeds size limit");
            }
            out.write(buf, 0, read);
        }
        return out.toByteArray();
    }

    public static List<PlatformBillLine> parseCsv(String csv, LocalDate billDate) {
        String[] lines = csv.replace("\uFEFF", "").split("\n");
        CsvHeader header = locateHeader(lines);
        if (header == null) {
            return new ArrayList<>();
        }
        ZoneId zone = ZONE;
        List<PlatformBillLine> result = new ArrayList<>();
        for (int i = header.headerIdx() + 1; i < lines.length; i++) {
            String line = lines[i].trim();
            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            }
            PlatformBillLine parsed = parseDataLine(line, header, billDate, zone);
            if (parsed != null) {
                result.add(parsed);
            }
        }
        return result;
    }

    private static CsvHeader locateHeader(String[] lines) {
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i].trim();
            if (!line.startsWith("#") && !line.isEmpty()
                    && (line.contains("商户订单号") || line.contains("支付宝交易号"))) {
                CsvHeader header = parseHeaderColumns(i, line);
                if (header != null) {
                    return header;
                }
            }
        }
        return null;
    }

    private static CsvHeader parseHeaderColumns(int headerIdx, String line) {
        int platformCol = -1;
        int merchantCol = -1;
        int amountCol = -1;
        int finishCol = -1;
        String[] headers = line.split(",");
        for (int c = 0; c < headers.length; c++) {
            String h = headers[c].trim();
            if (h.contains("支付宝交易号")) {
                platformCol = c;
            } else if (h.contains("商户订单号")) {
                merchantCol = c;
            } else if (h.contains("商家实收")) {
                amountCol = c;
            } else if (h.contains("订单金额") && amountCol < 0) {
                amountCol = c;
            } else if (h.contains("完成时间")) {
                finishCol = c;
            }
        }
        if (merchantCol < 0) {
            return null;
        }
        return new CsvHeader(headerIdx, platformCol, merchantCol, amountCol, finishCol);
    }

    private static PlatformBillLine parseDataLine(String line, CsvHeader header, LocalDate billDate, ZoneId zone) {
        String[] cols = line.split(",", -1);
        if (cols.length <= header.merchantCol()) {
            return null;
        }
        try {
            String merchantOrderNo = cols[header.merchantCol()].trim();
            if (merchantOrderNo.isEmpty()) {
                return null;
            }
            String platformTradeNo = header.platformCol() >= 0 && cols.length > header.platformCol()
                    ? cols[header.platformCol()].trim()
                    : merchantOrderNo;
            long amountCents = parseYuan(header.amountCol() >= 0 && cols.length > header.amountCol()
                    ? cols[header.amountCol()].trim()
                    : "0");
            Instant tradeTime = resolveTradeTime(cols, header.finishCol(), billDate, zone);
            return new PlatformBillLine(
                    platformTradeNo, merchantOrderNo, amountCents, tradeTime, "ALIPAY", line
            );
        } catch (Exception e) {
            log.debug("skip alipay bill line: {}", line);
            return null;
        }
    }

    private static Instant resolveTradeTime(String[] cols, int finishCol, LocalDate billDate, ZoneId zone) {
        Instant tradeTime = billDate.atStartOfDay(zone).toInstant();
        if (finishCol >= 0 && cols.length > finishCol && !cols[finishCol].isBlank()) {
            return LocalDateTime.parse(cols[finishCol].trim(), FINISH_TIME)
                    .atZone(zone).toInstant();
        }
        return tradeTime;
    }

    private record CsvHeader(int headerIdx, int platformCol, int merchantCol, int amountCol, int finishCol) {}

    private static long parseYuan(String yuan) {
        if (yuan == null || yuan.isBlank()) {
            return 0L;
        }
        return new BigDecimal(yuan.trim())
                .movePointRight(2)
                .setScale(0, RoundingMode.HALF_UP)
                .longValueExact();
    }
}
