#!/bin/bash
# QLExpress4 API 侦察：把真实签名打出来，避免照着记忆写错 API。
set -e
cd "$(dirname "$0")"
mvn -q dependency:copy-dependencies -DoutputDirectory=target/deps -DincludeArtifactIds=qlexpress4
JAR=$(ls target/deps/qlexpress4-*.jar)
echo "=== JAR: $JAR ==="
echo; echo "=== 顶层公开类 ==="
jar tf "$JAR" | grep -E '^com/alibaba/qlexpress4/[A-Za-z]+\.class$' | sed 's#/#.#g;s#\.class##'
echo; echo "=== Express4Runner ==="
javap -cp "$JAR" com.alibaba.qlexpress4.Express4Runner 2>/dev/null || echo "(未找到)"
echo; echo "=== InitOptions ==="
javap -cp "$JAR" com.alibaba.qlexpress4.InitOptions 2>/dev/null || echo "(未找到)"
echo; echo "=== QLOptions ==="
javap -cp "$JAR" com.alibaba.qlexpress4.QLOptions 2>/dev/null || echo "(未找到)"
echo; echo "=== 与 operator / function / result / security 相关的类 ==="
jar tf "$JAR" | grep -iE 'operator|function|result|security|trace' | grep '\.class$' \
  | grep -v '\$' | sed 's#/#.#g;s#\.class##' | head -60
