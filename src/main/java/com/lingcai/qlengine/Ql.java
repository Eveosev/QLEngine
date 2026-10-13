package com.lingcai.qlengine;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * QL 三值求值器：词法 → Pratt 语法 → 三值求值。
 *
 * <p>与 {@code lingcai_new/qlrun.py} 是语义孪生 —— 同一套文法、同一套三值规则、
 * 同一套函数（{@link Fn}）。它存在的意义不是「又写一个子集求值器」，而是让
 * <b>三值诊断</b>能力在服务侧成立：真 QLExpress 没有 UNKNOWN，取不到数要么抛异常
 * 要么给 null，那样就丢掉了「哪些字段别名在真实报文里不存在」这个最有价值的产出。
 *
 * <p>服务同时跑这一路和 {@link Native} 的真 QLExpress，两者的差异本身就是信息。
 *
 * <h2>为什么只做子集</h2>
 * 统计过 640 条已生成的 QL：三元 {@code ?:} 0 个、赋值 {@code =} 0 个、语句分隔
 * {@code ;} 0 个、多行 0 个、{@code in} 运算符 0 个、成员访问 {@code a.b} 0 个。
 * 产出的 QL 全是纯布尔表达式 —— 因为 knowledge/ql-conventions.md 就是这么约束
 * 模型的。所以文法只需要「或/与/比较/加减乘除/一元/原子」六层。
 */
public final class Ql {

    // ------------------------------------------------------------ AST

    public sealed interface Node
            permits Lit, Arr, Var, Call, Not, Neg, And, Or, Cond, Cmp, Bin {
    }

    public record Lit(Object v) implements Node {
    }

    public record Arr(List<Node> items) implements Node {
    }

    public record Var(String name) implements Node {
    }

    public record Call(String name, List<Node> args) implements Node {
    }

    public record Not(Node e) implements Node {
    }

    public record Neg(Node e) implements Node {
    }

    public record And(Node l, Node r) implements Node {
    }

    public record Or(Node l, Node r) implements Node {
    }

    /**
     * 三元 {@code c ? a : b}。
     *
     * <p>差旅标准按职级分档的规则写成嵌套三元
     * （{@code reimburseLevel <= 7 ? 300 : (... ? 200 : 150)}），没有它这类规则整条解析不了。
     * qlrun.py 的统计口径曾认定「0 处三元」，那份样本已经过时。
     */
    public record Cond(Node c, Node a, Node b) implements Node {
    }

    public record Cmp(String op, Node l, Node r) implements Node {
    }

    public record Bin(String op, Node l, Node r) implements Node {
    }

    public static final class SyntaxError extends RuntimeException {
        public SyntaxError(String msg) {
            super(msg);
        }
    }

    // ------------------------------------------------------------ 词法

    private record Tok(String kind, Object val) {
    }

    private static final Pattern NUM = Pattern.compile("\\d+(?:\\.\\d+)?");
    private static final Pattern IDENT = Pattern.compile("[A-Za-z_][A-Za-z_0-9]*");
    /** 长的在前 —— {@code >=} 不能被 {@code >} 抢先。 */
    private static final String[] OPS = {
            "&&", "||", "==", "!=", ">=", "<=", ">", "<",
            "+", "-", "*", "/", "!", "(", ")", "[", "]", ",", "?", ":"};

