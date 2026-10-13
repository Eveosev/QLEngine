package com.lingcai.qlengine;

import com.alibaba.qlexpress4.Express4Runner;
import com.alibaba.qlexpress4.InitOptions;
import com.alibaba.qlexpress4.QLOptions;
import com.alibaba.qlexpress4.QLResult;
import com.alibaba.qlexpress4.runtime.Parameters;
import com.alibaba.qlexpress4.security.QLSecurityStrategy;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * 真 QLExpress4 执行路径 —— 判定的<b>权威</b>来源。
 *
 * <p>与 {@link Ql} 的三值路径并行跑，两者差异即信息：
 * <ul>
 *   <li>native 报错 / 给 null 而 3vl 给 UNKNOWN → 字段取不到，符合预期；
 *   <li>两者都给布尔但不一致 → <b>真问题</b>，要么是 {@link Fn} 的移植偏差，
 *       要么是 3vl 的宽松相等（{@link Fn#looseEq}）与 QLExpress 原生相等语义不同。
 * </ul>
 *
 * <p>本类是整个工程<b>唯一</b>接触 QLExpress API 的地方。上游改签名时只需改这一处。
 *
 * <h2>几个刻意的选项</h2>
 * <ul>
 *   <li><b>securityStrategy = isolation</b>（默认值，显式写出）：禁止一切 Java
 *       字段/方法访问。跑的是模型抽取出来的产物，不受信任，能力只应通过白名单函数暴露。
 *   <li><b>avoidNullPointer = true</b>：取不到数时不抛 NPE。判定的价值在于「跑出
 *       什么结果」，让一个空字段炸掉整条规则只会把信号变成噪音 —— 缺字段这件事
 *       由 unresolved 单独报。
 *   <li><b>timeoutMillis</b>：服务要防跑飞的脚本。
 *   <li><b>precise = true</b>：金额计算走 BigDecimal，与 fields.json 里
 *       toNumber/sumField 声明返回 BigDecimal 的口径一致。
 * </ul>
 */
public final class Native {

    /** 单条规则的执行上限。模型产出的 QL 理应是纯表达式，跑不满 1 秒。 */
    private static final long TIMEOUT_MS = 1000L;

    /**
     * 当前请求的取数环境。
     *
     * <p>QLExpress 的 CustomFunction 签名里没有地方挂业务上下文，而 countWhere
     * 的行内表达式必须拿到字段表才能解析。虚拟线程一请求一线程，ThreadLocal
     * 在这里是干净的。
     */
    private static final ThreadLocal<Fields.Env> CURRENT = new ThreadLocal<>();

    private static final Express4Runner RUNNER = build();

    private static final QLOptions OPTS = QLOptions.builder()
            .precise(true)
            .avoidNullPointer(true)
            .timeoutMillis(TIMEOUT_MS)
            .cache(true)
            .build();

    private Native() {
    }

    private static Express4Runner build() {
        Express4Runner r = new Express4Runner(InitOptions.builder()
                .securityStrategy(QLSecurityStrategy.isolation())
                .build());

        // 15 个审单函数注册进引擎。实现体与 3vl 路径共用 Fn，避免两套语义。
        // countWhere 单独处理：第二参是行内表达式串，要在行上下文里再跑一遍。
        reg(r, "toNumber", p -> Fn.toNumber(a(p, 0)));
        reg(r, "containsAny", p -> Fn.containsAny(a(p, 0), a(p, 1)));
        reg(r, "sumField", p -> Fn.sumField(a(p, 0), a(p, 1)));
        reg(r, "hasAttachment", p -> Fn.hasAttachment(a(p, 0)));
        reg(r, "amountEquals", p -> Fn.amountEquals(a(p, 0), a(p, 1), a(p, 2)));
        reg(r, "daysBetween", p -> Fn.daysBetween(a(p, 0), a(p, 1)));
        reg(r, "monthsBetween", p -> Fn.monthsBetween(a(p, 0), a(p, 1)));
        reg(r, "dateInRange", p -> Fn.dateInRange(a(p, 0), a(p, 1), a(p, 2)));
        reg(r, "timeInRange", p -> Fn.timeInRange(a(p, 0), a(p, 1), a(p, 2)));
        reg(r, "similar", p -> Fn.similar(a(p, 0), a(p, 1)));
        reg(r, "fuzzyMatch", p -> Fn.similar(a(p, 0), a(p, 1)));
        reg(r, "ratioNotExceed", p -> Fn.ratioNotExceed(a(p, 0), a(p, 1), a(p, 2)));
        reg(r, "regexExtract", p -> Fn.regexExtract(a(p, 0), a(p, 1)));
        reg(r, "hasConsecutiveInvoiceNo", p -> Fn.hasConsecutiveInvoiceNo(a(p, 0), a(p, 1)));
        reg(r, "cnCapitalToNumber", p -> Fn.cnCapitalToNumber(a(p, 0)));
        reg(r, "isWeekendOrHoliday", p -> Fn.isWeekendOrHoliday(a(p, 0)));
        reg(r, "countWhere", p -> countWhereNative(a(p, 0), a(p, 1), CURRENT.get()));
        return r;
    }

    /** 把 {@code Parameters -> Object} 的实现体接成 QLExpress 的 CustomFunction。 */
    private static void reg(Express4Runner r, String name, java.util.function.Function<Parameters, Object> impl) {
        r.addFunction(name, (ctx, params) -> out(impl.apply(params)));
    }

    private static Object a(Parameters p, int i) {
        return p != null && i < p.size() ? p.getValue(i) : null;
    }

    /** QLExpress 没有 UNKNOWN —— 出口处降级成 null，让引擎按它自己的语义处理。 */
    private static Object out(Object v) {
        return Unknown.is(v) ? null : v;
    }

    /**
     * native 路径的 countWhere：行内表达式交回 QLExpress 自己跑。
     *
     * <p>行上下文用 {@link Fields.Env#child} 解析，与 3vl 路径<b>完全同源</b> ——
     * 大小写兜底、extendField 摊平、多路径回退一个不落。
     *
     * <p>这一点是对拍能不能用的前提：若这里只塞裸 row map，native 会因为
     * {@code filename} / {@code fileName} 取不到而数出 0，对拍报出来的就是我自己
     * 制造的取数不对称，而不是两个引擎的求值差异。实测这处曾贡献全部 34 条「真分歧」。
     */
    @SuppressWarnings("unchecked")
    private static Object countWhereNative(Object rowsArg, Object cond, Fields.Env env) {
        Object rs = Fn.rows(rowsArg);
        if (Unknown.is(rs) || cond == null || Unknown.is(cond)) {
            return null;
        }
        String expr = String.valueOf(cond);
        Set<String> vars;
        try {
            vars = RUNNER.getOutVarNames(expr);
        } catch (Throwable t) {
            return null;
        }
        int n = 0;
        for (Map<String, Object> row : (List<Map<String, Object>>) rs) {
            Fields.Env rowEnv = env.child(row);
            Map<String, Object> ctx = new HashMap<>();
            for (String v : vars) {
                Object val = rowEnv.lookup(v);
                ctx.put(v, Unknown.is(val) ? null : val);
            }
            try {
                Object v = RUNNER.execute(expr, ctx, OPTS).getResult();
                if (Boolean.TRUE.equals(Fn.truthy(v))) {
                    n++;
                }
            } catch (Throwable ignored) {
                // 单行跑不起来就不计数 —— native 没有弃权语义，这正是它与 3vl
                // 的根本差异，也正是对拍要照出来的东西。
            }
        }
        return n;
    }

    /**
     * 跑一条 QL。
     *
     * @return {@code {result, error, unresolved, trace}}；result 为引擎原生返回值
     */
    public static Map<String, Object> run(String ql, Fields.Env env) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (ql == null || ql.isBlank()) {
            out.put("result", null);
            out.put("error", "empty");
            out.put("unresolved", List.of());
            return out;
        }

        Map<String, Object> ctx = new HashMap<>();
        Set<String> unresolved = new TreeSet<>();
        try {
            // 用引擎自己的 getOutVarNames 取变量 —— 比自己解析权威，
            // 也能照出 3vl parser 与真引擎在文法上的分歧。
            for (String name : RUNNER.getOutVarNames(ql)) {
                Object v = env.lookup(name);
                if (Unknown.is(v)) {
                    unresolved.add(name);
                    ctx.put(name, null);
                } else {
                    ctx.put(name, v);
                }
            }
        } catch (Throwable t) {
            out.put("result", null);
            out.put("error", "parse: " + t.getClass().getSimpleName() + ": " + t.getMessage());
            out.put("unresolved", List.of());
            return out;
        }

        CURRENT.set(env);
        try {
            QLResult res = RUNNER.execute(ql, ctx, OPTS);
            out.put("result", res.getResult());
            out.put("error", null);
        } catch (Throwable t) {
            // 取不到数/类型不符时抛异常 —— 这正是它没有三值逻辑的体现，
            // 不是服务故障，照实报上去。
            out.put("result", null);
            out.put("error", t.getClass().getSimpleName() + ": " + t.getMessage());
        } finally {
            CURRENT.remove();
        }
        out.put("unresolved", new ArrayList<>(unresolved));
        return out;
    }
}
