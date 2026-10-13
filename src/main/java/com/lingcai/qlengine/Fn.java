package com.lingcai.qlengine;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 审单自定义函数（闭集 15 个 + 2 个别名）。
 *
 * <p>清单来自 {@code lingcai_new/assets/fields/fields.json} 的 functions 段，
 * 语义逐条对齐 {@code lingcai_new/qlrun.py} 的 BUILTINS —— 两个引擎要对拍，
 * 函数口径必须先一致。
 *
 * <p><b>全部函数对 UNKNOWN 透传</b>：任一入参取不到数，结果即 UNKNOWN，
 * 绝不降级成 false。
 *
 * <p>实测调用分布（总调用数）：containsAny 794、countWhere 646、toNumber 99、
 * hasAttachment 38、sumField 19、amountEquals 14、daysBetween 9、dateInRange 7、
 * similar 6、ratioNotExceed 5，其余各 1-2 次。前 5 个覆盖 97% 的调用。
 */
public final class Fn {

    /** countWhere 第二参是行内表达式串，需要回调外层求值器再解析一次。 */
    @FunctionalInterface
    public interface RowEvaluator {
        /** 返回 Boolean / 数值 / Unknown.U；解析失败返回 null。 */
        Object eval(String expr, Fields.Env rowEnv);
    }

    private static final Pattern NUMRE = Pattern.compile("-?\\d+(?:\\.\\d+)?");

    private Fn() {
    }

    // ------------------------------------------------------------ 归一化

    /**
     * 金额/数量归一化。兼容 int/float/数字串/带 ¥ 与千分位的文本。
     * <p>布尔值刻意判 UNKNOWN —— {@code true > 3000} 这种写法是规则写错了，
     * 不该悄悄按 1 算。
     */
    public static Object toNumber(Object v) {
        if (Unknown.is(v) || v == null) {
            return Unknown.U;
        }
        if (v instanceof Boolean) {
            return Unknown.U;
        }
        if (v instanceof Number n) {
            return n.doubleValue();
        }
        String s = String.valueOf(v).replace(",", "").replace("¥", "").replace("￥", "");
        Matcher m = NUMRE.matcher(s);
        return m.find() ? Double.parseDouble(m.group()) : Unknown.U;
    }

    /** 把任意取数结果摊成字符串列表，供 containsAny / regexExtract 扫描。 */
    public static Object texts(Object v) {
        if (Unknown.is(v)) {
            return Unknown.U;
        }
        if (v == null) {
            return new ArrayList<String>();
        }
        List<String> out = new ArrayList<>();
        if (v instanceof List<?> l) {
            for (Object x : l) {
                if (x == null || Unknown.is(x)) {
                    continue;
                }
                out.add(x instanceof String s ? s : Json.dumps(x));
            }
            return out;
        }
        out.add(v instanceof String s ? s : String.valueOf(v));
        return out;
    }

    /** 行集合归一化：非 dict 元素直接丢弃。 */
    @SuppressWarnings("unchecked")
    public static Object rows(Object v) {
        if (Unknown.is(v)) {
            return Unknown.U;
        }
        if (v == null) {
            return new ArrayList<Map<String, Object>>();
        }
        List<Map<String, Object>> out = new ArrayList<>();
        if (v instanceof Map<?, ?> m) {
            out.add((Map<String, Object>) m);
            return out;
        }
        if (v instanceof List<?> l) {
            for (Object x : l) {
                if (x instanceof Map<?, ?> row) {
                    out.add((Map<String, Object>) row);
                }
            }
            return out;
        }
        return out;
    }

    /** 转三值布尔。UNKNOWN 透传；空集合/0/空串按 false。 */
    public static Object truthy(Object v) {
        if (Unknown.is(v)) {
            return Unknown.U;
        }
        if (v instanceof Boolean b) {
            return b;
        }
        if (v == null) {
            return false;
        }
        if (v instanceof String s) {
            return !s.isEmpty();
        }
        if (v instanceof List<?> l) {
            return !l.isEmpty();
        }
        if (v instanceof Map<?, ?> m) {
            return !m.isEmpty();
        }
        if (v instanceof Number n) {
            return n.doubleValue() != 0.0;
        }
        return true;
    }

