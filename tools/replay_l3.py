"""L3 判定回放 —— sample_check.py 分层里一直悬着的那一层。

    QLENGINE_RULE_DIRS=… QLENGINE_PAYLOADS=… python3 tools/replay_l3.py [--json out/l3.json]

## 为什么这一层以前跑不了

sample_check.py 写着「L3（未实现）：判定回放，需要权威 QLExpress + 结构化的审核
结论」。两个前提现在都有了：qlengine 提供权威引擎，报文自带的 `bill.adviseList[]`
审批轨迹提供结论（`label_of` 反推）。

## 验收指标：合规单上的假阳

29 份报文的终态是「审批通过」。审批通过意味着单据合规，**期望零规则触发**。
所以合规单上每一条触发都是假阳，不需要任何人工标注就能数出来。

三个口径一起给：

    native        真 QLExpress，缺字段被压成「断言不成立」→ 触发
    3vl-loose     三值，UNKNOWN 的规则弃权，但前置为 UNKNOWN 时仍放行
    3vl-strict    三值，前置 UNKNOWN 也门掉

三者的差就是三值逻辑到底消掉了多少假阳。
"""
from __future__ import annotations

import argparse
import collections
import json
import os
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "lingcai_new"))
sys.path.insert(0, str(Path(__file__).resolve().parent))

from sample_check import label_of                      # noqa: E402
from qlengine_client import QlEngine, field_tables     # noqa: E402

# 规则包目录与报文目录由调用方给，客户数据不进本仓：
#   QLENGINE_RULE_DIRS=<规则包目录>[:<规则包目录>…]  QLENGINE_PAYLOADS=<报文目录>
RULE_DIRS = [Path(p) for p in os.environ.get("QLENGINE_RULE_DIRS", "").split(os.pathsep) if p]
PAYLOAD_DIR = Path(os.environ.get("QLENGINE_PAYLOADS") or "/nonexistent")

# `--remap` 的对照组：只给这两个别名各补一条路径，其余字段表原样不动。
#
# 两者原本都注册在 `extendField.mx1fykmzw` / `.fykmwb`，真实报文里没有这两个 key；
# 备用路径 `extendLineList8[]` 是空数组。人读的科目名实际在
# `expReportLineList[].extendField.expenseItemDesc`（形如「业务招待费-<科目编码>」）。
# 单变量实验：前置 UNKNOWN 率会掉多少。
REMAP = {
    "expenseSubject": ["$.bill.expReportLineList[].extendField.expenseItemDesc"],
    "lineExpenseSubject": ["$.bill.expReportLineList[].extendField.expenseItemDesc"],
}


def load_policy(d: Path) -> tuple[list[dict], dict[str, dict]]:
    rules = json.loads((d / "rules_ql.json").read_text(encoding="utf-8"))
    pres = {}
    f = d / "preconditions_ql.json"
    if f.exists():
        pres = {p["id"]: p for p in json.loads(f.read_text(encoding="utf-8"))}
    return [r for r in rules if (r.get("ql") or "").strip()], pres


def fired(verdict) -> bool:
    """规则约定 true = 断言成立 = 合规，false = 触发 = 拦截。"""
    return verdict is False


def native_verdict(n: dict):
    """native 没有三值。报错与非布尔都不算触发 —— 不硬把异常读成拦截。"""
    r = n.get("result")
    return r if isinstance(r, bool) else None


