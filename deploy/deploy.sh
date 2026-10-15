#!/usr/bin/env bash
# qlengine 远端部署：systemd 管理，远端只监听 127.0.0.1:7870，本机经 SSH 隧道访问。
#
#   deploy/deploy.sh preflight   只读检查远端：系统、内存、磁盘、端口、JRE、已部署版本
#   deploy/deploy.sh install     构建（跑测试）→ 推 jar 与 unit → 缺 JRE 时装 JRE → 切 current → 重启 → 核对测量版本
#   deploy/deploy.sh status      远端 systemd 状态与 /health
#   deploy/deploy.sh rollback    current 指回上一个 release 并重启
#   deploy/deploy.sh tunnel      本机 127.0.0.1:$LOCAL_PORT → 远端 127.0.0.1:7870（前台，Ctrl-C 断开）
#
# 环境变量：QLENGINE_REMOTE（ssh 主机名，默认 lingcai）、LOCAL_PORT（默认 17870）。
# 远端布局：/opt/qlengine/{jre → jre-<版本>, releases/<commit>/qlengine.jar, current → releases/<commit>, previous}
# 日志 /var/log/qlengine.log（只有启动行）。系统里不装 Java：用独立的 Temurin JRE 21，dnf 源只有 17。
set -euo pipefail

cd "$(dirname "$0")/.."

HOST="${QLENGINE_REMOTE:-lingcai}"
LOCAL_PORT="${LOCAL_PORT:-17870}"
JAR="target/qlengine-0.1.0-SNAPSHOT.jar"
JRE_VER="21.0.12.1_1"
# sha256 取自 github.com/adoptium/temurin21-binaries 发布页；包从清华镜像下
JRE_SHA="2413149700df0f7d440500a84a8f764c535f21e5a5e87d38328b64eec2c5b500"
JRE_URL="https://mirrors.tuna.tsinghua.edu.cn/Adoptium/21/jre/x64/linux/OpenJDK21U-jre_x64_linux_hotspot_${JRE_VER}.tar.gz"
JAVA_HOME="${JAVA_HOME:-/opt/homebrew/opt/openjdk}"
export JAVA_HOME PATH="$JAVA_HOME/bin:/opt/homebrew/bin:$PATH"
SSH=(ssh -o BatchMode=yes -o ConnectTimeout=10 "$HOST")

# 参数经远端 shell 再解析一次，先转义（测量版本里有 # 与 +）
remote() { "${SSH[@]}" "bash -s -- $(printf '%q ' "$@")"; }

# 本机起一次刚构建的 jar，读它报告的测量版本：远端必须报告同一个
local_version() {
  local port=7879 pid v=""
  QLENGINE_PORT=$port java -Xmx128m -jar "$JAR" >/dev/null 2>&1 &
  pid=$!
  for _ in $(seq 1 30); do
    v=$(curl -sf -m 1 "http://127.0.0.1:$port/health" | sed -n 's/.*"measurementVersion":"\([^"]*\)".*/\1/p') && [ -n "$v" ] && break
    sleep 0.3
  done
  kill $pid 2>/dev/null; wait $pid 2>/dev/null || true
  [ -n "$v" ] || { echo "本机起不来 $JAR，读不到测量版本" >&2; exit 1; }
  echo "$v"
}

case "${1:-}" in
  preflight)
    remote <<'EOF'
set +e
echo "== 系统"; . /etc/os-release; echo "$PRETTY_NAME $(uname -m) $(nproc) 核"
echo "== 内存"; free -m | sed -n '1,3p'
echo "== 磁盘"; df -h /opt | tail -1
echo "== 端口 7870"; ss -ltn 'sport = :7870' | tail -n +2 | grep -q . && echo "已占用" || echo "空闲"
echo "== JRE"; [ -x /opt/qlengine/jre/bin/java ] && /opt/qlengine/jre/bin/java -version 2>&1 | head -1 || echo "未装"
echo "== 已部署"; readlink /opt/qlengine/current 2>/dev/null || echo "无"; systemctl is-active qlengine 2>/dev/null || true
EOF
    ;;

  install)
    [ -z "$(git status --porcelain)" ] || { echo "工作区有未提交改动，先提交：部署的版本要能对应到 commit" >&2; exit 1; }
    commit=$(git rev-parse --short=12 HEAD)
    mvn -B -q package
    expect=$(local_version)
    sha=$(shasum -a 256 "$JAR" | cut -d' ' -f1)
    echo "部署 $commit · 测量版本 $expect · jar sha256 $sha"

    remote "$JRE_VER" "$JRE_SHA" "$JRE_URL" "$commit" <<'EOF'
