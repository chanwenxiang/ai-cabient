package com.aicabinet.trade.reconciliation;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayInputStream;
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
                if (name.contains("..") || name.startsWith("/") || entry.isDirectory()) {
                    continue;
                }
                if (!name.toLowerCase(Locale.ROOT).endsWith(".csv")) {
                    continue;
                }
                byte[] csvBytes = readZipEntryBounded(zis, MAX_ENTRY_UNCOMPRESSED_BYTES);
                all.addAll(parseCsv(new String(csvBytes, StandardCharsets.UTF_8), billDate));
            }
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("failed to unzip alipay bill", e);
        }
        return all;
    }

    private static byte[] readZipEntryBounded(ZipInputStream zis, long maxBytes) throws Exception {
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
        List<PlatformBillLine> result = new ArrayList<>();
        ZoneId zone = ZoneId.systemDefault();
        int headerIdx = -1;
        int platformCol = -1;
        int merchantCol = -1;
        int amountCol = -1;
        int finishCol = -1;

        for (int i = 0; i < lines.length; i++) {
            String line = lines[i].trim();
            if (line.startsWith("#") || line.isEmpty()) {
                continue;
            }
            if (line.contains("商户订单号") || line.contains("支付宝交易号")) {
                String[] headers = line.split(",");
                for (int c = 0; c < headers.length; c++) {
                    String h = headers[c].trim();
                    if (h.contains("支付宝交易号")) platformCol = c;
                    else if (h.contains("商户订单号")) merchantCol = c;
                    else if (h.contains("商家实收")) amountCol = c;
                    else if (h.contains("订单金额") && amountCol < 0) amountCol = c;
                    else if (h.contains("完成时间")) finishCol = c;
                }
                headerIdx = i;
                break;
            }
        }
        if (headerIdx < 0 || merchantCol < 0) {
            return result;
        }

        for (int i = headerIdx + 1; i < lines.length; i++) {
            String line = lines[i].trim();
            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            }
            String[] cols = line.split(",", -1);
            if (cols.length <= merchantCol) {
                continue;
            }
            try {
                String merchantOrderNo = cols[merchantCol].trim();
                if (merchantOrderNo.isEmpty()) {
                    continue;
                }
                String platformTradeNo = platformCol >= 0 && cols.length > platformCol
                        ? cols[platformCol].trim()
                        : merchantOrderNo;
                long amountCents = parseYuan(amountCol >= 0 && cols.length > amountCol
                        ? cols[amountCol].trim()
                        : "0");
                Instant tradeTime = billDate.atStartOfDay(zone).toInstant();
                if (finishCol >= 0 && cols.length > finishCol && !cols[finishCol].isBlank()) {
                    tradeTime = LocalDateTime.parse(cols[finishCol].trim(), FINISH_TIME)
                            .atZone(zone).toInstant();
                }
                result.add(new PlatformBillLine(
                        platformTradeNo, merchantOrderNo, amountCents, tradeTime, "ALIPAY", line
                ));
            } catch (Exception e) {
                log.debug("skip alipay bill line: {}", line);
            }
        }
        return result;
    }

    private static long parseYuan(String yuan) {
        if (yuan == null || yuan.isBlank()) {
            return 0L;
        }
        return Math.round(Double.parseDouble(yuan.trim()) * 100);
    }
}
