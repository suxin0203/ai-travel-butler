package com.suxin.travel.traveljava.common;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * email 归一与校验（契约 §2.5）：trim 后整体转小写；空串/纯空白归一为 null（=「未填」）。
 * 主流邮箱服务商本地部分与域名均按不区分大小写处理，与 MySQL uk_email 的
 * 大小写不敏感排序规则一致。归一后的值统一用于：验证码 Redis 键、比对、
 * 唯一性查询、DB 存储、「与当前绑定是否相同」的判断。
 */
public class Emails {

    private static final int MAX_LENGTH = 100;
    private static final Pattern FORMAT = Pattern.compile("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$");

    private Emails() {
    }

    public static String normalize(String raw) {
        if (raw == null) {
            return null;
        }
        String normalized = raw.trim().toLowerCase(Locale.ROOT);
        // 空串视同未填（契约 §2.5「email 可选」）：前端表单会把空输入作为 "" 提交，
        // 若不改写为 null，可选字段留空会被 isValid 误判为「邮箱格式不正确」（生产部署实测暴露）
        return normalized.isEmpty() ? null : normalized;
    }

    /** 归一后校验：格式 + 长度 ≤100（超长按「邮箱格式不正确」处理，契约 §2.5/§2.5a） */
    public static boolean isValid(String normalized) {
        return normalized != null && normalized.length() <= MAX_LENGTH
                && FORMAT.matcher(normalized).matches();
    }
}
