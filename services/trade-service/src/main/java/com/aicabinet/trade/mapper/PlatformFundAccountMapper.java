package com.aicabinet.trade.mapper;

import com.aicabinet.trade.domain.PlatformFundAccount;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Update;

import java.util.Optional;

@Mapper
public interface PlatformFundAccountMapper extends BaseTradeMapper<PlatformFundAccount> {

    default Optional<PlatformFundAccount> findByChannel(String channel) {
        return Optional.ofNullable(selectOne(Wrappers.<PlatformFundAccount>lambdaQuery()
                .eq(PlatformFundAccount::getChannel, channel)));
    }

    /**
     * 余额原子推进（入账正 / 出账负）。余额不足扣为负是<b>允许的</b>——负值本身就是
     * 「台账与实际不符」的显式信号，由巡检/日检告警兜住，不在 SQL 层静默拒绝。
     *
     * @return 是否命中行（false = 账户不存在）
     */
    @Update("UPDATE platform_fund_account SET balance_cents = balance_cents + #{deltaCents} "
            + "WHERE account_id = #{accountId}")
    boolean adjustBalance(long accountId, long deltaCents);
}
