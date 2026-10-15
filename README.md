# QLEngine

把 [alibaba/QLExpress](https://github.com/alibaba/QLExpress)（QLExpress4）包成 HTTP 服务，并在旁边跑一条自写的三值（TRUE / FALSE / UNKNOWN）求值路径。用途是在真实报文上回放「制度 → 规则」抽取出的 QL 规则：
- 回答「线上引擎会怎么判」；
- 回答「哪些规则因为取不到字段而判不了」。

在 RuleCraft 自进化框架里，它还承担**测量服务**的角色：规则改进的好坏以它的读数为准。所以它会报告自己的测量版本和错误分类。

- 运行时：Java 21（用到虚拟线程）。第三方依赖两个：`com.alibaba:qlexpress4:4.1.3`、`jackson-databind`。
- 规模：Java 约 1950 行，测试 3 组，对拍工具 3 个（Python）。

## 与 QLExpress 的关系

本仓**不是** QLExpress 的 fork，不含它的源码。QLExpress 只作为 Maven 依赖引入：

```xml
<dependency>
  <groupId>com.alibaba</groupId>
  <artifactId>qlexpress4</artifactId>
  <version>4.1.3</version>       <!-- pom.xml 的 qlexpress.version -->
</dependency>
```

| | QLExpress4 | 本仓补上的部分 |
|---|---|---|
| 形态 | 嵌入式 Java 脚本引擎（库） | 独立 HTTP 服务（`Server.java`），JDK 自带 `HttpServer` + 虚拟线程，不引 Spring |
| 缺字段 | 没有 UNKNOWN：取不到时给 null，`countWhere(...) >= 1` 数出 0，规则「触发」，合规单被误拦 | `Ql.java` 三值求值器：缺字段向上传播为 UNKNOWN，并报告缺了哪些字段 |
| 业务函数 | 不含 | `Fn.java` 实现 17 个白名单函数，同时注册给两条路径，两边语义同源 |
| 取数 | 由调用方传入上下文 | `Fields.java` 把字段别名解析成报文 jsonpath，带大小写 / 下划线归一回退与多路径回退 |
| 读数可信 | 不涉及 | `Measure.java`：测量版本、错误码闭集、只编译接口 |

**接触面只有一个文件。** `Native.java` 是唯一 import `com.alibaba.qlexpress4.*` 的类，用到的 API：
- `Express4Runner`、`InitOptions`、`QLOptions`、`QLResult`；
- `runtime.Parameters`；
- `security.QLSecurityStrategy`。

引擎的配置都是刻意选的：

| 选项 | 取值 | 原因 |
|---|---|---|
| `securityStrategy` | `QLSecurityStrategy.isolation()` | 执行的是模型抽取出的 QL，不受信任。禁止一切 Java 字段 / 方法访问，能力只经白名单函数暴露 |
| `avoidNullPointer` | `true` | 空字段不抛 NPE。缺字段由三值路径单独报告 |
| `timeoutMillis` | `1000` | 防跑飞的脚本 |
| `precise` | `true` | 金额计算走 BigDecimal |
| `cache` | `true` | 同一条 QL 只编译一次 |

**升级 QLExpress** 的步骤：
1. 改 `pom.xml` 的 `qlexpress.version` 和 `Server.ENGINE`；
2. 签名有变化时只改 `Native.java`；
3. 跑 `mvn package`；
4. 用 `tools/diff_check.py` 重新对拍。

引擎版本是测量版本的一部分，升级后调用方会把旧读数视为另一版本（见下文「测量版本」）。

**许可证。** QLExpress 使用 Apache License 2.0。`mvn package` 产出的 fat jar（maven-shade）把 QLExpress 和 Jackson 打了进去。对外分发这个 jar 时，需要随附它们的 LICENSE / NOTICE。本仓自身的许可证尚未声明。

## 实现

```
请求 ──► Server ──► Fields.Env（报文 + 字段表 + 行集合表）
                     ├─► Ql（3vl）：词法 → Pratt 语法 → 三值求值 ──► verdict / missing / error / errorCode
                     └─► Native（真 QLExpress4）：同一份 Env、同一套 Fn ──► result / error
```

两条路径一起跑（`mode=both`，缺省），**两者的差异就是产出**：

| 3vl | native | 含义 |
|---|---|---|
| UNKNOWN | 报错 / null | 字段取不到，符合预期 |
| UNKNOWN | false | 真引擎把缺字段当成「断言不成立」，会造成反向误报。这正是 UNKNOWN 存在的理由 |
| 布尔 A | 布尔 B（A≠B） | 真问题：`Fn` 移植有偏差，或宽松相等（`Fn.looseEq`）与 QLExpress 原生相等的语义不同 |

规则约定：**true = 断言成立 = 合规；false = 触发 = 拦截。**

| 文件 | 职责 |
|---|---|
| `Server.java` | 路由、请求解析、批量执行。批量时每条规则用独立的 Env，missing 集合不串台；单条规则内部异常记为 `INTERNAL`，不影响同批其他规则 |
| `Ql.java` | 三值求值器。文法只覆盖「或 / 与 / 比较 / 加减乘除 / 一元 / 原子」六层，因为统计 640 条生成的 QL，三元、赋值、语句分隔、`in`、成员访问都是 0 次。它是 Python 求值器 `qlrun.py`（RuleCraft 仓）的语义孪生，两者逐条对拍一致 |
| `Native.java` | QLExpress4 路径（见上节）。`countWhere` 的行内表达式交回 QLExpress 在行上下文里再跑一遍，行上下文与 3vl 同源 |
| `Fn.java` | 17 个白名单函数的实现，两条路径共用 |
| `Registry.java` | 函数白名单（规范名 + 别名）。与 RuleCraft 字段库 `fields.json` 的 functions 段一一对应，加函数时两处一起改 |
| `Fields.java` | 取数层。别名 → jsonpath；`[]` / `[*]` / `[?(...)]` 都表示展开一层数组；字段名做归一后完全相等的回退（`fileName` ≈ `filename`），不做模糊匹配 |
| `Unknown.java` | UNKNOWN 哨兵值及其传播规则 |
| `Measure.java` | 测量版本、错误码、只编译 |
| `Json.java` | Jackson 单例 |

白名单函数：`toNumber` `daysBetween` `monthsBetween`（`daysBetween` 的别名）`isWeekendOrHoliday` `timeInRange` `dateInRange` `sumField` `countWhere` `hasConsecutiveInvoiceNo` `containsAny` `amountEquals` `similar` `fuzzyMatch`（`similar` 的别名）`hasAttachment` `ratioNotExceed` `regexExtract` `cnCapitalToNumber`。

已知的语义取舍（写进测量版本）：

| 项 | 取值 | 含义 |
|---|---|---|
| `numeric` | `double` | 三值路径的数值一律用 double，以便与 `qlrun.py` 逐条对拍。native 路径为 `precise=true`，金额走 BigDecimal |
| `isWeekendOrHoliday` | `weekend-only` | 节假日表未接，只判周末 |
| `unknownFunction` | `abstain` | 未知函数弃权，不报错 |

## 接口

| 方法 | 路径 | 请求 | 返回 |
|---|---|---|---|
| GET | `/health` | — | `ok`、`engine`、`api`（`qlengine.measure/1`）、`measurementVersion`、`functionsHash`、`functions`、`semantics` |
| GET | `/functions` | — | 函数白名单 |
| POST | `/execute` | `{ql, payload, fields?, collections?, mode?}` | `verdict`、`missing`、`error`、`errorCode`、`native` |
| POST | `/execute/batch` | `{payload, fields?, collections?, mode?, rules:[{key, ql}]}` | `measurementVersion`、`results[]`（每条同 `/execute`，另加 `key`） |
| POST | `/compile` | `{ql}` 或 `{rules:[{key, ql}]}` | `ok`、`errorCode`、`aliases`（QL 引用到的字段别名，是下界） |

- `mode` 取 `both`（缺省）、`3vl` 或 `native`。
- `fields`：别名 → jsonpath 列表；`collections`：行集合别名 → 容器路径。两者都来自 RuleCraft 的字段表。
- 几百条规则跑一张单时请走 `/execute/batch`，不要循环调 `/execute`，否则报文会被重复传几百遍。

`errorCode` 是闭集：

| 值 | 含义 |
|---|---|
| `null` | 有明确判定 |
| `MISSING` | 取不到字段，结果为 UNKNOWN |
| `ABSTAIN` | 不缺字段，但给不出布尔值（如未知函数） |
| `PARSE` | 文法错误 |
| `EMPTY` | QL 为空 |
| `EVAL` | 求值时抛错 |
| `INTERNAL` | 服务内部异常（单条隔离） |
| `BAD_REQUEST` | 请求不合法（HTTP 400） |

**测量版本**：`<引擎>#<sha256(引擎 | 函数白名单哈希 | 语义取舍) 前 12 位>`，例如 `qlexpress4-4.1.3+3vl#7318b8b3411f`。引擎、函数白名单、语义取舍三者任一变化，版本号就变。调用方把它钉进评测协议；版本不一致的读数不能互相比较。

## 构建与运行

```bash
./run.sh build      # mvn -B package（含测试）→ target/qlengine-0.1.0-SNAPSHOT.jar（约 2.8MB fat jar）
./run.sh start      # 后台启动，缺省 127.0.0.1:7870；QLENGINE_HOST / QLENGINE_PORT 可改，日志 QLENGINE_LOG（缺省 /tmp/qlengine.log）
./run.sh status     # /health 与函数白名单
./run.sh stop
```

`run.sh` 缺省使用 `/opt/homebrew/opt/openjdk`（macOS，Homebrew 的 openjdk 是 keg-only），其他环境设 `JAVA_HOME`。

**测试**：`mvn test` 跑三组。
- `QlTest`：文法与三值规则；
- `FnTest`：函数，含 20 位全电发票号；
- `MeasureTest`：错误码、测量版本、只编译。

**对拍工具**（`tools/`，需要服务在跑，需要 RuleCraft 仓 `lingcai_new` 与本仓同在一个父目录下）：
- `diff_check.py`：三路对拍。`qlrun.py`（Python）× 3vl（Java）× native（QLExpress）。前两者必须逐条一致；3vl 与 native 的差异量化了「接真引擎会多出多少拦截」。
- `qlengine_client.py`：Python 客户端（`QlEngine().batch(...)`），供 RuleCraft 的样本检查 L3 层调用。
- `replay_l3.py`：L3 判定回放。数「审批通过」的单据上有多少规则触发（即假阳），按 native、3vl-loose、3vl-strict 三个口径统计。

规则包与报文属于客户数据，不放在本仓，由环境变量给出：

```bash
QLENGINE_RULE_DIRS=<规则包目录>[:<规则包目录>…] QLENGINE_PAYLOADS=<报文目录> python3 tools/diff_check.py --json out/diff.json
```

规则包目录里要有 `rules_ql.json`（`replay_l3.py` 还读 `preconditions_ql.json`）。

## 部署

`deploy/deploy.sh` 把服务部署到一台 Linux 主机，用 systemd 管理。

- 远端只监听 `127.0.0.1:7870`，因为服务**没有鉴权**；本机经 SSH 隧道访问。
- 不依赖系统 Java：从清华镜像下载 Temurin JRE 21 到 `/opt/qlengine/jre-*`，按 Adoptium 官方 sha256 校验。
- JVM 参数为 `-Xmx128m`、SerialGC、C1，systemd `MemoryMax=256M`。实测：10 张单全量度量，峰值 RSS 约 49MB。
- 每个 release 放在 `/opt/qlengine/releases/<commit>/`，`current` / `previous` 两个软链用于回滚。
- 部署后核对远端 `/health` 的测量版本与本机构建一致，不一致自动回滚。

```bash
QLENGINE_REMOTE=<ssh 主机名> deploy/deploy.sh preflight   # 只读检查
QLENGINE_REMOTE=<ssh 主机名> deploy/deploy.sh install
QLENGINE_REMOTE=<ssh 主机名> deploy/deploy.sh status | rollback
LOCAL_PORT=17870 deploy/deploy.sh tunnel                  # 本机 127.0.0.1:17870 → 远端 127.0.0.1:7870
```

## 已知限制

- 没有鉴权与 TLS，只应监听本机或内网。
- 三值路径只覆盖纯布尔表达式子集。超出子集的 QL 记为 `PARSE`，native 路径仍会求值。
- `isWeekendOrHoliday` 未接节假日表。
- 三值路径数值用 double，与字段库声明的 BigDecimal 不一致（刻意保留，见上文）。
