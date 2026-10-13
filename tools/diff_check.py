"""对拍：qlrun.py（Python 子集） × qlengine-3vl（Java 子集） × qlengine-native（真 QLExpress）。

    QLENGINE_RULE_DIRS=… QLENGINE_PAYLOADS=… python3 tools/diff_check.py [--limit-rules N] [--json out.json]

## 为什么要三路对拍

qlengine 的三值路径是 qlrun.py 的语义孪生 —— 两者**必须**逐条一致，不一致就是
Java 移植出了偏差，这是回归测试。native 路径是真引擎，它与三值路径的差异不是
bug 而是信息：

    native 报错 / null，3vl 给 UNKNOWN      字段取不到，符合预期
    两者都给布尔但不同                       真问题：要么 Fn 移植错了，要么
                                            looseEq 与 QLExpress 原生相等语义不同
    3vl 给 UNKNOWN，native 给 false          正是 UNKNOWN 存在的理由 —— 缺字段被
                                            真引擎当成「断言不成立」，会反向误报

最后一类是本对拍最想量化的东西：它直接等于「接真引擎后会凭空多出多少条拦截」。
"""
from __future__ import annotations

import argparse
import collections
import json
import os
import sys
import urllib.request
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
LINGCAI = ROOT / "lingcai_new"
sys.path.insert(0, str(LINGCAI))

import qlrun  # noqa: E402

ENDPOINT = "http://127.0.0.1:7870/execute/batch"

# 规则包目录与报文目录由调用方给，客户数据不进本仓：
#   QLENGINE_RULE_DIRS=<规则包目录>[:<规则包目录>…]  QLENGINE_PAYLOADS=<报文目录>
RULE_DIRS = [Path(p) for p in os.environ.get("QLENGINE_RULE_DIRS", "").split(os.pathsep) if p]
PAYLOAD_DIR = Path(os.environ.get("QLENGINE_PAYLOADS") or "/nonexistent")


def field_tables() -> tuple[dict, dict]:
    """字段别名 → 路径列表，以及行集合别名 → 容器路径。与 sample_check.py 同口径。"""
    from fields import FIELDS
    import assets_loader as assets

    fields = {a: [p] for a, (p, _d) in FIELDS.items()}
    fields.update(assets.paths_for_var())
    return fields, assets.collection_vars()


def load_rules(limit: int = 0) -> list[dict]:
    out = []
    for d in RULE_DIRS:
        f = d / "rules_ql.json"
        if not f.exists():
            continue
        for r in json.loads(f.read_text(encoding="utf-8")):
            ql = (r.get("ql") or "").strip()
            if ql:
                out.append({"key": f"{d.name}::{r.get('id', '')}", "ql": ql})
    # 同一条 QL 在不同制度里重复出现很常见，去重省掉一半调用
    seen, uniq = set(), []
    for r in out:
        if r["ql"] not in seen:
            seen.add(r["ql"])
            uniq.append(r)
    return uniq[:limit] if limit else uniq


def load_payloads() -> list[tuple[str, dict]]:
    out = []
    for f in sorted(PAYLOAD_DIR.rglob("*.txt")):
        try:
            out.append((f"{f.parent.name}/{f.stem}", json.loads(f.read_text(encoding="utf-8"))))
        except Exception as e:
            print(f"  跳过 {f.name}: {e}")
    return out


def post(body: dict) -> dict:
    req = urllib.request.Request(
        ENDPOINT, data=json.dumps(body, ensure_ascii=False).encode("utf-8"),
        headers={"Content-Type": "application/json"})
    with urllib.request.urlopen(req, timeout=300) as r:
        return json.loads(r.read().decode("utf-8"))


def norm(v) -> str:
    """把三路的结果压到同一个可比较的标签上。"""
    if v is None:
        return "UNKNOWN"
    if v is True:
        return "TRUE"
    if v is False:
        return "FALSE"
    return f"VAL({v})"


