package com.lingcai.qlengine;

/**
 * 取不到数的哨兵。单例，用 {@code == Unknown.U} 判断。
 *
 * <p>刻意不用 null：报文里 {@code null} 是<b>确定的空值</b>（字段存在但没填），
 * 而 UNKNOWN 是<b>取不到</b>（字段根本不存在）。两者对规则的意义完全不同 ——
 * 前者可以参与判定，后者必须让整条规则弃权。
 *
 * <p>把「取不到数」当成 false 会让规则<b>反向误触发</b>：
 * {@code countWhere(attachmentInfo, "containsAny(filename, [...])") >= 1} 里，
 * 若 filename 在客户真实报文里根本不存在，按 false 算 → 计数 0 → {@code >= 1} 为假
 * → 「附件缺失」判定成立 → 误拦一张合规单据。
 */
public final class Unknown {

    /** 唯一实例。 */
    public static final Unknown U = new Unknown();

    private Unknown() {
    }

    public static boolean is(Object v) {
        return v == U;
    }

    /** 任一为 UNKNOWN。参数求值处高频使用。 */
    public static boolean any(Object... vs) {
        for (Object v : vs) {
            if (v == U) {
                return true;
            }
        }
        return false;
    }

    @Override
    public String toString() {
        return "UNKNOWN";
    }
}