def replay(eng: QlEngine, fields: dict, colls: dict, diag: dict | None = None) -> dict:
    payloads = []
    for f in sorted(PAYLOAD_DIR.rglob("*.txt")):
        try:
            p = json.loads(f.read_text(encoding="utf-8"))
        except Exception:
            continue
        payloads.append((f"{f.parent.name}/{f.stem}", label_of(p), p))

    per_bill, agg = [], collections.Counter()
    fp_rules: collections.Counter = collections.Counter()

    for pid, label, payload in payloads:
        hit = {"native": [], "loose": [], "strict": []}
        n_rules = 0

        for d in RULE_DIRS:
            rules, pres = load_policy(d)
            n_rules += len(rules)

            # 前置和规则一起提交，一次往返跑完一份制度
            items = [{"key": f"r::{r['id']}", "ql": r["ql"]} for r in rules]
            pre_ids = {pid2 for r in rules for pid2 in (r.get("pre_ids") or [])}
            items += [{"key": f"p::{i}", "ql": pres[i]["ql"]}
                      for i in pre_ids
                      if i in pres and (pres[i].get("ql") or "").strip()]
            res = {x["key"]: x for x in eng.batch(payload, fields, colls, items)}

            for r in rules:
                # 前置门控：两个口径。严口径把 UNKNOWN 也门掉，宽口径只门 FALSE。
                gs = gl = True
                for i in (r.get("pre_ids") or []):
                    pv = res.get(f"p::{i}")
                    if pv is None:
                        continue                  # 前置没编译出 QL，无法门控，放行
                    if diag is not None:
                        diag["pre"]["ERROR" if pv.get("error") else
                                    {True: "TRUE", False: "FALSE",
                                     None: "UNKNOWN"}[pv["verdict"]]] += 1
                        if pv["verdict"] is None:
                            for m in (pv.get("missing") or []):
                                diag["gap"][m] += 1
                    if pv["verdict"] is False:
                        gs = gl = False
                        break
                    if pv["verdict"] is None:
                        gs = False

                x = res[f"r::{r['id']}"]
                tag = f"{d.name}::{r['id']}"
                if fired(native_verdict(x["native"])):
                    hit["native"].append(tag)
                if gl and fired(x["verdict"]):
                    hit["loose"].append(tag)
                if gs and fired(x["verdict"]):
                    hit["strict"].append(tag)

        per_bill.append({"bill": pid, "label": label, "rules": n_rules,
                         "fired": {k: len(v) for k, v in hit.items()}})
        agg[f"{label}/native"] += len(hit["native"])
        agg[f"{label}/loose"] += len(hit["loose"])
        agg[f"{label}/strict"] += len(hit["strict"])
        agg[f"{label}/bills"] += 1
        if label == "approved":
            # 合规单上 native 触发但三值不触发的规则 —— 三值消掉的假阳
            for t in set(hit["native"]) - set(hit["strict"]):
                fp_rules[t] += 1

    return {"perBill": per_bill, "agg": dict(agg),
            "fpEliminated": dict(fp_rules.most_common(25)),
            "ruleTotal": per_bill[0]["rules"] if per_bill else 0}


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--json", default="")
    ap.add_argument("--remap", action="store_true",
                    help="给 expenseSubject/lineExpenseSubject 补真实路径后重跑（对照组）")
    ap.add_argument("--diag", action="store_true",
                    help="打印前置求值分布与取不到的字段排行")
    args = ap.parse_args()
    if not RULE_DIRS or not PAYLOAD_DIR.is_dir():
        raise SystemExit("先设置 QLENGINE_RULE_DIRS（规则包目录，含 rules_ql.json）与 QLENGINE_PAYLOADS（报文目录）")

    eng = QlEngine()
    if not eng.alive():
        print("qlengine 没在跑，先 ./run.sh start")
        sys.exit(1)

    fields, colls = field_tables(str(ROOT / "lingcai_new"))
    if args.remap:
        for alias, extra in REMAP.items():
            fields[alias] = extra + list(fields.get(alias) or [])
        print(f"已重映射 {len(REMAP)} 个别名（对照组）\n")

    diag = {"pre": collections.Counter(), "gap": collections.Counter()} if args.diag else None
    rep = replay(eng, fields, colls, diag)
    a = rep["agg"]

    print(f"L3 判定回放　规则 {rep['ruleTotal']} 条 × 报文 "
          f"{sum(v for k, v in a.items() if k.endswith('/bills'))} 份\n")

    print(f"{'结论':<10}{'单数':>5}{'native':>10}{'3vl宽':>10}{'3vl严':>10}   （每单平均触发）")
    for label in ("approved", "rejected", "unknown"):
        n = a.get(f"{label}/bills", 0)
        if not n:
            continue
        nat, lo, st = (a.get(f"{label}/{k}", 0) for k in ("native", "loose", "strict"))
        print(f"{label:<10}{n:>5}{nat / n:>10.1f}{lo / n:>10.1f}{st / n:>10.1f}")

    na = a.get("approved/bills", 0)
    if na:
        nat, st = a.get("approved/native", 0), a.get("approved/strict", 0)
        print(f"\n合规单据（{na} 份）上的假阳 —— 期望 0：")
        print(f"   native      {nat:>6} 条（每单 {nat / na:.1f}）")
        print(f"   3vl 严口径  {st:>6} 条（每单 {st / na:.1f}）")
        if nat:
            print(f"   三值逻辑消掉 {nat - st} 条 = {(nat - st) / nat:.1%}")

    if rep["fpEliminated"]:
        print(f"\n消掉的假阳按规则聚类（top 10）：")
        for t, n in list(rep["fpEliminated"].items())[:10]:
            print(f"   {n:>4} 单　{t}")

    if diag is not None:
        print(f"\n前置求值分布  {dict(diag['pre'])}")
        tot = sum(diag["pre"].values())
        if tot:
            print(f"   UNKNOWN 占比 {diag['pre']['UNKNOWN'] / tot:.1%}")
        if diag["gap"]:
            print("   前置取不到的字段 top 10：")
            for k, n in diag["gap"].most_common(10):
                print(f"   {n:>6}  {k}")
        rep["diag"] = {"pre": dict(diag["pre"]), "gap": dict(diag["gap"].most_common(30))}

    if args.json:
        Path(args.json).write_text(json.dumps(rep, ensure_ascii=False, indent=1),
                                   encoding="utf-8")
        print(f"\n报告已写入 {args.json}")


if __name__ == "__main__":
    main()
