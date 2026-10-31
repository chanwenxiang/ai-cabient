package com.aicabinet.trade.support;

import com.aicabinet.trade.domain.SkuCatalog;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 在架判据单源（台账 #367）。这里钉的是「收口时不能改变」的三件事：
 * 忽略大小写、null 不算在售、两档刻意不互补。
 */
class SkuOnShelfTest {

    @Test
    void isOnShelf_activeCaseInsensitive() {
        assertTrue(SkuOnShelf.isOnShelf(sku("ACTIVE")));
        assertTrue(SkuOnShelf.isOnShelf(sku("active")));
        assertFalse(SkuOnShelf.isOnShelf(sku("INACTIVE")));
        assertFalse(SkuOnShelf.isOnShelf(sku(null)));
        assertFalse(SkuOnShelf.isOnShelf(null));
    }

    @Test
    void isDelisted_inactiveCaseInsensitive() {
        assertTrue(SkuOnShelf.isDelisted(sku("INACTIVE")));
        assertTrue(SkuOnShelf.isDelisted(sku("inactive")));
        assertFalse(SkuOnShelf.isDelisted(sku("ACTIVE")));
        assertFalse(SkuOnShelf.isDelisted(sku(null)));
        assertFalse(SkuOnShelf.isDelisted(null));
    }

    /**
     * 其它档案态两档都 false：在售面把它当「不在架」（不进缺货/低库存/洞察），
     * 评审面把它当「未标下架」（继续出诊断）。改成互补等于改 2026-10-21 拍板的评审范围。
     */
    @Test
    void unknownStatus_isNeitherOnShelfNorDelisted() {
        assertFalse(SkuOnShelf.isOnShelf(sku("PENDING")));
        assertFalse(SkuOnShelf.isDelisted(sku("PENDING")));
    }

    private static SkuCatalog sku(String status) {
        SkuCatalog sku = new SkuCatalog();
        sku.setSkuId("100001");
        sku.setStatus(status);
        return sku;
    }
}