def native_label(n: dict) -> str:
    """native 没有三值 —— 报错单列一档，非布尔结果也单列，不硬塞成真假。"""
    if n.get("error"):
        return "ERROR"
    r = n.get("result")
    if isinstance(r, bool):
        return "TRUE" if r else "FALSE"
    if r is None:
        return "NULL"
    return f"VAL({r})"


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--limit-rules", type=int, default=0)
    ap.add_argument("--limit-payloads", type=int, default=0)
    ap.add_argument("--json", default="")
    args = ap.parse_args()
    if not RULE_DIRS or not PAYLOAD_DIR.is_dir():
        raise SystemExit("先设置 QLENGINE_RULE_DIRS（规则包目录，含 rules_ql.json）与 QLENGINE_PAYLOADS（报文目录）")

    fields, colls = field_tables()
    rules = load_rules(args.limit_rules)
    payloads = load_payloads()
    if args.limit_payloads:
        payloads = payloads[: args.limit_payloads]
    print(f"规则 {len(rules)} 条（已去重）× 报文 {len(payloads)} 份 = "
          f"{len(rules) * len(payloads)} 次求值\n")

    parity = collections.Counter()          # 3vl vs qlrun：除已知升级差外必须全 same
    matrix = collections.Counter()          # 3vl × native 的联合分布
    mismatches, native_gaps = [], []
    parse_gaps: dict[str, str] = {}        # 3vl 解析不了、真引擎跑得通的 QL
    real_diffs = []                        # 两边都给布尔却不同 —— 移植偏差
    upgrades: dict[str, str] = {}          # Java 已支持、qlrun.py 尚未支持的文法
    gap_fields: collections.Counter = collections.Counter()   # 反向误报按字段聚类
    gap_blind = 0                          # 反向误报但一个缺失字段都报不出来

    for pid, payload in payloads:
        resp = post({"payload": payload, "fields": fields,
                     "collections": colls, "mode": "both",
                     "rules": rules})
        by_key = {r["key"]: r for r in resp["results"]}

        for rule in rules:
            j = by_key[rule["key"]]
            p = qlrun.run(rule["ql"], payload, fields, colls)

            jl = "ERROR" if j.get("error") else norm(j.get("verdict"))
            pl = "ERROR" if p.get("error") else norm(p.get("verdict"))
            if jl == pl:
                parity["same"] += 1
            elif pl == "ERROR" and jl != "ERROR" and "parse" in (p.get("error") or ""):
                # 已知升级差：Java 补了三元，qlrun.py 还没有。形状单一，单列一档，
                # 不混进一致性信号里 —— 否则真的移植偏差会被这 180 条淹掉。
                parity["upgrade"] += 1
                upgrades.setdefault(rule["ql"], p.get("error") or "")
            else:
                parity["diff"] += 1
                if len(mismatches) < 40:
                    mismatches.append({
                        "payload": pid, "key": rule["key"], "ql": rule["ql"][:160],
                        "java3vl": jl, "python": pl,
                        "javaErr": j.get("error"), "pyErr": p.get("error"),
                        "javaMissing": j.get("missing"), "pyMissing": p.get("missing"),
                    })

            nl = native_label(j.get("native") or {})
            matrix[(jl, nl)] += 1
            # 子集文法的缺口：真引擎认，我们的解析器不认
            if jl == "ERROR" and nl in ("TRUE", "FALSE"):
                parse_gaps.setdefault(rule["ql"], j.get("error") or "")
            # 最关键的一档：3vl 弃权而 native 判否 —— 接真引擎会凭空多出的拦截
            if (jl in ("TRUE", "FALSE") and nl in ("TRUE", "FALSE") and jl != nl
                    and len(real_diffs) < 60):
                real_diffs.append({"payload": pid, "key": rule["key"],
                                   "ql": rule["ql"][:200], "java3vl": jl, "native": nl,
                                   "missing": j.get("missing")})
            if jl == "UNKNOWN" and nl == "FALSE":
                ms = j.get("missing") or []
                if ms:
                    for m in ms:
                        gap_fields[m] += 1
                else:
                    # 弃权却报不出缺哪个字段 —— sumField/countWhere 因**行内**字段
                    # 取不到而弃权时，名字没进 missing，人拿到报告无从下手
                    gap_blind += 1
            if jl == "UNKNOWN" and nl == "FALSE" and len(native_gaps) < 40:
                native_gaps.append({
                    "payload": pid, "key": rule["key"], "ql": rule["ql"][:160],
                    "missing": j.get("missing"),
                })

    total = parity["same"] + parity["diff"] + parity["upgrade"]
    base = parity["same"] + parity["diff"]
    print("## 一致性：qlengine-3vl vs qlrun.py（应为 100%）")
    print(f"   一致 {parity['same']} / {base} = {parity['same'] / max(base, 1):.4%}　"
          f"分歧 {parity['diff']}")
    if parity["upgrade"]:
        print(f"   另有 {parity['upgrade']} 次已知升级差（{len(upgrades)} 条 QL）："
              f"Java 支持三元，qlrun.py 尚未跟进，不计入上面的分母")
        for ql in list(upgrades)[:3]:
            print(f"     · {ql[:120]}")
    print()
    for m in mismatches[:12]:
        print(f"   ✗ [{m['payload']}] java={m['java3vl']} python={m['python']}")
        print(f"     {m['ql']}")
        if m["javaErr"] or m["pyErr"]:
            print(f"     err java={m['javaErr']} python={m['pyErr']}")

    print("\n## 分布：3vl 判定 × native 结果")
    print(f"   {'3vl':<10} {'native':<10} {'次数':>8}")
    for (a, b), n in matrix.most_common(20):
        flag = "  ← 反向误报风险" if (a == "UNKNOWN" and b == "FALSE") else ""
        flag = "  ← 真分歧" if (a in ("TRUE", "FALSE") and b in ("TRUE", "FALSE")
                                and a != b) else flag
        print(f"   {a:<10} {b:<10} {n:>8}{flag}")

    gap = sum(n for (a, b), n in matrix.items() if a == "UNKNOWN" and b == "FALSE")
    real = sum(n for (a, b), n in matrix.items()
               if a in ("TRUE", "FALSE") and b in ("TRUE", "FALSE") and a != b)
    print(f"\n   反向误报（3vl 弃权 / native 判否）：{gap} 次 = {gap / max(total, 1):.2%}")
    for g in native_gaps[:5]:
        print(f"     · [{g['payload']}] 缺 {g['missing']}")
        print(f"       {g['ql']}")

    print(f"\n   其中报不出缺失字段的：{gap_blind} 次 = {gap_blind / max(gap, 1):.1%}"
          f"（行内字段取不到，名字没进 missing）")
    print(f"\n   反向误报按字段聚类（修哪个收益最大）：")
    for f, n in gap_fields.most_common(12):
        print(f"     {n:>6}  {f}")

    print(f"\n   真分歧（两边都给布尔却不同）：{real} 次")
    for d in real_diffs[:12]:
        print(f"     · 3vl={d['java3vl']} native={d['native']}  缺 {d['missing']}")
        print(f"       {d['ql']}")

    if parse_gaps:
        print(f"\n## 子集文法缺口（真引擎认、3vl 解析器不认）：{len(parse_gaps)} 条 QL")
        for ql, err in list(parse_gaps.items())[:10]:
            print(f"   · {err}")
            print(f"     {ql[:150]}")

    if args.json:
        Path(args.json).write_text(json.dumps({
            "rules": len(rules), "payloads": len(payloads), "evaluations": total,
            "parity": dict(parity),
            "matrix": {f"{a}|{b}": n for (a, b), n in matrix.items()},
            "mismatches": mismatches, "nativeGaps": native_gaps,
            "parseGaps": parse_gaps, "realDiffs": real_diffs,
            "upgrades": upgrades,
            "gapFields": dict(gap_fields.most_common()), "gapBlind": gap_blind,
        }, ensure_ascii=False, indent=1), encoding="utf-8")
        print(f"\n报告已写入 {args.json}")


if __name__ == "__main__":
    main()