    /** 切 token。字符串字面量保留原文（countWhere 的第二参要拿它再解析一次）。 */
    static List<Tok> lex(String src) {
        List<Tok> out = new ArrayList<>();
        int i = 0;
        int n = src.length();
        while (i < n) {
            char c = src.charAt(i);
            if (c == ' ' || c == '\t' || c == '\r' || c == '\n') {
                i++;
                continue;
            }
            if (c == '\'' || c == '"') {                 // 同引号闭合，支持 \" 转义
                StringBuilder buf = new StringBuilder();
                int j = i + 1;
                while (j < n && src.charAt(j) != c) {
                    if (src.charAt(j) == '\\' && j + 1 < n) {
                        buf.append(src.charAt(j + 1));
                        j += 2;
                        continue;
                    }
                    buf.append(src.charAt(j));
                    j++;
                }
                if (j >= n) {
                    throw new SyntaxError("字符串未闭合 @" + i);
                }
                out.add(new Tok("str", buf.toString()));
                i = j + 1;
                continue;
            }
            Matcher mn = NUM.matcher(src);
            if (mn.find(i) && mn.start() == i) {
                String t = mn.group();
                out.add(new Tok("num", Double.parseDouble(t)));
                i = mn.end();
                continue;
            }
            Matcher mi = IDENT.matcher(src);
            if (mi.find(i) && mi.start() == i) {
                String w = mi.group();
                if (w.equals("true") || w.equals("false")) {
                    out.add(new Tok("bool", w.equals("true")));
                } else if (w.equals("null") || w.equals("nil")) {
                    out.add(new Tok("null", null));
                } else {
                    out.add(new Tok("ident", w));
                }
                i = mi.end();
                continue;
            }
            boolean matched = false;
            for (String op : OPS) {
                if (src.startsWith(op, i)) {
                    out.add(new Tok("op", op));
                    i += op.length();
                    matched = true;
                    break;
                }
            }
            if (!matched) {
                throw new SyntaxError("无法识别的字符 '" + c + "' @" + i);
            }
        }
        out.add(new Tok("eof", null));
        return out;
    }

    // ------------------------------------------------------------ 语法
    //
    // expr := or
    // or   := and ('||' and)*      and  := cmp ('&&' cmp)*
    // cmp  := add (CMPOP add)*     add  := mul (('+'|'-') mul)*
    // mul  := unary (('*'|'/') unary)*
    // unary := ('!'|'-') unary | primary
    // primary := num | str | bool | null | '[' args ']' | ident '(' args ')'
    //          | ident | '(' expr ')'

    private static final List<String> CMP_OPS =
            List.of("==", "!=", ">=", "<=", ">", "<");

    private static final class Parser {
        private final List<Tok> t;
        private int i = 0;

        Parser(List<Tok> toks) {
            this.t = toks;
        }

        private Tok peek() {
            return t.get(i);
        }

        private boolean is(String op) {
            Tok tk = peek();
            return tk.kind().equals("op") && op.equals(tk.val());
        }

        private Object eat() {
            return t.get(i++).val();
        }

        private void expect(String op) {
            if (!is(op)) {
                throw new SyntaxError("期望 " + op + "，实际 " + peek().val() + " @tok" + i);
            }
            i++;
        }

        Node parse() {
            Node node = pTernary();
            if (!peek().kind().equals("eof")) {
                throw new SyntaxError("表达式末尾有多余内容 @tok" + i);
            }
            return node;
        }

        /** 右结合：{@code a ? b : c ? d : e} 读作 {@code a ? b : (c ? d : e)}。 */
        private Node pTernary() {
            Node cond = pOr();
            if (!is("?")) {
                return cond;
            }
            eat();
            Node a = pTernary();
            if (!is(":")) {
                throw new SyntaxError("三元缺少 ':' @tok" + i);
            }
            eat();
            return new Cond(cond, a, pTernary());
        }

        private Node pOr() {
            Node left = pAnd();
            while (is("||")) {
                eat();
                left = new Or(left, pAnd());
            }
            return left;
        }

        private Node pAnd() {
            Node left = pCmp();
            while (is("&&")) {
                eat();
                left = new And(left, pCmp());
            }
            return left;
        }

        private Node pCmp() {
            Node left = pAdd();
            while (peek().kind().equals("op") && CMP_OPS.contains(String.valueOf(peek().val()))) {
                String op = String.valueOf(eat());
                left = new Cmp(op, left, pAdd());
            }
            return left;
        }

        private Node pAdd() {
            Node left = pMul();
            while (is("+") || is("-")) {
                String op = String.valueOf(eat());
                left = new Bin(op, left, pMul());
            }
            return left;
        }

        private Node pMul() {
            Node left = pUnary();
            while (is("*") || is("/")) {
                String op = String.valueOf(eat());
                left = new Bin(op, left, pUnary());
            }
            return left;
        }

        private Node pUnary() {
            if (is("!")) {
                eat();
                return new Not(pUnary());
            }
            if (is("-")) {
                eat();
                return new Neg(pUnary());
            }
            return pPrimary();
        }

