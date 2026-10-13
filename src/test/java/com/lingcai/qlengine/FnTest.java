package com.lingcai.qlengine;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 函数层的语义护栏。
 *
 * <p>期望值不是凭感觉写的，是从 qlrun.py 的对应实现推出来的 —— 这里每断一条，
 * 对拍就会在那一处炸掉。挑的都是移植最容易走样的点，不是覆盖率练习。
 */
class FnTest {

    // -------------------------------------------------- similar：difflib 移植

    @Test
    void similarMatchesDifflibRatio() {
        // difflib.SequenceMatcher(None, a, b).ratio() = 2M/T
        // "abcd"/"abcd"：M=4, T=8 → 1.0
        assertEquals(1.0, (Double) Fn.similar("abcd", "abcd"), 1e-9);
        // "abcd"/"abce"：最长公共块 "abc"，M=3, T=8 → 0.75
        assertEquals(0.75, (Double) Fn.similar("abcd", "abce"), 1e-9);
        // 完全不相交 → 0
        assertEquals(0.0, (Double) Fn.similar("abc", "xyz"), 1e-9);
        // 递归分块才对：difflib 会在最长块两侧继续找，不是只数一个块
        // "aXbXc"/"abc"：块 a、b、c 各 1 → M=3, T=8 → 0.75
        assertEquals(0.75, (Double) Fn.similar("aXbXc", "abc"), 1e-9);
    }

    @Test
    void similarEmptyPairIsOne() {
        // T=0 时 difflib 定义 ratio()=1.0。两个空串「相似」在审单里没什么意义，
        // 但口径必须跟 qlrun 一致 —— 对拍不允许有这种自作主张的偏差。
        assertEquals(1.0, (Double) Fn.similar("", ""), 1e-9);
    }

    // -------------------------------------------------- regexExtract：分组语义

    @Test
    void regexExtractFollowsPythonGroupSemantics() {
        // 0 个分组 → 整个匹配
        assertEquals(List.of("123", "456"), Fn.regexExtract("a123b456", "\\d+"));
        // 1 个分组 → 该分组
        assertEquals(List.of("123"), Fn.regexExtract("no.123", "no\\.(\\d+)"));
        // 多个分组 → 每个匹配拼成一个串（Python 给 tuple，这里没有 tuple 类型）
        List<?> multi = (List<?>) Fn.regexExtract("a1b2", "([a-z])(\\d)");
        assertEquals(2, multi.size());
    }

    // -------------------------------------------------- 中文大写金额

    @Test
    void cnCapitalToNumber() {
        assertEquals(123.0, (Double) Fn.cnCapitalToNumber("壹佰贰拾叁元整"), 1e-9);
        assertEquals(1000.0, (Double) Fn.cnCapitalToNumber("壹仟元整"), 1e-9);
        // 「零」占位不能把十位吃掉
        assertEquals(105.0, (Double) Fn.cnCapitalToNumber("壹佰零伍元"), 1e-9);
    }

    /**
     * 数值一律 double，不是 fields.json 声明的 BigDecimal。
     *
     * <p>这是**刻意**的：对拍基准是 qlrun.py，它用 Python float。改成 BigDecimal
     * 会让 15870 次对拍全线漂移，反而看不出移植有没有错。契约与实现的这处不一致
     * 记在 project-docs 里，等对拍稳定后再统一收口。
     */
    @Test
    void numericsAreDoubleMatchingQlrun() {
        assertTrue(Fn.toNumber("123.45") instanceof Double);
        assertTrue(Fn.sumField(List.of(Map.of("a", 1)), "a") instanceof Double);
        assertTrue(Fn.cnCapitalToNumber("壹元") instanceof Double);
    }

    // -------------------------------------------------- 取不到数 → UNKNOWN

    @Test
    void missingFieldPropagatesUnknown() {
        assertTrue(Unknown.is(Fn.toNumber(Unknown.U)));
        assertTrue(Unknown.is(Fn.containsAny(Unknown.U, List.of("x"))));
        assertTrue(Unknown.is(Fn.sumField(Unknown.U, "amount")));
    }

    // -------------------------------------------------- sumField 不做大小写兜底

    @Test
    void sumFieldUsesExactKey() {
        List<Map<String, Object>> rows = List.of(
                Map.of("amount", 10), Map.of("amount", 5));
        assertEquals(15.0, (Double) Fn.sumField(rows, "amount"), 1e-9);
        // 与 Python 的 r.get(str(field)) 对齐：键名不符就是一个字段都没取到。
        // 取数层的大小写兜底只在字段解析处做，行内字段不做 —— 否则
        // countWhere/sumField 的行为会和真引擎分叉。
        // 行在、字段一个都没取到 → UNKNOWN 而非 0：合计不出来就别假装是零。
        assertTrue(Unknown.is(Fn.sumField(rows, "Amount")));
    }

    @Test
    void sumFieldOnEmptyRowsIsZeroNotUnknown() {
        // 空集合的合计确实是 0 —— 这跟「有行但取不到字段」是两回事。
        assertEquals(0.0, (Double) Fn.sumField(List.of(), "amount"), 1e-9);
    }

    // -------------------------------------------------- amountEquals 容差

    @Test
    void amountEqualsHonoursTolerance() {
        assertEquals(Boolean.TRUE, Fn.amountEquals(100.0, 100.4, 0.5));
        assertEquals(Boolean.FALSE, Fn.amountEquals(100.0, 101.0, 0.5));
    }

    @Test
    void hasAttachmentOnEmptyList() {
        assertEquals(Boolean.FALSE, Fn.hasAttachment(List.of()));
        assertEquals(Boolean.TRUE, Fn.hasAttachment(List.of(Map.of("a", 1))));
    }

    @Test
    void consecutiveInvoiceNo() {
        assertEquals(Boolean.TRUE,
                Fn.hasConsecutiveInvoiceNo(List.of("001", "002", "003"), 3));
        assertEquals(Boolean.FALSE,
                Fn.hasConsecutiveInvoiceNo(List.of("001", "005", "009"), 3));
    }

    @Test
    void consecutiveInvoiceNoHandlesTwentyDigitEInvoice() {
        // 全电发票号码 20 位定长，Long.MAX_VALUE 只有 19 位。用 long 接会
        // NumberFormatException，旧实现 catch 掉当「跳过」，等于把全电票全丢了。
        // 号码为构造值，形状与真实全电发票一致（20 位定长）。
        assertEquals(Boolean.TRUE, Fn.hasConsecutiveInvoiceNo(
                List.of("26000000000000001465", "26000000000000001466"), 2));
        // 同为 20 位但不连号
        assertEquals(Boolean.FALSE, Fn.hasConsecutiveInvoiceNo(
                List.of("26000000000000001465", "26000000000000001999"), 2));
        // 20 位与 8 位混在一张单上，不能因为长号被丢弃就漏判
        assertEquals(Boolean.TRUE, Fn.hasConsecutiveInvoiceNo(
                List.of("01234567", "26000000000000002726",
                        "26000000000000002727"), 2));
    }

    @Test
    void consecutiveInvoiceNoSkipsDecimals() {
        // NUMRE 会匹配小数。小数不是票号，显式跳过而不是靠异常兜 ——
        // qlrun.py:526 的 int(m.group()) 在这里会抛 ValueError（已知差异）。
        assertEquals(Boolean.FALSE,
                Fn.hasConsecutiveInvoiceNo(List.of("1.5", "2.5"), 2));
    }
}
