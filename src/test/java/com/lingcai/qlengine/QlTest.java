package com.lingcai.qlengine;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 三值逻辑的护栏。
 *
 * <p>这里断言的是本服务存在的理由：UNKNOWN 不能退化成 false。退化一次，
 * {@code countWhere(...) >= 1} 这类规则就会在字段缺失时数出 0、判定「附件缺失」成立，
 * 把一张合规单据拦下来 —— 反向误报，比漏报更难查。
 */
class QlTest {

    private static Fields.Env env(Map<String, Object> payload, Map<String, Object> fields) {
        return new Fields.Env(payload, Fields.normalizeTable(fields), Map.of());
    }

    private static Ql.Verdict run(String ql, Map<String, Object> payload,
                                  Map<String, Object> fields) {
        return Ql.run(ql, env(payload, fields));
    }

    // -------------------------------------------------- Kleene 短路

    @Test
    void unknownAndFalseIsFalse() {
        // 一边确定为假，整个合取就是假 —— 不必知道另一边。这是短路的正确形态，
        // 也是唯一允许 UNKNOWN 消失的地方。
        Ql.Verdict v = run("missingField > 1 && 1 > 2", Map.of(), Map.of());
        assertEquals(Boolean.FALSE, v.verdict());
    }

    @Test
    void unknownOrTrueIsTrue() {
        Ql.Verdict v = run("missingField > 1 || 1 < 2", Map.of(), Map.of());
        assertEquals(Boolean.TRUE, v.verdict());
    }

    @Test
    void unknownAndTrueStaysUnknown() {
        Ql.Verdict v = run("missingField > 1 && 1 < 2", Map.of(), Map.of());
        assertNull(v.verdict());
        assertTrue(v.missing().contains("missingField"));
    }

    @Test
    void notUnknownStaysUnknown() {
        assertNull(run("!missingField", Map.of(), Map.of()).verdict());
    }

    // -------------------------------------------------- 缺字段要报出名字

    @Test
    void missingNamesAreSurfaced() {
        Ql.Verdict v = run("daysBetween(startDate, endDate) > 2", Map.of(), Map.of());
        assertNull(v.verdict());
        assertEquals(List.of("endDate", "startDate"), v.missing());
    }

    // -------------------------------------------------- countWhere 的行内表达式

    @Test
    void countWhereEvaluatesRowExpression() {
        Map<String, Object> payload = Map.of("lines", List.of(
                Map.of("amt", 100), Map.of("amt", 50), Map.of("amt", 900)));
        Ql.Verdict v = Ql.run("countWhere(lines, \"amt > 80\") >= 2",
                new Fields.Env(payload,
                        Fields.normalizeTable(Map.of()),
                        Fields.normalizeTable(Map.of("lines", "$.lines"))));
        assertEquals(Boolean.TRUE, v.verdict());
    }

    @Test
    void countWhereOnMissingCollectionIsUnknownNotZero() {
        // 本服务最核心的一条断言：集合取不到时必须弃权。
        // 若退化成 0，`>= 1` 判假、规则触发，凭空拦下一张合规单据。
        Ql.Verdict v = run("countWhere(noSuchList, \"amt > 80\") >= 1", Map.of(), Map.of());
        assertNull(v.verdict());
    }

    // -------------------------------------------------- 取数

    @Test
    void fieldPathResolves() {
        Map<String, Object> payload = Map.of("bill", Map.of("total", 300));
        Ql.Verdict v = run("toNumber(total) > 200", payload,
                Map.of("total", "$.bill.total"));
        assertEquals(Boolean.TRUE, v.verdict());
    }

    @Test
    void caseInsensitiveFallback() {
        // 报文用 fileName，字段库注册的是 filename —— 真实存在的落位错误，
        // 取数层兜一层大小写，不然 474 次取数全空。
        Map<String, Object> payload = Map.of("bill", Map.of("fileName", "a.pdf"));
        Ql.Verdict v = run("containsAny(filename, [\"pdf\"])", payload,
                Map.of("filename", "$.bill.filename"));
        assertEquals(Boolean.TRUE, v.verdict());
    }

    // -------------------------------------------------- 三元

    @Test
    void ternaryPicksBranch() {
        Map<String, Object> payload = Map.of("bill", Map.of("lvl", 5, "amt", 280));
        // 差旅标准按职级分档的真实写法
        String ql = "toNumber(amt) <= (toNumber(lvl) <= 7 ? 300 : 200)";
        Ql.Verdict v = run(ql, payload, Map.of("lvl", "$.bill.lvl", "amt", "$.bill.amt"));
        assertEquals(Boolean.TRUE, v.verdict());
    }

    @Test
    void nestedTernaryIsRightAssociative() {
        Map<String, Object> payload = Map.of("bill", Map.of("lvl", 9, "amt", 210));
        // lvl=9 落到第二档 200 → 210 > 200 → 断言不成立
        String ql = "toNumber(amt) <= (toNumber(lvl) <= 7 ? 300 : toNumber(lvl) <= 10 ? 200 : 150)";
        assertEquals(Boolean.FALSE,
                run(ql, payload, Map.of("lvl", "$.bill.lvl", "amt", "$.bill.amt")).verdict());
    }

    @Test
    void ternaryWithUnknownConditionIsUnknown() {
        // 分档条件取不到 → 不知道该用哪档标准 → 弃权。
        // 挑一个分支当答案会凭空造出一个标准值。
        assertNull(run("toNumber(amt) <= (toNumber(lvl) <= 7 ? 300 : 200)",
                Map.of("bill", Map.of("amt", 280)),
                Map.of("amt", "$.bill.amt", "lvl", "$.bill.lvl")).verdict());
    }

    // -------------------------------------------------- 语法错误不当成判定

    @Test
    void syntaxErrorIsReportedNotVerdict() {
        Ql.Verdict v = run("a > > b", Map.of(), Map.of());
        assertNull(v.verdict());
        assertTrue(v.error() != null && !v.error().isBlank());
    }
}
