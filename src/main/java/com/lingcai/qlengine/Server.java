package com.lingcai.qlengine;

import com.fasterxml.jackson.databind.JsonNode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;

/**
 * QL 执行服务。JDK 自带 HttpServer + Jackson，不引 Spring。
 *
 * <p>理由：单端点、无库、无鉴权复杂度。部署目标是共用机器，依赖越少越好 ——
 * 与 rulecraft 本体「第三方依赖只有五个」是同一个取舍。
 *
 * <h2>接口</h2>
 * <pre>
 * GET  /health              存活探针 + 测量版本（引擎、函数白名单哈希、语义取舍），见 {@link Measure#health}
 * GET  /functions           自定义函数白名单（闭集，与 fields.json 对齐）
 * POST /execute             单条 QL
 * POST /execute/batch       一份报文 × 多条 QL（640 条规则跑一单的主路径）
 * POST /compile             只解析不求值：{ql} 或 {rules:[{key,ql}]}
 * </pre>
 *
 * <p>每条结果带 {@code errorCode}（闭集，见 {@link Measure#errorCode}）；单条规则的内部异常记为
 * {@code INTERNAL}，不影响同批其他规则；请求不合法返回 400 与 {@code BAD_REQUEST}。
 */
public final class Server {

    public static final String ENGINE = "qlexpress4-4.1.3+3vl";

    private Server() {
    }

    public static void main(String[] args) throws IOException {
        int port = Integer.parseInt(System.getenv().getOrDefault("QLENGINE_PORT", "7870"));
        String host = System.getenv().getOrDefault("QLENGINE_HOST", "127.0.0.1");

        HttpServer server = HttpServer.create(new InetSocketAddress(host, port), 0);
        server.createContext("/health", Server::health);
        server.createContext("/functions", Server::functions);
        server.createContext("/execute", Server::execute);
        server.createContext("/compile", Server::compile);
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        server.start();
        System.out.println("qlengine listening on " + host + ":" + port + " engine=" + ENGINE);
    }

    // ------------------------------------------------------------ 路由

    private static void health(HttpExchange ex) throws IOException {
        send(ex, 200, Measure.health());
    }

    private static void functions(HttpExchange ex) throws IOException {
        send(ex, 200, Map.of("engine", ENGINE, "functions", Registry.NAMES));
    }

    private static void execute(HttpExchange ex) throws IOException {
        if (!"POST".equalsIgnoreCase(ex.getRequestMethod())) {
            send(ex, 405, Map.of("error", "POST only"));
            return;
        }
        try {
            JsonNode req = Json.M.readTree(ex.getRequestBody().readAllBytes());
            boolean batch = ex.getRequestURI().getPath().endsWith("/batch");
            send(ex, 200, batch ? runBatch(req) : runOne(req));
        } catch (Exception e) {
            send(ex, 400, badRequest(e));
        }
    }

    private static void compile(HttpExchange ex) throws IOException {
        if (!"POST".equalsIgnoreCase(ex.getRequestMethod())) {
            send(ex, 405, Map.of("error", "POST only", "errorCode", "BAD_REQUEST"));
            return;
        }
        try {
            JsonNode req = Json.M.readTree(ex.getRequestBody().readAllBytes());
            if (req.has("rules")) {
                List<Map.Entry<String, String>> rules = new ArrayList<>();
                for (JsonNode item : req.path("rules")) {
                    rules.add(Map.entry(item.path("key").asText(""), item.path("ql").asText("")));
                }
                send(ex, 200, Map.of("engine", ENGINE, "measurementVersion", Measure.measurementVersion(),
                        "results", Measure.compileAll(rules)));
            } else {
                Map<String, Object> out = new LinkedHashMap<>(Measure.compile(req.path("ql").asText("")));
                out.put("engine", ENGINE);
                send(ex, 200, out);
            }
        } catch (Exception e) {
            send(ex, 400, badRequest(e));
        }
    }

    private static Map<String, Object> badRequest(Exception e) {
        return Map.of("error", e.getClass().getSimpleName() + ": " + e.getMessage(), "errorCode", "BAD_REQUEST");
    }

    /** 三值判定写进结果；errorCode 用 put 而非 Map.of，因为它可以为 null。 */
    private static void putVerdict(Map<String, Object> r, String ql, Fields.Env env) {
        try {
            Ql.Verdict v = Ql.run(ql, env);
            r.put("verdict", v.verdict());
            r.put("missing", v.missing());
            r.put("error", v.error());
            r.put("errorCode", Measure.errorCode(v));
        } catch (RuntimeException e) {
            r.put("verdict", null);
            r.put("missing", List.of());
            r.put("error", "internal: " + e.getClass().getSimpleName() + ": " + e.getMessage());
            r.put("errorCode", "INTERNAL");
        }
    }

    // ------------------------------------------------------------ 执行

    @SuppressWarnings("unchecked")
    private static Fields.Env envOf(JsonNode req) {
        Map<String, Object> payload = Json.M.convertValue(
                req.path("payload"), Map.class);
        Map<String, Object> rawFields = req.has("fields")
                ? Json.M.convertValue(req.get("fields"), Map.class) : Map.of();
        Map<String, Object> rawColls = req.has("collections")
                ? Json.M.convertValue(req.get("collections"), Map.class) : Map.of();
        return new Fields.Env(payload == null ? Map.of() : payload,
                Fields.normalizeTable(rawFields),
                Fields.normalizeTable(rawColls));
    }

    private static Map<String, Object> runOne(JsonNode req) {
        String ql = req.path("ql").asText("");
        Fields.Env env = envOf(req);
        String mode = req.path("mode").asText("both");

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("engine", ENGINE);

        if (!mode.equals("native")) {
            putVerdict(out, ql, env);
        }
        if (!mode.equals("3vl")) {
            out.put("native", Native.run(ql, env));
        }
        return out;
    }

    /**
     * 一份报文 × 多条 QL。
     *
     * <p>每条规则用独立 Env —— missing 集合不能串台，否则报告分不清是哪条规则
     * 取不到数。
     */
    private static Map<String, Object> runBatch(JsonNode req) {
        Fields.Env base = envOf(req);
        String mode = req.path("mode").asText("both");
        List<Map<String, Object>> results = new ArrayList<>();

        for (JsonNode item : req.path("rules")) {
            String key = item.path("key").asText(null);
            String ql = item.path("ql").asText("");
            Fields.Env env = new Fields.Env(base.payload, base.fields, base.collections);

            Map<String, Object> r = new LinkedHashMap<>();
            r.put("key", key);
            if (!mode.equals("native")) {
                putVerdict(r, ql, env);
            }
            if (!mode.equals("3vl")) {
                r.put("native", Native.run(ql, env));
            }
            results.add(r);
        }
        return Map.of("engine", ENGINE, "measurementVersion", Measure.measurementVersion(), "results", results);
    }

    // ------------------------------------------------------------ IO

    private static void send(HttpExchange ex, int code, Object body) throws IOException {
        byte[] b = Json.M.writeValueAsBytes(body);
        ex.getResponseHeaders().add("Content-Type", "application/json; charset=utf-8");
        ex.sendResponseHeaders(code, b.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(b);
        }
    }

    static String utf8(byte[] b) {
        return new String(b, StandardCharsets.UTF_8);
    }
}