        private Node pPrimary() {
            Tok tk = peek();
            String k = tk.kind();
            if (k.equals("num") || k.equals("str") || k.equals("bool") || k.equals("null")) {
                return new Lit(eat());
            }
            if (is("(")) {
                eat();
                Node node = pTernary();
                expect(")");
                return node;
            }
            if (is("[")) {
                eat();
                List<Node> items = new ArrayList<>();
                if (!is("]")) {
                    items.add(pTernary());
                    while (is(",")) {
                        eat();
                        items.add(pTernary());
                    }
                }
                expect("]");
                return new Arr(items);
            }
            if (k.equals("ident")) {
                String name = String.valueOf(eat());
                if (is("(")) {
                    eat();
                    List<Node> args = new ArrayList<>();
                    if (!is(")")) {
                        args.add(pTernary());
                        while (is(",")) {
                            eat();
                            args.add(pTernary());
                        }
                    }
                    expect(")");
                    return new Call(name, args);
                }
                return new Var(name);
            }
            throw new SyntaxError("无法解析的 token " + tk.val() + " @tok" + i);
        }
    }

    public static Node parse(String src) {
        return new Parser(lex(src)).parse();
    }

    // ------------------------------------------------------------ 求值

    /** countWhere 回调：把行内表达式串在行上下文里跑一遍。解析失败返回 null。 */
    private static final Fn.RowEvaluator ROW_EVAL = (expr, rowEnv) -> {
        try {
            return evaluate(parse(expr), rowEnv);
        } catch (SyntaxError e) {
            return null;
        }
    };

    public static Object evaluate(Node node, Fields.Env env) {
        return switch (node) {
            case Lit l -> l.v();
            case Arr a -> {
                List<Object> out = new ArrayList<>();
                for (Node x : a.items()) {
                    out.add(evaluate(x, env));
                }
                yield out;
            }
            case Var v -> env.lookup(v.name());
            case Call c -> call(c, env);
            case Not nt -> {
                Object v = Fn.truthy(evaluate(nt.e(), env));
                yield Unknown.is(v) ? Unknown.U : !((Boolean) v);
            }
            case Neg ng -> {
                Object v = Fn.toNumber(evaluate(ng.e(), env));
                yield Unknown.is(v) ? Unknown.U : -((Double) v);
            }
            // 短路仍能定值：UNKNOWN && FALSE = FALSE（无论未知的那边是什么都是假）
            case And an -> {
                Object a = Fn.truthy(evaluate(an.l(), env));
                if (Boolean.FALSE.equals(a)) {
                    yield false;
                }
                Object b = Fn.truthy(evaluate(an.r(), env));
                if (Boolean.FALSE.equals(b)) {
                    yield false;
                }
                yield (Unknown.is(a) || Unknown.is(b)) ? Unknown.U : true;
            }
            case Cond cn -> {
                Object c = Fn.truthy(evaluate(cn.c(), env));
                // 条件未知则整体未知 —— 两个分支的值可能天差地别，不能挑一个当答案
                yield Unknown.is(c) ? Unknown.U
                        : Boolean.TRUE.equals(c) ? evaluate(cn.a(), env)
                        : evaluate(cn.b(), env);
            }
            case Or or -> {
                Object a = Fn.truthy(evaluate(or.l(), env));
                if (Boolean.TRUE.equals(a)) {
                    yield true;
                }
                Object b = Fn.truthy(evaluate(or.r(), env));
                if (Boolean.TRUE.equals(b)) {
                    yield true;
                }
                yield (Unknown.is(a) || Unknown.is(b)) ? Unknown.U : false;
            }
            case Cmp cm -> {
                Object l = evaluate(cm.l(), env);
                Object r = evaluate(cm.r(), env);
                if (Unknown.any(l, r)) {
                    yield Unknown.U;
                }
                if (cm.op().equals("==") || cm.op().equals("!=")) {
                    boolean eq = Fn.looseEq(l, r);
                    yield cm.op().equals("==") ? eq : !eq;
                }
                Object ln = Fn.toNumber(l);
                Object rn = Fn.toNumber(r);
                if (Unknown.any(ln, rn)) {
                    yield Unknown.U;
                }
                double x = (Double) ln;
                double y = (Double) rn;
                yield switch (cm.op()) {
                    case ">" -> x > y;
                    case "<" -> x < y;
                    case ">=" -> x >= y;
                    case "<=" -> x <= y;
                    default -> Unknown.U;
                };
            }
            case Bin bn -> {
                Object l = Fn.toNumber(evaluate(bn.l(), env));
                Object r = Fn.toNumber(evaluate(bn.r(), env));
                if (Unknown.any(l, r)) {
                    yield Unknown.U;
                }
                double x = (Double) l;
                double y = (Double) r;
                if (bn.op().equals("/") && y == 0) {
                    yield Unknown.U;
                }
                yield switch (bn.op()) {
                    case "+" -> x + y;
                    case "-" -> x - y;
                    case "*" -> x * y;
                    case "/" -> x / y;
                    default -> Unknown.U;
                };
            }
        };
    }

