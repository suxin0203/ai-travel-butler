package com.suxin.travel.traveljava.common;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Emails 归一/校验单元测试。
 * 重点钉住：空串/纯空白必须归一为 null（=「未填」），否则前端表单的空输入
 * 会被可选校验误判为「邮箱格式不正确」（生产部署实测暴露的回归点）。
 */
class EmailsTest {

    @Test
    @DisplayName("normalize：null 与空串/纯空白归一为 null（未填）")
    void normalizeBlank() {
        assertNull(Emails.normalize(null));
        assertNull(Emails.normalize(""));
        assertNull(Emails.normalize("   "));
    }

    @Test
    @DisplayName("normalize：trim + 整体转小写")
    void normalizeCase() {
        assertEquals("a@b.com", Emails.normalize("  A@B.COM "));
    }

    @Test
    @DisplayName("isValid：合法/非法/超长/未填(null) 判定")
    void isValidCases() {
        assertTrue(Emails.isValid("a@b.com"));
        assertFalse(Emails.isValid("not-an-email"));
        assertFalse(Emails.isValid(null));
        assertFalse(Emails.isValid("x".repeat(101) + "@b.com"));
    }
}
