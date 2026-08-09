package com.aicabinet.trade.domain;

import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.IdType;
import java.time.Instant;

@TableName("member")
public class Member {
    @TableId(type = IdType.AUTO)
    private Long memberId;

    private Long userId;

    private String memberLevel;

    private Integer totalPoints = 0;

    private Integer availablePoints = 0;

    private Integer usedPoints = 0;

    private Integer expiredPoints = 0;

    private java.math.BigDecimal totalSpent;

    private Integer orderCount = 0;

    private String inviteCode;

    private Long invitedBy;

    private Instant createdAt = Instant.now();

    private Instant updatedAt;

    private Instant levelUpgradeAt;

    public Long getMemberId() { return memberId; }
    public void setMemberId(Long memberId) { this.memberId = memberId; }

    public Long getUserId() { return userId; }
    public void setUserId(Long userId) { this.userId = userId; }

    public String getMemberLevel() { return memberLevel; }
    public void setMemberLevel(String memberLevel) { this.memberLevel = memberLevel; }

    public Integer getTotalPoints() { return totalPoints; }
    public void setTotalPoints(Integer totalPoints) { this.totalPoints = totalPoints; }

    public Integer getAvailablePoints() { return availablePoints; }
    public void setAvailablePoints(Integer availablePoints) { this.availablePoints = availablePoints; }

    public Integer getUsedPoints() { return usedPoints; }
    public void setUsedPoints(Integer usedPoints) { this.usedPoints = usedPoints; }

    public Integer getExpiredPoints() { return expiredPoints; }
    public void setExpiredPoints(Integer expiredPoints) { this.expiredPoints = expiredPoints; }

    public java.math.BigDecimal getTotalSpent() { return totalSpent; }
    public void setTotalSpent(java.math.BigDecimal totalSpent) { this.totalSpent = totalSpent; }

    public Integer getOrderCount() { return orderCount; }
    public void setOrderCount(Integer orderCount) { this.orderCount = orderCount; }

    public String getInviteCode() { return inviteCode; }
    public void setInviteCode(String inviteCode) { this.inviteCode = inviteCode; }

    public Long getInvitedBy() { return invitedBy; }
    public void setInvitedBy(Long invitedBy) { this.invitedBy = invitedBy; }

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }

    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }

    public Instant getLevelUpgradeAt() { return levelUpgradeAt; }
    public void setLevelUpgradeAt(Instant levelUpgradeAt) { this.levelUpgradeAt = levelUpgradeAt; }
}
