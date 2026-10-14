package com.lingcai.qlengine;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 测量服务的自描述：错误码闭集、测量版本、只编译。
 *
 * <p>调用方据此区分「判定」与「没读到」。错误码一旦漂移，调用方会把求值失败当成业务结论。
 */
class MeasureTest {

    private static Ql.Verdict run(String ql, Map<String, Object> payload, Map<String, Object> fields) {
        return Ql.run(ql, new Fields.Env(payload, Fields.normalizeTable(fields), Map.of()));
    }

    @Test
    void decidedVerdictHasNoErrorCode() {
        assertNull(Measure.errorCode(run("1 < 2", Map.of(), Map.of())));
        assertNull(Measure.errorCode(run("missingField > 1 && 1 > 2", Map.of(), Map.of())));
    }

    @Test
    void errorCodesAreClosedSet() {
        assertEquals("PARSE", Measure.errorCode(run("a > > b", Map.of(), Map.of())));
        assertEquals("EMPTY", Measure.errorCode(run("  ", Map.of(), Map.of())));
        assertEquals("MISSING", Measure.errorCode(run("missingField > 1", Map.of(), Map.of())));
        assertEquals("ABSTAIN", Measure.errorCode(run("noSuchFunction(1) > 0", Map.of(), Map.of())));
    }

    @Test
    void compileSeparatesSyntaxFromData() {
        Map<String, Object> ok = Measure.compile("daysBetween(startDate, endDate) > 2");
        assertEquals(true, ok.get("ok"));
        assertEquals(List.of("endDate", "startDate"), ok.get("aliases"));
        Map<String, Object> bad = Measure.compile("a > > b");
        assertEquals(false, bad.get("ok"));
        assertEquals("PARSE", bad.get("errorCode"));
        assertEquals("EMPTY", Measure.compile("").get("errorCode"));
    }

    @Test
    void measurementVersionIsStableAndDescribesSemantics() {
        assertEquals(Measure.measurementVersion(), Measure.measurementVersion());
        assertTrue(Measure.measurementVersion().startsWith(Server.ENGINE + "#"));
        Map<String, Object> h = Measure.health();
        assertEquals(true, h.get("ok"));
        assertEquals(Measure.functionsHash(), h.get("functionsHash"));
        assertFalse(((Map<?, ?>) h.get("semantics")).isEmpty());
    }
}