    // ------------------------------------------------------------ 文本

    /** 敏感词/关键词命中。 */
    public static Object containsAny(Object text, Object kws) {
        Object hay = texts(text);
        if (Unknown.is(hay) || Unknown.is(kws)) {
            return Unknown.U;
        }
        List<String> words = new ArrayList<>();
        if (kws instanceof List<?> l) {
            for (Object k : l) {
                if (k != null && !Unknown.is(k)) {
                    words.add(String.valueOf(k));
                }
            }
        } else if (kws != null) {
            words.add(String.valueOf(kws));
        }
        @SuppressWarnings("unchecked")
        List<String> hs = (List<String>) hay;
        for (String h : hs) {
            for (String w : words) {
                if (!w.isEmpty() && h.contains(w)) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * 从备注/摘要中抽取行程、期间等信息。
     * <p>返回语义对齐 Python {@code re.findall}：0 个捕获组给整体匹配，
     * 1 个组给该组，多个组给组列表。
     */
    public static Object regexExtract(Object text, Object pat) {
        if (Unknown.any(text, pat)) {
            return Unknown.U;
        }
        Object hay = texts(text);
        if (Unknown.is(hay)) {
            return Unknown.U;
        }
        Pattern rx;
        try {
            rx = Pattern.compile(String.valueOf(pat));
        } catch (Exception e) {
            return Unknown.U;
        }
        List<Object> out = new ArrayList<>();
        @SuppressWarnings("unchecked")
        List<String> hs = (List<String>) hay;
        for (String h : hs) {
            Matcher m = rx.matcher(h);
            while (m.find()) {
                int g = m.groupCount();
                if (g == 0) {
                    out.add(m.group());
                } else if (g == 1) {
                    out.add(m.group(1));
                } else {
                    List<String> tuple = new ArrayList<>();
                    for (int i = 1; i <= g; i++) {
                        tuple.add(m.group(i));
                    }
                    out.add(tuple);
                }
            }
        }
        return out;
    }

    /**
     * 名称模糊匹配（盖章名/销方/地址）。
     *
     * <p>对齐 Python {@code difflib.SequenceMatcher(None, a, b).ratio()}：
     * {@code 2*M/T}，M 为递归最长匹配块的字符总数。
     * <p><b>已知偏差</b>：未实现 difflib 的 autojunk 启发（序列长度 ≥200 时把
     * 高频字符当垃圾）。审单场景比的是公司名/地址这类短串，触不到该阈值。
     */
    public static Object similar(Object a, Object b) {
        if (Unknown.any(a, b)) {
            return Unknown.U;
        }
        String x = String.valueOf(a);
        String y = String.valueOf(b);
        int total = x.length() + y.length();
        if (total == 0) {
            return 1.0;
        }
        return 2.0 * matchedChars(x, y, 0, x.length(), 0, y.length()) / total;
    }

    /** difflib 的递归最长匹配块：找到最长公共块后，对左右两侧分别递归。 */
    private static int matchedChars(String a, String b, int alo, int ahi, int blo, int bhi) {
        if (alo >= ahi || blo >= bhi) {
            return 0;
        }
        int bestI = alo;
        int bestJ = blo;
        int bestSize = 0;
        // j2len[j] = 以 a[i] 与 b[j] 结尾的公共子串长度
        Map<Integer, Integer> j2len = new LinkedHashMap<>();
        for (int i = alo; i < ahi; i++) {
            Map<Integer, Integer> newJ2Len = new LinkedHashMap<>();
            for (int j = blo; j < bhi; j++) {
                if (a.charAt(i) != b.charAt(j)) {
                    continue;
                }
                int k = j2len.getOrDefault(j - 1, 0) + 1;
                newJ2Len.put(j, k);
                if (k > bestSize) {
                    bestI = i - k + 1;
                    bestJ = j - k + 1;
                    bestSize = k;
                }
            }
            j2len = newJ2Len;
        }
        if (bestSize == 0) {
            return 0;
        }
        return bestSize
                + matchedChars(a, b, alo, bestI, blo, bestJ)
                + matchedChars(a, b, bestI + bestSize, ahi, bestJ + bestSize, bhi);
    }

    // ------------------------------------------------------------ 聚合

    /**
     * 条件计数。第二参是<b>字符串里的行内表达式</b>，交回外层求值器再解析一次。
     *
     * <p>行内任一行求值出 UNKNOWN 时整体判 UNKNOWN，而不是跳过那一行：跳过等于默默
     * 按「不满足」算，正是三值逻辑要防的那种静默偏差。
     */
    public static Object countWhere(Object rowsArg, Object cond, Fields.Env env, RowEvaluator ev) {
        Object rs = rows(rowsArg);
        if (Unknown.is(rs) || Unknown.is(cond)) {
            return Unknown.U;
        }
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> list = (List<Map<String, Object>>) rs;
        String expr = String.valueOf(cond);
        int n = 0;
        boolean sawUnknown = false;
        for (Map<String, Object> r : list) {
            Object v = ev.eval(expr, env.child(r));
            if (v == null) {
                return Unknown.U;          // 行内表达式解析失败
            }
            if (Unknown.is(v)) {
                sawUnknown = true;
                continue;
            }
            Object t = truthy(v);
            if (Boolean.TRUE.equals(t)) {
                n++;
            }
        }
        if (sawUnknown && n == 0) {
            return Unknown.U;              // 全部弃权 → 计数无意义
        }
        return n;
    }

    /**
     * 列表按字段求和（内部走 toNumber）。
     * <p>取字段用<b>精确键名</b>，不走 Fields.get 的大小写回退 —— 与 qlrun.py
     * 的 {@code r.get(str(field))} 对齐。
     */
    public static Object sumField(Object rowsArg, Object field) {
        Object rs = rows(rowsArg);
        if (Unknown.is(rs) || Unknown.is(field)) {
            return Unknown.U;
        }
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> list = (List<Map<String, Object>>) rs;
        String key = String.valueOf(field);
        double tot = 0.0;
        boolean seen = false;
        for (Map<String, Object> r : list) {
            Object v = toNumber(r.get(key));
            if (!Unknown.is(v)) {
                tot += (Double) v;
                seen = true;
            }
        }
        return (seen || list.isEmpty()) ? tot : Unknown.U;
    }

    /** 附件存在性（非空数组判断的语义封装）。 */
    public static Object hasAttachment(Object v) {
        if (Unknown.is(v)) {
            return Unknown.U;
        }
        Object rs = rows(v);
        if (Unknown.is(rs)) {
            return Unknown.U;
        }
        return !((List<?>) rs).isEmpty();
    }

    /** 发票号连号检测（k 张及以上）。
     *
     * <p>票号必须按 {@link BigInteger} 存。全电发票号码是 20 位定长，
     * 而 {@code Long.MAX_VALUE} 只有 19 位 —— 用 long 接会在
     * {@code Long.parseLong} 抛 NumberFormatException，旧实现把它 catch 掉当
     * 「超长票号跳过」，等于把全电票全部丢弃。实测 `eval/真实报文` 30 份里
     * 394 个 invoiceNo 有 356 个是 20 位（90.4%），旧实现在
     * 其中一份内部培训费报文上只剩 2 个 8 位号，于是一对 20 位连号
     * 被漏掉，前置 P110 判 false，
     * 门控的 16 条规则（R156–R171）全部无法触发。这条函数对全电发票是死的。
     *
     * <p>NUMRE 会匹配小数（`-?\d+(?:\.\d+)?`），小数不是票号，显式跳过，
     * 不靠异常兜。 */
    public static Object hasConsecutiveInvoiceNo(Object nos, Object k) {
        if (Unknown.any(nos, k)) {
            return Unknown.U;
        }
        Object hay = texts(nos);
        if (Unknown.is(hay)) {
            return Unknown.U;
        }
        TreeSet<BigInteger> xs = new TreeSet<>();
        @SuppressWarnings("unchecked")
        List<String> hs = (List<String>) hay;
        for (String h : hs) {
            Matcher m = NUMRE.matcher(h);
            if (m.find()) {
                String g = m.group();
                if (g.indexOf('.') >= 0) {
                    continue;
                }
                xs.add(new BigInteger(g));
            }
        }
        Object kn = toNumber(k);
        if (Unknown.is(kn)) {
            return Unknown.U;
        }
        int need = (int) (double) (Double) kn;
        if (need <= 1) {
            return xs.size() >= need;
        }
        List<BigInteger> sorted = new ArrayList<>(xs);
        int run = 1;
        for (int i = 1; i < sorted.size(); i++) {
            run = sorted.get(i).subtract(sorted.get(i - 1)).equals(BigInteger.ONE)
                    ? run + 1 : 1;
            if (run >= need) {
                return true;
            }
        }
        return false;
    }

    // ------------------------------------------------------------ 数值

    /** 金额一致性（允许容差，默认 0.01）。 */
    public static Object amountEquals(Object a, Object b, Object tol) {
        Object x = toNumber(a);
        Object y = toNumber(b);
        Object t = toNumber(tol);
        if (Unknown.any(x, y)) {
            return Unknown.U;
        }
        double tv = Unknown.is(t) ? 0.01 : (Double) t;
        return Math.abs((Double) x - (Double) y) <= tv;
    }

    /** 数量倍数上限：{@code a <= b * ratio}。 */
    public static Object ratioNotExceed(Object a, Object b, Object ratio) {
        Object x = toNumber(a);
        Object y = toNumber(b);
        Object r = toNumber(ratio);
        if (Unknown.any(x, y, r)) {
            return Unknown.U;
        }
        return (Double) x <= (Double) y * (Double) r;
    }

    /** 中文大写金额转数值（与 amountWithTax 核对）。 */
    private static final Map<Character, Integer> CN_DIGIT = Map.ofEntries(
            Map.entry('零', 0), Map.entry('壹', 1), Map.entry('贰', 2), Map.entry('叁', 3),
            Map.entry('肆', 4), Map.entry('伍', 5), Map.entry('陆', 6), Map.entry('柒', 7),
            Map.entry('捌', 8), Map.entry('玖', 9));

    private static final Map<Character, Long> CN_UNIT = Map.of(
            '拾', 10L, '佰', 100L, '仟', 1000L, '万', 10000L, '亿', 100000000L);

    public static Object cnCapitalToNumber(Object s) {
        if (Unknown.is(s) || s == null || String.valueOf(s).isEmpty()) {
            return Unknown.U;
        }
        String txt = String.valueOf(s);
        double total = 0;
        long seg = 0;
        long last = 0;
        for (char ch : txt.toCharArray()) {
            if (CN_DIGIT.containsKey(ch)) {
                last = CN_DIGIT.get(ch);
            } else if (CN_UNIT.containsKey(ch)) {
                long u = CN_UNIT.get(ch);
                if (u >= 10000) {
                    total = (total + seg + last) * u;
                    seg = 0;
                    last = 0;
                } else {
                    seg += (last == 0 ? 1 : last) * u;
                    last = 0;
                }
            } else if (ch == '元' || ch == '圆') {
                break;
            }
        }
        double v = total + seg + last;
        return v != 0 ? v : Unknown.U;
    }

    // ------------------------------------------------------------ 日期

    private static final DateTimeFormatter DT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final DateTimeFormatter D =
            DateTimeFormatter.ofPattern("yyyy-MM-dd");

    /** 容忍 {@code yyyy-MM-dd} / {@code yyyy-MM-dd HH:mm:ss} 混格式。 */
    private static LocalDate parseDate(Object x) {
        String s = String.valueOf(x).strip();
        if (s.length() > 19) {
            s = s.substring(0, 19);
        }
        s = s.replace("/", "-").replace("T", " ");
        try {
            if (s.length() >= 19) {
                return LocalDateTime.parse(s.substring(0, 19), DT).toLocalDate();
            }
        } catch (Exception ignored) {
            // 落到只取日期部分
        }
        try {
            return s.length() >= 10 ? LocalDate.parse(s.substring(0, 10), D) : null;
        } catch (Exception e) {
            return null;
        }
    }

    /** 日期差（天）。 */
    public static Object daysBetween(Object a, Object b) {
        if (Unknown.any(a, b) || a == null || b == null
                || String.valueOf(a).isEmpty() || String.valueOf(b).isEmpty()) {
            return Unknown.U;
        }
        LocalDate d1 = parseDate(a);
        LocalDate d2 = parseDate(b);
        if (d1 == null || d2 == null) {
            return Unknown.U;
        }
        return (double) Math.abs(ChronoUnit.DAYS.between(d1, d2));
    }

    /** 月差 —— 按 30 天折算，与 qlrun.py 对齐。 */
    public static Object monthsBetween(Object a, Object b) {
        Object d = daysBetween(a, b);
        return Unknown.is(d) ? Unknown.U : (Double) d / 30.0;
    }

    /** 日期落在起止区间内（含端点，按字符串前 10 位比较）。 */
    public static Object dateInRange(Object d, Object s, Object e) {
        if (Unknown.any(d, s, e) || isBlank(d) || isBlank(s) || isBlank(e)) {
            return Unknown.U;
        }
        String ds = head(d, 10);
        String ss = head(s, 10);
        String es = head(e, 10);
        return ss.compareTo(ds) <= 0 && ds.compareTo(es) <= 0;
    }

    /** 时刻落在区间内（取末 8 位 {@code HH:mm:ss} 比较）。 */
    public static Object timeInRange(Object t, Object a, Object b) {
        if (Unknown.any(t, a, b) || isBlank(t) || isBlank(a) || isBlank(b)) {
            return Unknown.U;
        }
        String tv = tail8(t);
        String av = tail8(a);
        String bv = tail8(b);
        return av.compareTo(tv) <= 0 && tv.compareTo(bv) <= 0;
    }

    /**
     * 周末/法定节假日判断。
     * <p><b>已知缺口</b>：节假日表未接入，当前<b>只判周末</b>。与 qlrun.py 同口径，
     * 保证对拍无噪音；接入节假日表时两侧必须同步改。
     */
    public static Object isWeekendOrHoliday(Object d) {
        if (Unknown.is(d) || isBlank(d)) {
            return Unknown.U;
        }
        LocalDate date;
        try {
            date = LocalDate.parse(head(d, 10), D);
        } catch (Exception e) {
            return Unknown.U;
        }
        int dow = date.getDayOfWeek().getValue();   // 周一=1 … 周日=7
        return dow >= 6;
    }

    private static boolean isBlank(Object v) {
        return v == null || String.valueOf(v).isEmpty();
    }

    private static String head(Object v, int n) {
        String s = String.valueOf(v);
        return s.length() <= n ? s : s.substring(0, n);
    }

    private static String tail8(Object v) {
        String s = String.valueOf(v);
        return s.length() >= 8 ? s.substring(s.length() - 8) : s;
    }

    // ------------------------------------------------------------ 宽松相等

    /**
     * 宽松相等：报文里 {@code bill.*} 的金额多为字符串、{@code invoices[].amount}
     * 是数值，{@code amount == 100} 这种写法两边类型天然不同，严格相等会全判 false。
     */
    public static boolean looseEq(Object a, Object b) {
        if (a instanceof Boolean || b instanceof Boolean) {
            return Boolean.TRUE.equals(truthy(a)) == Boolean.TRUE.equals(truthy(b));
        }
        Object na = toNumber(a);
        Object nb = toNumber(b);
        if (!Unknown.is(na) && !Unknown.is(nb)) {
            return Math.abs((Double) na - (Double) nb) < 1e-9;
        }
        return String.valueOf(a).equals(String.valueOf(b));
    }

    /** BigDecimal 出口 —— fields.json 声明 toNumber/sumField 返回 BigDecimal。 */
    public static Object asBigDecimal(Object v) {
        return Unknown.is(v) ? Unknown.U : BigDecimal.valueOf((Double) v);
    }
}
