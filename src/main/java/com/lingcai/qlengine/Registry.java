package com.lingcai.qlengine;

import java.util.List;

/**
 * 自定义函数白名单（闭集）。
 *
 * <p>与 {@code lingcai_new/assets/fields/fields.json} 的 functions 段一一对应。
 * 提示词把这份清单渲染给模型、qlexport 的静态检查用它判「未知函数」，服务侧再实现
 * 一遍 —— 三处必须同步，加函数时三处一起改。
 */
public final class Registry {

    /** 规范名 + 别名。 */
    public static final List<String> NAMES = List.of(
            "toNumber",
            "daysBetween",
            "monthsBetween",          // daysBetween 的别名
            "isWeekendOrHoliday",
            "timeInRange",
            "dateInRange",
            "sumField",
            "countWhere",
            "hasConsecutiveInvoiceNo",
            "containsAny",
            "amountEquals",
            "similar",
            "fuzzyMatch",             // similar 的别名
            "hasAttachment",
            "ratioNotExceed",
            "regexExtract",
            "cnCapitalToNumber");

    private Registry() {
    }
}
