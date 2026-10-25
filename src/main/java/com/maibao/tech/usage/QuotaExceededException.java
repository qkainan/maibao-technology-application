package com.maibao.tech.usage;

import java.math.BigDecimal;

import com.maibao.tech.common.BizException;

/** 超出当日配额：业务码 4029，前端据此提示并引导明日再试/提额。 */
public class QuotaExceededException extends BizException {

    public static final int CODE = 4029;

    public QuotaExceededException(BigDecimal limit, BigDecimal used) {
        super(CODE, String.format(
                "今日额度已用完（额度 %.2f 元，已用 %.2f 元），请明日再试或联系管理员提额",
                limit.doubleValue(), used.doubleValue()));
    }
}