set -euo pipefail
ver=$1 sha=$2 url=$3 commit=$4
mkdir -p /opt/qlengine/releases/$commit
id qlengine >/dev/null 2>&1 || useradd --system --no-create-home --home-dir /opt/qlengine --shell /sbin/nologin qlengine
if [ ! -x /opt/qlengine/jre-$ver/bin/java ]; then
  tmp=$(mktemp -d)
  curl -sfL -m 300 -o $tmp/jre.tgz "$url"
  echo "$sha  $tmp/jre.tgz" | sha256sum -c --quiet
  mkdir -p /opt/qlengine/jre-$ver
  tar xzf $tmp/jre.tgz -C /opt/qlengine/jre-$ver --strip-components=1
  rm -rf $tmp
fi
ln -sfn jre-$ver /opt/qlengine/jre
/opt/qlengine/jre/bin/java -version 2>&1 | head -1
EOF
    scp -q -o BatchMode=yes "$JAR" "$HOST:/opt/qlengine/releases/$commit/qlengine.jar"
    scp -q -o BatchMode=yes deploy/qlengine.service "$HOST:/etc/systemd/system/qlengine.service"

    remote "$commit" "$sha" "$expect" <<'EOF'
set -euo pipefail
commit=$1 sha=$2 expect=$3
cd /opt/qlengine
echo "$sha  releases/$commit/qlengine.jar" | sha256sum -c --quiet
cur=$(readlink current 2>/dev/null || true)
[ -n "$cur" ] && [ "$cur" != "releases/$commit" ] && ln -sfn "$cur" previous
ln -sfn releases/$commit current
chown -R root:root /opt/qlengine && chmod -R a+rX /opt/qlengine
systemctl daemon-reload
systemctl enable -q qlengine
systemctl restart qlengine
got=""
for _ in $(seq 1 40); do
  got=$(curl -sf -m 1 http://127.0.0.1:7870/health | sed -n 's/.*"measurementVersion":"\([^"]*\)".*/\1/p') && [ -n "$got" ] && break
  sleep 0.5
done
if [ "$got" != "$expect" ]; then
  echo "测量版本不符：远端 '${got:-无响应}'，期望 '$expect'" >&2
  if [ -L previous ]; then ln -sfn "$(readlink previous)" current; systemctl restart qlengine; echo "已回滚到 $(readlink current)" >&2; fi
  exit 1
fi
echo "远端就绪：$(readlink current) · $got · RSS $(ps -o rss= -p $(systemctl show -p MainPID --value qlengine) | awk '{printf "%.0fMB", $1/1024}')"
EOF
    ;;

  status)
    remote <<'EOF'
set +e
systemctl --no-pager -l status qlengine | sed -n '1,6p'
echo "current → $(readlink /opt/qlengine/current)  previous → $(readlink /opt/qlengine/previous 2>/dev/null || echo 无)"
curl -s -m 2 http://127.0.0.1:7870/health; echo
EOF
    ;;

  rollback)
    remote <<'EOF'
set -euo pipefail
cd /opt/qlengine
[ -L previous ] || { echo "没有上一个 release" >&2; exit 1; }
cur=$(readlink current); prev=$(readlink previous)
ln -sfn "$prev" current; ln -sfn "$cur" previous
systemctl restart qlengine
sleep 3; curl -sf -m 2 http://127.0.0.1:7870/health; echo
echo "current → $prev"
EOF
    ;;

  tunnel)
    echo "127.0.0.1:$LOCAL_PORT → $HOST:127.0.0.1:7870（Ctrl-C 断开）"
    exec ssh -N -o BatchMode=yes -o ExitOnForwardFailure=yes -o ServerAliveInterval=30 -L "127.0.0.1:$LOCAL_PORT:127.0.0.1:7870" "$HOST"
    ;;

  *)
    sed -n '2,13p' "$0"
    exit 1
    ;;
esac
