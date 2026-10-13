"""qlengine 的 Python 客户端 —— sample_check.py 的 L3（判定回放）接这个。

L2 用 qlrun.py 回答「这条规则在这单上会不会命中」，但 qlrun 自己声明了不能当权威。
L3 要的是线上引擎实际会怎么判，即本模块的 `native` 字段。

    from qlengine_client import QlEngine

    eng = QlEngine()                      # 默认 127.0.0.1:7870
    if not eng.alive():
        ...                               # 服务没起，降级回 L2

    for r in eng.batch(payload, fields, collections, rules):
        r["verdict"]           # 三值：True / False / None(=UNKNOWN)
        r["missing"]           # 取不到的字段别名
        r["native"]["result"]  # 真 QLExpress 的返回值
        r["native"]["error"]   # 真引擎报错（缺字段时常见，不是服务故障）

## 两个字段都要看

规则约定 true = 断言成立 = 合规，false = 触发 = 拦截。QLExpress 没有 UNKNOWN，
字段取不到时它会把「无从判断」压成 false，凭空多出一条拦截。实测 15870 次求值里
这种情形占 36%。所以 L3 的正确读法是：

    verdict is None                 → 弃权，别上报，去看 missing 修字段
    verdict is not None             → 以 native 为准；与 verdict 不一致要查
"""
from __future__ import annotations

import json
import urllib.error
import urllib.request


class QlEngine:
    """无状态 HTTP 客户端。服务本身也无状态，不需要连接池。"""

    def __init__(self, base: str = "http://127.0.0.1:7870", timeout: float = 300.0):
        self.base = base.rstrip("/")
        self.timeout = timeout

    # ---------------------------------------------------------------- 探活

    def alive(self) -> bool:
        """服务在不在。起不来时调用方应降级到 L2，而不是把整条链路挂掉。"""
        try:
            return bool(self._get("/health").get("ok"))
        except Exception:
            return False

    def functions(self) -> list[str]:
        """引擎注册的函数白名单。与 fields.json 的闭集对不上就是两边漂了。"""
        return self._get("/functions").get("functions") or []

    # ---------------------------------------------------------------- 执行

    def run(self, ql: str, payload: dict, fields: dict, collections: dict,
            mode: str = "both") -> dict:
        return self._post("/execute", {
            "ql": ql, "payload": payload, "fields": fields,
            "collections": collections, "mode": mode})

    def batch(self, payload: dict, fields: dict, collections: dict,
              rules: list[dict], mode: str = "both") -> list[dict]:
        """一份报文 × 多条规则。

        `rules` 形如 `[{"key": <规则 id>, "ql": <表达式>}]`；返回按 key 对齐。
        几百条规则跑一单走这个接口，别循环调 `run` —— 报文会被重复传几百遍。
        """
        return self._post("/execute/batch", {
            "payload": payload, "fields": fields, "collections": collections,
            "mode": mode, "rules": rules}).get("results") or []

    # ---------------------------------------------------------------- IO

    def _get(self, path: str) -> dict:
        with urllib.request.urlopen(self.base + path, timeout=10) as r:
            return json.loads(r.read().decode("utf-8"))

    def _post(self, path: str, body: dict) -> dict:
        req = urllib.request.Request(
            self.base + path,
            data=json.dumps(body, ensure_ascii=False).encode("utf-8"),
            headers={"Content-Type": "application/json"})
        try:
            with urllib.request.urlopen(req, timeout=self.timeout) as r:
                return json.loads(r.read().decode("utf-8"))
        except urllib.error.HTTPError as e:
            # 服务对坏请求回 400 + JSON，原样抛出比吞掉有用
            raise RuntimeError(f"qlengine {e.code}: {e.read().decode('utf-8')}") from e


def field_tables(lingcai_dir: str) -> tuple[dict, dict]:
    """从 lingcai_new 取字段别名表与行集合表，与 sample_check.py 同口径。"""
    import sys
    if lingcai_dir not in sys.path:
        sys.path.insert(0, lingcai_dir)
    from fields import FIELDS
    import assets_loader as assets

    fields = {a: [p] for a, (p, _d) in FIELDS.items()}
    fields.update(assets.paths_for_var())
    return fields, assets.collection_vars()
