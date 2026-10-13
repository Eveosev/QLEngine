package com.lingcai.qlengine;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 取数层：把 jsonpath 别名解析成报文里的值。
 *
 * <p>语义与 lingcai_new/qlrun.py 的 {@code _get}/{@code _walk}/{@code Env.lookup}
 * 逐条对齐 —— 两个引擎要能对拍，取数口径必须先一致，否则差异全是噪音。
 */
public final class Fields {

    /** {@code [..]} 段：{@code []}、{@code [*]}、{@code [?(...)]} 都算「展开这一层数组」。 */
    private static final Pattern SEG = Pattern.compile("\\[[^\\]]*\\]");

    private Fields() {
    }

    /**
     * 取字段，大小写/下划线不敏感回退。
     *
     * <p>字段库里注册的是 {@code extendField.filename}，而客户真实报文里是 {@code fileName}
     * —— 纯大小写不匹配，实测影响 474 次取数。这类差异在对接不同客户时必然反复出现，
     * 与其每次去改字段库（还会被飞书同步覆盖、且触发全量重拆），不如在取数层容忍。
     *
     * <p>只做归一化后完全相等的回退，不做模糊匹配：{@code fileName}/{@code filename}
     * 可以互认，但绝不把 {@code fileType} 当成 {@code fileName}。
     */
    public static Object get(Map<String, Object> d, String name) {
        if (d.containsKey(name)) {
            return d.get(name);
        }
        String want = fold(name);
        for (Map.Entry<String, Object> e : d.entrySet()) {
            if (fold(e.getKey()).equals(want)) {
                return e.getValue();
            }
        }
        return Unknown.U;
    }

    private static String fold(String s) {
        return s.toLowerCase().replace("_", "");
    }

    /**
     * 按点号路径取值。任一层取不到 → UNKNOWN。数组层自动展开成列表。
     *
     * <p>{@code [?(...)]} 过滤器语义<b>故意不实现</b>，直接当成展开全部：过滤条件写错
     * 会让结果偏保守（多取几行），而假装支持再解析错会让结果<b>静默偏乐观</b>，
     * 后者危险得多。
     */
    @SuppressWarnings("unchecked")
    public static Object walk(Object node, String path) {
        Object cur = node;
        String p = path;
        while (p.startsWith("$")) {
            p = p.substring(1);
        }
        while (p.startsWith(".")) {
            p = p.substring(1);
        }
        for (String raw : p.split("\\.")) {
            if (raw.isEmpty()) {
                continue;
            }
            Matcher m = SEG.matcher(raw);
            boolean expand = m.find();
            String name = SEG.matcher(raw).replaceAll("");

            if (!name.isEmpty()) {
                if (cur instanceof List<?> rows) {
                    // 上一层已展开：逐行取同名字段
                    List<Object> nxt = new ArrayList<>();
                    for (Object x : rows) {
                        if (x instanceof Map<?, ?> row) {
                            Object v = get((Map<String, Object>) row, name);
                            if (!Unknown.is(v)) {
                                nxt.add(v);
                            }
                        }
                    }
                    if (nxt.isEmpty()) {
                        return Unknown.U;
                    }
                    cur = nxt;
                } else if (cur instanceof Map<?, ?> map) {
                    cur = get((Map<String, Object>) map, name);
                    if (Unknown.is(cur)) {
                        return Unknown.U;
                    }
                } else {
                    return Unknown.U;
                }
            }

            if (expand) {
                if (cur == null) {
                    // 字段在但为 null → 空集合，不是取不到
                    return new ArrayList<>();
                }
                List<Object> src = cur instanceof List<?> l
                        ? new ArrayList<>(l) : new ArrayList<>(List.of(cur));
                List<Object> flat = new ArrayList<>();
                for (Object x : src) {           // 上一层若已是列表，这里会嵌套，压平
                    if (x instanceof List<?> inner) {
                        flat.addAll(inner);
                    } else {
                        flat.add(x);
                    }
                }
                cur = flat;
            }
        }
        return cur;
    }

    /**
     * 求值环境：报文 + 字段别名表 + 行集合表 + 当前行（countWhere 内层用）。
     */
    public static final class Env {
        public final Map<String, Object> payload;
        public final Map<String, List<String>> fields;
        public final Map<String, List<String>> collections;
        public final Map<String, Object> row;
        /** 本次求值里取不到的别名，报告用。内外层共享。 */
        public final java.util.Set<String> missing;

        public Env(Map<String, Object> payload,
                   Map<String, List<String>> fields,
                   Map<String, List<String>> collections) {
            this(payload, fields, collections, null, new java.util.TreeSet<>());
        }

        private Env(Map<String, Object> payload,
                    Map<String, List<String>> fields,
                    Map<String, List<String>> collections,
                    Map<String, Object> row,
                    java.util.Set<String> missing) {
            this.payload = payload;
            this.fields = fields;
            this.collections = collections;
            this.row = row;
            this.missing = missing;
        }

        /** 派生行上下文。missing 共享 —— 内层的缺失也要冒泡到报告。 */
        public Env child(Map<String, Object> newRow) {
            return new Env(payload, fields, collections, newRow, missing);
        }

        /**
         * 别名求值。
         *
         * <p>行上下文优先：countWhere 第二参里的裸字段名指的是<b>当前行</b>的字段，
         * 不是全局同名字段。顺序反了会让行内条件恒取到第一行的值。
         */
        @SuppressWarnings("unchecked")
        public Object lookup(String name) {
            if (row != null) {
                Object v = get(row, name);
                if (!Unknown.is(v)) {
                    return v;
                }
                if (row.get("extendField") instanceof Map<?, ?> ext) {
                    // 报文把自定义字段塞在 extendField 下
                    v = get((Map<String, Object>) ext, name);
                    if (!Unknown.is(v)) {
                        return v;
                    }
                }
            }
            // 多路径回退：同一别名在字段库里常挂多个容器，逐条试，第一条取到就用。
            // 只有全部路径都取不到才记 missing —— 记早了报告会把「换个容器就有」的
            // 字段也算成缺口，把真正的问题淹掉。
            for (Map<String, List<String>> table : List.of(collections, fields)) {
                List<String> paths = table.get(name);
                if (paths == null) {
                    continue;
                }
                for (String path : paths) {
                    Object v = walk(payload, path);
                    if (!Unknown.is(v) && !(v instanceof List<?> l && l.isEmpty())) {
                        return v;
                    }
                }
                missing.add(name);
                return Unknown.U;
            }
            missing.add(name);
            return Unknown.U;
        }
    }

    /** 把 {@code String | List<String>} 的别名表统一成 {@code List<String>}。 */
    @SuppressWarnings("unchecked")
    public static Map<String, List<String>> normalizeTable(Map<String, Object> raw) {
        Map<String, List<String>> out = new LinkedHashMap<>();
        if (raw == null) {
            return out;
        }
        for (Map.Entry<String, Object> e : raw.entrySet()) {
            Object v = e.getValue();
            if (v instanceof List<?> l) {
                List<String> paths = new ArrayList<>();
                for (Object x : l) {
                    if (x != null) {
                        paths.add(String.valueOf(x));
                    }
                }
                out.put(e.getKey(), paths);
            } else if (v != null) {
                out.put(e.getKey(), List.of(String.valueOf(v)));
            }
        }
        return out;
    }
}
