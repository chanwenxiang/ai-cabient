package com.aicabinet.trade.support;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * 面向用户/API 的中文错误文案。服务层优先直接使用常量；遗留英文消息经 {@link #translate(String)} 转换。
 */
public final class ApiMessages {

    private ApiMessages() {}

    // 认证
    public static final String MISSING_TOKEN = "请先登录";
    public static final String INVALID_TOKEN = "登录已失效，请重新登录";
    public static final String INVALID_PHONE = "手机号格式不正确";
    public static final String INVALID_CODE = "验证码错误或已过期";
    public static final String INVALID_PASSWORD = "密码错误";
    public static final String PASSWORD_NOT_SET = "该账号未设置密码，请使用验证码或联系管理员";
    public static final String WX_NOT_BOUND = "微信未绑定账号，请先使用手机号登录后再绑定";
    public static final String USER_NOT_VERIFIED = "请先完成实名认证";
    public static final String OPERATOR_REQUIRED = "需要运营账号权限";
    public static final String PERMISSION_DENIED = "无权限执行此操作";
    public static final String CONSUMER_CANNOT_USE_ADMIN = "当前账号为消费者，请使用运营账号登录后台";
    public static final String ACCESS_DENIED = "无权访问该资源";

    // 用户/账户
    public static final String USER_NOT_FOUND = "用户不存在";
    public static final String ACCOUNT_NOT_FOUND = "账户不存在";
    public static final String BALANCE_TOO_LOW = "余额不足，请先充值（至少保留 5 元）";
    public static final String BALANCE_NEGATIVE = "余额不能为负数";
    public static final String BALANCE_TOO_LARGE = "余额超出允许范围";
    public static final String CANNOT_ADJUST_OPERATOR_BALANCE = "不能调整运营账号余额";

    // 设备/会话
    public static final String DEVICE_NOT_FOUND = "设备不存在，请检查设备编号";
    public static final String DEVICE_BUSY = "设备使用中，请稍后再试";
    public static final String DEVICE_OFFLINE = "设备离线，暂不可开门";
    public static final String REPLENISHMENT_IN_PROGRESS = "设备补货中，暂不能购物或结算";
    public static final String REPLENISHMENT_TASK_NOT_FOUND = "补货任务不存在";
    public static final String REPLENISHMENT_TASK_MISMATCH = "补货任务与设备不匹配";
    public static final String REPLENISHMENT_TASK_FINISHED = "补货任务已结束";
    public static final String REPLENISHMENT_TASK_ASSIGNEE = "仅任务负责人可执行此补货操作";
    public static final String REPLENISHMENT_CHECK_IN_REQUIRED = "请先到店签到后再补货开门";
    public static final String DEVICE_MISMATCH = "设备与会话不匹配";
    public static final String SESSION_NOT_FOUND = "购物会话不存在";
    public static final String SESSION_FINISHED = "会话已结束，无法取消";
    public static final String SESSION_STATE_INVALID = "当前会话状态不允许此操作";

    // 风控
    public static final String USER_BLACKLISTED = "账号受限，请联系客服";
    public static final String TOO_MANY_OPENS = "开门过于频繁，请稍后再试";

    // 订单/支付/争议
    public static final String ORDER_NOT_FOUND = "订单不存在";
    public static final String ORDER_ACCESS_DENIED = "无权查看该订单";
    public static final String ORDER_NOT_PENDING = "订单状态不是待支付";
    public static final String ORDER_ALREADY_PAID = "订单已支付";
    public static final String ORDER_NOT_PAID = "订单未支付，无法退款";
    public static final String INSUFFICIENT_BALANCE = "余额不足";
    public static final String INSUFFICIENT_REFUND = "余额不足，无法退款";
    public static final String WECHAT_PAY_NOT_CONFIGURED = "微信支付未配置";
    public static final String ALIPAY_PAY_NOT_CONFIGURED = "支付宝未配置";
    public static final String WX_OPENID_NOT_BOUND = "请先绑定微信后再支付";
    public static final String TICKET_NOT_FOUND = "争议工单不存在";
    public static final String TICKET_ALREADY_RESOLVED = "工单已处理";
    public static final String DISPUTE_ALREADY_EXISTS = "该会话已有申诉工单";
    public static final String DISPUTE_ITEMS_REQUIRED = "请至少选择一件商品";
    public static final String ORDER_ALREADY_REFUNDED = "订单已退款";

    // 商品/运营
    public static final String SKU_NOT_FOUND = "商品不存在";
    public static final String SKU_EXISTS = "商品编号已存在";
    public static final String DEVICE_EXISTS = "设备编号已存在";
    public static final String ROLE_NOT_FOUND = "角色不存在";
    public static final String NOT_OPERATOR_ACCOUNT = "不是运营账号";
    public static final String CANNOT_MODIFY_ADMIN_ROLE = "不能修改管理员角色权限";
    public static final String DEVICE_IDS_REQUIRED = "请指定设备列表";
    public static final String BIND_OPENID_DISABLED = "生产环境不允许绑定微信 OpenID";
    public static final String WECHAT_MINIAPP_NOT_CONFIGURED = "微信小程序未配置";
    public static final String RECONCILIATION_NOT_FOUND = "对账记录不存在";
    public static final String MISSING_OUT_TRADE_NO = "缺少商户订单号";
    public static final String UNSUPPORTED_CHANNEL = "不支持的支付渠道";
    public static final String INVALID_WECHAT_NOTIFY = "微信支付回调签名校验失败";
    public static final String INVALID_ALIPAY_NOTIFY = "支付宝回调签名校验失败";

    // 通用
    public static final String INTERNAL_ERROR = "系统繁忙，请稍后重试";
    public static final String INVALID_REQUEST = "请求参数无效";

    private static final Pattern BALANCE_MIN_PATTERN = Pattern.compile(
            "balance below minimum (\\d+) cents", Pattern.CASE_INSENSITIVE);
    private static final Pattern BLACKLIST_PATTERN = Pattern.compile(
            "user blacklisted:\\s*(.+)", Pattern.CASE_INSENSITIVE);
    private static final Pattern TRANSITION_PATTERN = Pattern.compile(
            "cannot transition from (\\w+) to (\\w+)", Pattern.CASE_INSENSITIVE);

    /** 将 Bean Validation 字段错误格式化为用户可读中文（避免 phoneNumber: 不能为空）。 */
    public static String formatValidationFieldError(String field, String defaultMessage) {
        String label = validationFieldLabel(field);
        if (defaultMessage != null) {
            String trimmed = defaultMessage.trim();
            if (containsCjk(trimmed) && !isGenericValidationMessage(trimmed)
                    && (field == null || !trimmed.contains(field))) {
                return trimmed;
            }
        }
        if (defaultMessage == null || defaultMessage.isBlank() || isGenericValidationMessage(defaultMessage.trim())) {
            return label + "不能为空";
        }
        String trimmed = defaultMessage.trim();
        if (trimmed.startsWith(label)) {
            return trimmed;
        }
        if (containsCjk(trimmed)) {
            return label + trimmed;
        }
        return label + "不能为空";
    }

    private static boolean isGenericValidationMessage(String message) {
        if (message == null || message.isBlank()) {
            return true;
        }
        String msg = message.trim();
        return "不能为空".equals(msg)
                || "不能为null".equalsIgnoreCase(msg)
                || "must not be blank".equalsIgnoreCase(msg)
                || "must not be empty".equalsIgnoreCase(msg);
    }

    private static String validationFieldLabel(String field) {
        if (field == null || field.isBlank()) {
            return "参数";
        }
        return switch (field) {
            case "phoneNumber" -> "手机号";
            case "password" -> "密码";
            case "code" -> "验证码";
            case "deviceId" -> "设备编号";
            case "sessionId" -> "会话编号";
            case "skuId" -> "商品编号";
            case "reason" -> "原因";
            default -> field;
        };
    }

    /** 将英文或混合消息转为用户可读中文；已是中文则原样返回。 */
    public static String translate(String raw) {
        if (raw == null || raw.isBlank()) {
            return INTERNAL_ERROR;
        }
        if (containsCjk(raw)) {
            return raw.trim();
        }
        String msg = raw.trim();
        String lower = msg.toLowerCase(Locale.ROOT);

        return switch (lower) {
            case "missing token" -> MISSING_TOKEN;
            case "invalid token" -> INVALID_TOKEN;
            case "invalid phone" -> INVALID_PHONE;
            case "invalid code" -> INVALID_CODE;
            case "invalid userid" -> INVALID_REQUEST;
            case "user not found" -> USER_NOT_FOUND;
            case "user not verified" -> USER_NOT_VERIFIED;
            case "account not found" -> ACCOUNT_NOT_FOUND;
            case "access denied" -> ACCESS_DENIED;
            case "operator permission required" -> OPERATOR_REQUIRED;
            case "permission denied" -> PERMISSION_DENIED;
            case "device not found" -> DEVICE_NOT_FOUND;
            case "device has active session" -> DEVICE_BUSY;
            case "device mismatch" -> DEVICE_MISMATCH;
            case "session not found" -> SESSION_NOT_FOUND;
            case "session already finished" -> SESSION_FINISHED;
            case "order not found" -> ORDER_NOT_FOUND;
            case "order access denied" -> ORDER_ACCESS_DENIED;
            case "order not pending" -> ORDER_NOT_PENDING;
            case "order already paid" -> ORDER_ALREADY_PAID;
            case "order not paid" -> ORDER_NOT_PAID;
            case "insufficient balance" -> INSUFFICIENT_BALANCE;
            case "insufficient balance for refund" -> INSUFFICIENT_REFUND;
            case "insufficient balance." -> INSUFFICIENT_BALANCE;
            case "wechat pay not configured" -> WECHAT_PAY_NOT_CONFIGURED;
            case "wx openid not bound" -> WX_OPENID_NOT_BOUND;
            case "ticket not found" -> TICKET_NOT_FOUND;
            case "ticket already resolved" -> TICKET_ALREADY_RESOLVED;
            case "sku not found" -> SKU_NOT_FOUND;
            case "sku already exists" -> SKU_EXISTS;
            case "device already exists" -> DEVICE_EXISTS;
            case "role not found" -> ROLE_NOT_FOUND;
            case "not an operator account" -> NOT_OPERATOR_ACCOUNT;
            case "cannot modify admin role permissions" -> CANNOT_MODIFY_ADMIN_ROLE;
            case "deviceids required" -> DEVICE_IDS_REQUIRED;
            case "bind-openid disabled in production" -> BIND_OPENID_DISABLED;
            case "wechat miniapp not configured" -> WECHAT_MINIAPP_NOT_CONFIGURED;
            case "reconciliation not found" -> RECONCILIATION_NOT_FOUND;
            case "missing out_trade_no" -> MISSING_OUT_TRADE_NO;
            case "balance cannot be negative" -> BALANCE_NEGATIVE;
            case "balance too large" -> BALANCE_TOO_LARGE;
            case "cannot adjust operator balance" -> CANNOT_ADJUST_OPERATOR_BALANCE;
            case "internal error" -> INTERNAL_ERROR;
            case "too many door open attempts, please try later" -> TOO_MANY_OPENS;
            default -> translatePattern(msg, lower);
        };
    }

    private static String translatePattern(String msg, String lower) {
        if (lower.startsWith("unsupported channel:")) {
            return UNSUPPORTED_CHANNEL;
        }
        if (lower.startsWith("invalid wechat notify signature")) {
            return INVALID_WECHAT_NOTIFY;
        }
        if (lower.startsWith("invalid permissionid:")) {
            return INVALID_REQUEST;
        }
        if (lower.startsWith("invalid roleid:")) {
            return INVALID_REQUEST;
        }
        if (lower.startsWith("sku not found:")) {
            return SKU_NOT_FOUND;
        }
        if (lower.startsWith("unknown device:")) {
            return DEVICE_NOT_FOUND;
        }
        if (lower.startsWith("permission denied:")) {
            return PERMISSION_DENIED;
        }
        var blacklist = BLACKLIST_PATTERN.matcher(msg);
        if (blacklist.find()) {
            String reason = blacklist.group(1).trim();
            return USER_BLACKLISTED + (reason.isEmpty() ? "" : "：" + reason);
        }
        var balanceMin = BALANCE_MIN_PATTERN.matcher(msg);
        if (balanceMin.find()) {
            return BALANCE_TOO_LOW;
        }
        var transition = TRANSITION_PATTERN.matcher(msg);
        if (transition.find()) {
            return SESSION_STATE_INVALID;
        }
        if (lower.contains("balance") && lower.contains("insufficient")) {
            return INSUFFICIENT_BALANCE;
        }
        if (lower.contains("unauthorized") || lower.contains("forbidden")) {
            return ACCESS_DENIED;
        }
        if (lower.contains("not found")) {
            return "资源不存在";
        }
        return msg;
    }

    private static boolean containsCjk(String s) {
        for (int i = 0; i < s.length(); i++) {
            if (Character.UnicodeScript.of(s.charAt(i)) == Character.UnicodeScript.HAN) {
                return true;
            }
        }
        return false;
    }
}
