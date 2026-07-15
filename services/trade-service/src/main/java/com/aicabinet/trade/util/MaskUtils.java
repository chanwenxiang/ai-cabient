package com.aicabinet.trade.util;

import org.apache.commons.lang3.StringUtils;

/**
 * 鏁版嵁鑴辨晱宸ュ叿绫? */
public class MaskUtils {

    /**
     * 鎵嬫満鍙疯劚鏁?     * 13812345678 -> 138****5678
     */
    public static String maskPhone(String phone) {
        if (StringUtils.isBlank(phone) || phone.length() < 7) {
            return phone;
        }
        return phone.substring(0, 3) + "****" + phone.substring(phone.length() - 4);
    }

    /**
     * 韬唤璇佸彿鑴辨晱
     * 310101199001011234 -> 310***********1234
     */
    public static String maskIdCard(String idCard) {
        if (StringUtils.isBlank(idCard) || idCard.length() < 8) {
            return idCard;
        }
        int length = idCard.length();
        return idCard.substring(0, 3) + "***********" + idCard.substring(length - 4);
    }

    /**
     * 閾惰鍗″彿鑴辨晱
     * 6222021234567890123 -> 6222************123
     */
    public static String maskBankCard(String bankCard) {
        if (StringUtils.isBlank(bankCard) || bankCard.length() < 8) {
            return bankCard;
        }
        return bankCard.substring(0, 4) + "************" + bankCard.substring(bankCard.length() - 3);
    }

    /**
     * 濮撳悕鑴辨晱
     */
    public static String maskName(String name) {
        if (StringUtils.isBlank(name)) {
            return name;
        }
        if (name.length() == 1) {
            return name;
        }
        return name.charAt(0) + "*".repeat(name.length() - 1);
    }

    /**
     * 閭鑴辨晱
     */
    public static String maskEmail(String email) {
        if (StringUtils.isBlank(email) || !email.contains("@")) {
            return email;
        }
        int atIndex = email.indexOf("@");
        if (atIndex <= 1) {
            return email;
        }
        return email.charAt(0) + "****" + email.substring(atIndex);
    }

    /**
     * 鑷畾涔夎劚鏁?     */
    public static String mask(String value, int prefixLength, int suffixLength) {
        if (StringUtils.isBlank(value)) {
            return value;
        }
        if (value.length() <= prefixLength + suffixLength) {
            return value;
        }
        String prefix = value.substring(0, prefixLength);
        String suffix = value.substring(value.length() - suffixLength);
        int maskLength = value.length() - prefixLength - suffixLength;
        return prefix + "*".repeat(maskLength) + suffix;
    }
}
