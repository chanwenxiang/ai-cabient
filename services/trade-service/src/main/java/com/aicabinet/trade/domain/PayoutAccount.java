package com.aicabinet.trade.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

/**
 * 提现收款账户（对公/对私）。
 *
 * <p><b>模型要点</b>：一个主体（商户/线长/平台）可挂多个收款账户，对公与对私并存，
 * 各类型至多一个默认账户（DB 部分唯一索引 {@code uk_payout_account_default} 保证）。
 *
 * <p>🔴 <b>敏感字段纪律</b>：
 * <ul>
 *   <li>{@code accountNo} = AES-256-GCM <b>密文</b>，只在真正发起打款时由
 *       {@code PayoutFieldCipher#decrypt} 解开；</li>
 *   <li>{@code accountNoMask} = 掩码，列表/详情/快照<b>只用它</b>；</li>
 *   <li>本实体<b>不得</b>被打进日志（{@code toString} 未覆写即含全字段 ⇒ 禁止 Lombok
 *       {@code @Data}，此处只用 {@code @Getter/@Setter}）。</li>
 * </ul>
 */
@TableName("payout_account")
@Getter
@Setter
public class PayoutAccount {

    @TableId(type = IdType.AUTO)
    private Long accountId;

    /** 主体类型：MERCHANT / LINE_MANAGER / PLATFORM */
    private String ownerType;
    private String ownerId;

    /** PAYEE_TYPE_COMPANY（对公）/ PAYEE_TYPE_PERSONAL（对私） */
    private String accountType;

    /** WECHAT / ALIPAY / BANK */
    private String channel;

    /** 户名（对公=公司全称，对私=实名） */
    private String accountName;

    /** 账号密文（**禁止落日志、禁止直接返回给前端**） */
    private String accountNo;

    /** 账号掩码（明文派生，不可逆） */
    private String accountNoMask;

    private String bankName;
    private String bankBranch;
    /**
     * 联行号（CNAPS，通常 12 位数字）—— V309 新增。
     * <p>银行代付只有「户名 + 账号 + 开户行」三要素时，部分银行<b>无法自动路由</b>，
     * 打款被退回且失败原因常只写「收款行不匹配」—— 补这个字段是为了让出款能一次成功。
     */
    private String bankCode;
    /**
     * 开户行省市（如「广东省深圳市」）—— V309 新增。
     * <p>部分渠道大额代付要求用它匹配清算网点。
     */
    private String bankProvinceCity;
    /** 纳税人识别号（对公代付必填） */
    private String taxNo;

    private Boolean isDefault;
    /** ACTIVE / DISABLED（停用后不可用于新提现单，历史单快照不受影响） */
    private String status;

    /**
     * 版本号：<b>预留，当前未参与并发校验</b>。V307 的列注释写「防止并发改账户」是目标态——
     * 本仓既没有 {@code @Version}，也没注册乐观锁拦截器，写入路径（{@code insert}、
     * {@link com.aicabinet.trade.mapper.PayoutAccountMapper#switchDefault}、
     * {@code PayoutAccountService#disable} 的 {@code updateById}）都<b>不</b>递增它。
     *
     * <p>要接成真锁，必须所有写路径一起改（含 {@code switchDefault} 的 SQL 里
     * {@code SET version = version + 1}），只给某一条加 {@code where version = ?} 会让版本号
     * 变成「只在部分写入时前进」的假时钟。现有并发窗口无害：{@code switchDefault} 只碰
     * ACTIVE 行，停用后的账户由 {@code resolveForApply} 的 ACTIVE 校验兜住。
     */
    private Integer version;
    private Long createdBy;
    private Long reviewedBy;
    private Instant createdAt;
    private Instant updatedAt;

    public boolean isActive() {
        return PayoutAccountStatus.ACTIVE.equals(status);
    }
}
