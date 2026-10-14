package com.lingcai.qlengine;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 作为测量服务的自描述与错误分类。
 *
 * <p>调用方（自进化框架）把本服务当作读数来源：读数必须能区分「判定结果」和「没读到」，
 * 还要能知道读数来自哪个语义版本。所以：
 * <ul>
 *   <li>{@link #errorCode} 把 {@code error} 字符串归成闭集错误码，调用方不必猜字符串；</li>
 *   <li>{@link #health} 报告引擎版本、函数白名单哈希与已知的语义取舍，任一变化即是新的测量版本；</li>
 *   <li>{@link #compile} 只解析不求值，给规则抽取的可编译率用。</li>
 * </ul>
 */
public final class Measure {

    public static final String API = "qlengine.measure/1";

    /** 已知的语义取舍：改了就是新的测量版本，调用方需要重测基线。 */
    public static final Map<String, String> SEMANTICS = Map.of(
            "numeric", "double",
            "isWeekendOrHoliday", "weekend-only",
            "unknownFunction", "abstain");

    private Measure() {
    }

    /**
     * 错误码闭集。
     *
     * <ul>
     *   <li>{@code PARSE}：QL 文法错误；</li>
     *   <li>{@code EMPTY}：QL 为空；</li>
     *   <li>{@code EVAL}：求值时抛错；</li>
     *   <li>{@code MISSING}：取不到字段，三值结果为 UNKNOWN；</li>
     *   <li>{@code ABSTAIN}：没有缺字段却给不出布尔值（如未知函数）；</li>
     *   <li>{@code null}：有明确判定。</li>
     * </ul>
     * {@code INTERNAL}（服务内部异常）与 {@code BAD_REQUEST}（请求不合法）由 {@link Server} 给出。
     */
    public static String errorCode(Ql.Verdict v) {
        String e = v.error();
        if (e != null) {
            if (e.startsWith("parse:")) {
                return "PARSE";
            }
            if (e.equals("empty")) {
                return "EMPTY";
            }
            return "EVAL";
        }
        if (v.verdict() == null) {
            return v.missing().isEmpty() ? "ABSTAIN" : "MISSING";
        }
        return null;
    }

    public static String functionsHash() {
        return sha256(String.join("\n", Registry.NAMES));
    }

    /** 测量版本：引擎 + 函数白名单 + 语义取舍。三者任一变化，版本号就变。 */
    public static String measurementVersion() {
        return Server.ENGINE + "#" + sha256(Server.ENGINE + "|" + functionsHash() + "|"
                + new java.util.TreeMap<>(SEMANTICS)).substring(0, 12);
    }

    public static Map<String, Object> health() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.put("engine", Server.ENGINE);
        out.put("api", API);
        out.put("measurementVersion", measurementVersion());
        out.put("functionsHash", functionsHash());
        out.put("functions", Registry.NAMES.size());
        out.put("semantics", new java.util.TreeMap<>(SEMANTICS));
        return out;
    }

    /**
     * 只解析不求值。{@code aliases} 是 QL 引用到的字段别名：在空报文上求值时三值路径报告的缺失集合
     * （短路可能让少数分支不被访问，所以是下界）。
     */
    public static Map<String, Object> compile(String ql) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (ql == null || ql.isBlank()) {
            out.put("ok", false);
            out.put("errorCode", "EMPTY");
            out.put("error", "empty");
            return out;
        }
        try {
            Ql.parse(ql);
        } catch (Ql.SyntaxError e) {
            out.put("ok", false);
            out.put("errorCode", "PARSE");
            out.put("error", "parse: " + e.getMessage());
            return out;
        }
        Ql.Verdict probe = Ql.run(ql, new Fields.Env(Map.of(), Fields.normalizeTable(Map.of()),
                Fields.normalizeTable(Map.of())));
        out.put("ok", true);
        out.put("errorCode", null);
        out.put("aliases", new ArrayList<>(probe.missing()));
        return out;
    }

    public static List<Map<String, Object>> compileAll(List<Map.Entry<String, String>> rules) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map.Entry<String, String> r : rules) {
            Map<String, Object> one = new LinkedHashMap<>();
            one.put("key", r.getKey());
            one.putAll(compile(r.getValue()));
            out.add(one);
        }
        return out;
    }

    static String sha256(String s) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
