package com.maibao.tech.usage.util;

import org.springframework.stereotype.Component;

/**
 * token 估算：中文字符按 1 个 token，其余按 4 字符 1 个 token 粗略折算。
 *
 * <p>真实 token 数只有厂商的 tokenizer 算得准；这里的目的不是精确，
 * 而是让"日志 + 成本"这条模拟业务有稳定、可解释的数值。</p>
 */
@Component
public class TokenEstimator {

    public int estimate(String text) {
        if (text == null || text.isEmpty()) {
            return 0;
        }
        int cjk = 0;
        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            if (Character.UnicodeScript.of(ch) == Character.UnicodeScript.HAN) {
                cjk++;
            }
        }
        int others = text.length() - cjk;
        int estimate = cjk + (int) Math.ceil(others / 4.0);
        return Math.max(1, estimate);
    }
}