    private static Object call(Call c, Fields.Env env) {
        String name = c.name();
        List<Node> args = c.args();

        // countWhere 的第二参必须保持字符串原文，不能先求值（它是行内表达式）
        if (name.equals("countWhere") && args.size() >= 2 && args.get(1) instanceof Lit lit) {
            return Fn.countWhere(evaluate(args.get(0), env), lit.v(), env, ROW_EVAL);
        }

        List<Object> a = new ArrayList<>();
        for (Node x : args) {
            a.add(evaluate(x, env));
        }
        int n = a.size();
        return switch (name) {
            case "containsAny" -> n >= 2 ? Fn.containsAny(a.get(0), a.get(1)) : Unknown.U;
            case "countWhere" -> n >= 2
                    ? Fn.countWhere(a.get(0), a.get(1), env, ROW_EVAL) : Unknown.U;
            case "toNumber" -> n >= 1 ? Fn.toNumber(a.get(0)) : Unknown.U;
            case "hasAttachment" -> n >= 1 ? Fn.hasAttachment(a.get(0)) : Unknown.U;
            case "sumField" -> n >= 2 ? Fn.sumField(a.get(0), a.get(1)) : Unknown.U;
            case "amountEquals" -> n >= 2
                    ? Fn.amountEquals(a.get(0), a.get(1), n >= 3 ? a.get(2) : null) : Unknown.U;
            case "daysBetween" -> n >= 2 ? Fn.daysBetween(a.get(0), a.get(1)) : Unknown.U;
            case "monthsBetween" -> n >= 2 ? Fn.monthsBetween(a.get(0), a.get(1)) : Unknown.U;
            case "dateInRange" -> n >= 3
                    ? Fn.dateInRange(a.get(0), a.get(1), a.get(2)) : Unknown.U;
            case "timeInRange" -> n >= 3
                    ? Fn.timeInRange(a.get(0), a.get(1), a.get(2)) : Unknown.U;
            case "similar", "fuzzyMatch" -> n >= 2 ? Fn.similar(a.get(0), a.get(1)) : Unknown.U;
            case "ratioNotExceed" -> n >= 3
                    ? Fn.ratioNotExceed(a.get(0), a.get(1), a.get(2)) : Unknown.U;
            case "regexExtract" -> n >= 2 ? Fn.regexExtract(a.get(0), a.get(1)) : Unknown.U;
            case "hasConsecutiveInvoiceNo" -> n >= 2
                    ? Fn.hasConsecutiveInvoiceNo(a.get(0), a.get(1)) : Unknown.U;
            case "cnCapitalToNumber" -> n >= 1 ? Fn.cnCapitalToNumber(a.get(0)) : Unknown.U;
            case "isWeekendOrHoliday" -> n >= 1 ? Fn.isWeekendOrHoliday(a.get(0)) : Unknown.U;
            default -> Unknown.U;          // 未知函数：弃权，不猜
        };
    }

    // ------------------------------------------------------------ 对外入口

    /** 一条 QL 在一份报文上的三值判定结果。 */
    public record Verdict(Boolean verdict, List<String> missing, String error) {
    }

    /**
     * 跑一条 QL。
     *
     * @return verdict: true 命中（断言成立）/ false 不命中 / null 弃权（取不到数）
     */
    public static Verdict run(String ql, Fields.Env env) {
        if (ql == null || ql.isBlank()) {
            return new Verdict(null, List.of(), "empty");
        }
        Object v;
        try {
            v = Fn.truthy(evaluate(parse(ql), env));
        } catch (SyntaxError e) {
            return new Verdict(null, List.of(), "parse: " + e.getMessage());
        } catch (RuntimeException e) {
            return new Verdict(null, new ArrayList<>(env.missing),
                    "eval: " + e.getClass().getSimpleName() + ": " + e.getMessage());
        }
        return new Verdict(Unknown.is(v) ? null : (Boolean) v,
                new ArrayList<>(env.missing), null);
    }
}
