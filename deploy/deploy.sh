#!/usr/bin/env bash
# qlengine 远端部署。服务由 systemd 管理，只监听远端 127.0.0.1:7870；对外经 nginx（TLS + BasicAuth）。
#
# 发版（可反复执行，只换 jar，不碰入口）：
#   deploy/deploy.sh install            构建（跑测试）→ 推 jar → 切 current → 重启 → 核对测量版本 → 冒烟 → 记部署日志
#   deploy/deploy.sh rollback [commit]  回到上一个 release，或指定 commit 的 release
#   deploy/deploy.sh releases           远端 release 列表与部署日志
#   deploy/deploy.sh status             systemd 状态、current / previous、/health、入口
#   deploy/deploy.sh preflight          只读检查远端：系统、内存、磁盘、端口、JRE、已部署版本
# 对外入口（与发版无关，改入口时才执行）：
#   deploy/deploy.sh proxy              证书（缺时生成）、nginx server 块、fail2ban jail；nginx -t 不过即撤回
#   deploy/deploy.sh proxy-user <name>  新增或重置一个 BasicAuth 账号；从 stdin 读一行 `htpasswd -nB <name>` 的输出
#   deploy/deploy.sh cert [path]        取回入口证书供客户端钉住（缺省 ~/.config/harness/qlengine-ca.pem）
# 运维通道：
#   deploy/deploy.sh tunnel             本机 127.0.0.1:$LOCAL_PORT → 远端 127.0.0.1:7870（绕过 nginx，前台）
#
# 环境变量：
#   QLENGINE_REMOTE       ssh 主机名，缺省 lingcai
#   QLENGINE_PUBLIC_PORT  对外端口，缺省 19999
#   QLENGINE_PUBLIC_HOST  证书里的 IP，缺省取 ssh 配置里的 HostName
#   KEEP_RELEASES         保留的 release 数，缺省 5（current、previous 总保留）
#   ALLOW_VERSION_CHANGE  =1 时允许测量版本变化（框架会把旧判决标 stale，需重测基线）
#   ALLOW_UNPUSHED        =1 时允许部署未推送到 origin 的提交（仅临时排障用）
#   LOCAL_PORT            tunnel 的本机端口，缺省 17870
#
# 远端布局：/opt/qlengine/{jre → jre-<版本>, releases/<commit>/qlengine.jar, current, previous, deploy.log}
# 日志 /var/log/qlengine.log（只有启动行）、/var/log/nginx/qlengine.{access,error}.log。
# 不装系统 Java：用独立的 Temurin JRE 21（dnf 源只有 17）。
set -euo pipefail

cd "$(dirname "$0")/.."

HOST="${QLENGINE_REMOTE:-lingcai}"
PUBLIC_PORT="${QLENGINE_PUBLIC_PORT:-19999}"
KEEP="${KEEP_RELEASES:-5}"
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

# 远端函数：等服务起来，核对测量版本（expect 为空时不核对）并冒烟；成功时输出版本。install / rollback 共用。
# 冒烟用的 QL 里 b 取不到：三值路径必须弃权并报 MISSING，这一条同时验证取数、三值逻辑和错误码。
read -r -d '' REMOTE_VERIFY <<'FN' || true
verify() {
  local expect=$1 got="" smoke=""
  for _ in $(seq 1 40); do
    got=$(curl -sf -m 1 http://127.0.0.1:7870/health | sed -n 's/.*"measurementVersion":"\([^"]*\)".*/\1/p') && [ -n "$got" ] && break
    sleep 0.5
  done
  if [ -z "$got" ] || { [ -n "$expect" ] && [ "$got" != "$expect" ]; }; then
    echo "测量版本不符：远端 '${got:-无响应}'，期望 '${expect:-任意}'" >&2; return 1
  fi
  smoke=$(curl -sf -m 5 -X POST http://127.0.0.1:7870/execute -H 'Content-Type: application/json' \
    -d '{"ql":"a > 1 && !(b == 1)","payload":{"a":2},"fields":{"a":"$.a","b":"$.b"},"mode":"both"}') || true
  case "$smoke" in
    *'"verdict":null'*'"errorCode":"MISSING"'*) ;;
    *) echo "冒烟失败：${smoke:-无响应}" >&2; return 1 ;;
  esac
  echo "$got"
}
FN

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
    if [ "${ALLOW_UNPUSHED:-0}" != 1 ]; then
      git fetch -q origin
      [ -n "$(git branch -r --contains HEAD)" ] || { echo "$commit 还没推到 origin：先 git push（ALLOW_UNPUSHED=1 可临时跳过）" >&2; exit 1; }
    fi
    mvn -B -q package
    expect=$(local_version)
    sha=$(shasum -a 256 "$JAR" | cut -d' ' -f1)
    who=$(git config user.email || echo unknown)
    echo "部署 $commit · 测量版本 $expect · jar sha256 $sha"

    # 1. 运行时、用户、目录；输出当前在跑的测量版本（首次部署为空）
    current=$(remote "$JRE_VER" "$JRE_SHA" "$JRE_URL" "$commit" <<'EOF'
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
  echo "已装 JRE $ver" >&2
fi
ln -sfn jre-$ver /opt/qlengine/jre
curl -sf -m 2 http://127.0.0.1:7870/health | sed -n 's/.*"measurementVersion":"\([^"]*\)".*/\1/p' || true
EOF
)
    if [ -n "$current" ] && [ "$current" != "$expect" ] && [ "${ALLOW_VERSION_CHANGE:-0}" != 1 ]; then
      echo "测量版本将从 $current 变为 $expect。" >&2
      echo "读数口径变了：框架会把旧判决标 stale，需要重测基线。确认后用 ALLOW_VERSION_CHANGE=1 重跑。" >&2
      exit 1
    fi

    # 2. 推 jar 与 unit
    scp -q -o BatchMode=yes "$JAR" "$HOST:/opt/qlengine/releases/$commit/qlengine.jar"
    scp -q -o BatchMode=yes deploy/qlengine.service "$HOST:/etc/systemd/system/qlengine.service"

    # 3. 切换、重启、核对、冒烟；失败回滚。保留最近 KEEP 个 release，记部署日志
    { printf '%s\n' "$REMOTE_VERIFY"; cat <<'EOF'
set -euo pipefail
commit=$1 sha=$2 expect=$3 keep=$4 who=$5
cd /opt/qlengine
echo "$sha  releases/$commit/qlengine.jar" | sha256sum -c --quiet
cur=$(readlink current 2>/dev/null || true)
[ -n "$cur" ] && [ "$cur" != "releases/$commit" ] && ln -sfn "$cur" previous
ln -sfn releases/$commit current
chown -R root:root /opt/qlengine && chmod -R a+rX /opt/qlengine
systemctl daemon-reload
systemctl enable -q qlengine
systemctl restart qlengine
if ! got=$(verify "$expect"); then
  if [ -L previous ]; then ln -sfn "$(readlink previous)" current; systemctl restart qlengine; echo "已回滚到 $(readlink current)" >&2; fi
  echo "$(date -Iseconds) FAIL $commit $expect by=$who" >> deploy.log
  exit 1
fi
# 清理旧 release：按修改时间保留最近 keep 个，current、previous 总保留
keepset=" $(readlink current) $(readlink previous 2>/dev/null || true) "
ls -1t releases | tail -n +$((keep + 1)) | while read -r r; do
  case "$keepset" in *" releases/$r "*) ;; *) rm -rf "releases/$r"; echo "清理 release $r" ;; esac
done
echo "$(date -Iseconds) OK $commit $got prev=${cur#releases/} sha=$sha by=$who" >> deploy.log
echo "远端就绪：$(readlink current) · $got · RSS $(ps -o rss= -p "$(systemctl show -p MainPID --value qlengine)" | awk '{printf "%.0fMB", $1/1024}')"
EOF
    } | remote "$commit" "$sha" "$expect" "$KEEP" "$who"
    ;;

  rollback)
    { printf '%s\n' "$REMOTE_VERIFY"; cat <<'EOF'
set -euo pipefail
target=${1:-}
cd /opt/qlengine
cur=$(readlink current)
if [ -n "$target" ]; then
  [ -d "releases/$target" ] || { echo "没有 release $target：$(ls releases | tr '\n' ' ')" >&2; exit 1; }
  dst=releases/$target
else
  [ -L previous ] || { echo "没有上一个 release" >&2; exit 1; }
  dst=$(readlink previous)
fi
ln -sfn "$dst" current; ln -sfn "$cur" previous
systemctl restart qlengine
got=$(verify "") || { echo "回滚后服务不正常" >&2; exit 1; }
echo "$(date -Iseconds) ROLLBACK ${dst#releases/} $got from=${cur#releases/}" >> deploy.log
echo "current → $dst · $got"
EOF
    } | remote "${2:-}"
    ;;

  releases)
    remote <<'EOF'
set +e
cd /opt/qlengine 2>/dev/null || { echo "未部署"; exit 0; }
echo "current → $(readlink current)  previous → $(readlink previous 2>/dev/null || echo 无)"
ls -1t releases | sed 's/^/  /'
echo "== 部署日志（最近 10 条）"; tail -10 deploy.log 2>/dev/null
EOF
    ;;

  status)
    remote "$PUBLIC_PORT" <<'EOF'
set +e
systemctl --no-pager -l status qlengine | sed -n '1,6p'
echo "current → $(readlink /opt/qlengine/current)  previous → $(readlink /opt/qlengine/previous 2>/dev/null || echo 无)"
curl -s -m 2 http://127.0.0.1:7870/health; echo
[ -f /etc/nginx/conf.d/qlengine.conf ] && echo "入口 :$1 未带凭证 → $(curl -sk -m 2 -o /dev/null -w '%{http_code}' https://127.0.0.1:$1/health)" || echo "入口未配置"
EOF
    ;;

  proxy)
    public_host="${QLENGINE_PUBLIC_HOST:-$(ssh -G "$HOST" | awk '/^hostname /{print $2}')}"
    scp -q -o BatchMode=yes deploy/nginx-qlengine.conf "$HOST:/tmp/qlengine-nginx.conf"
    scp -q -o BatchMode=yes deploy/fail2ban-qlengine.conf "$HOST:/tmp/qlengine-fail2ban.conf"
    remote "$PUBLIC_PORT" "$public_host" <<'EOF'
set -euo pipefail
port=$1 host=$2
ts=$(date +%Y%m%d-%H%M%S)
mkdir -p /etc/nginx/qlengine
if [ ! -f /etc/nginx/qlengine/cert.pem ]; then
  openssl req -x509 -newkey ec -pkeyopt ec_paramgen_curve:P-256 -nodes -days 3650 \
    -subj "/CN=$host" -addext "subjectAltName=IP:$host" \
    -keyout /etc/nginx/qlengine/key.pem -out /etc/nginx/qlengine/cert.pem 2>/dev/null
  chmod 600 /etc/nginx/qlengine/key.pem
  echo "已生成证书（CN/SAN = $host，10 年）"
fi
if [ ! -f /etc/nginx/.htpasswd-qlengine ]; then
  install -m 0640 -o root -g nginx /dev/null /etc/nginx/.htpasswd-qlengine
  echo "已建空账号文件：proxy-user 之前所有请求都是 401"
fi
conf=/etc/nginx/conf.d/qlengine.conf
bak=""
if [ -f $conf ]; then bak=/etc/nginx/qlengine/qlengine.conf.bak-$ts; cp $conf $bak; fi
sed "s/__PORT__/$port/g" /tmp/qlengine-nginx.conf > $conf
if ! nginx -t 2>/tmp/qlengine-nginx-t.log; then
  cat /tmp/qlengine-nginx-t.log >&2
  if [ -n "$bak" ]; then cp $bak $conf; else rm -f $conf; fi
  echo "nginx -t 未通过，已撤回" >&2; exit 1
fi
systemctl reload nginx
firewall-cmd -q --query-port=$port/tcp || { firewall-cmd -q --permanent --add-port=$port/tcp && firewall-cmd -q --reload; echo "firewalld 已放行 $port"; }
touch /var/log/nginx/qlengine.error.log
sed "s/__PORT__/$port/g" /tmp/qlengine-fail2ban.conf > /etc/fail2ban/jail.d/qlengine.conf
# 先校验整份 fail2ban 配置：机器上其他 jail 配错时全量 reload 会失败，那与本入口无关，只提示不硬改
if fail2ban-client -t >/dev/null 2>&1; then
  fail2ban-client reload >/dev/null && fail2ban-client status qlengine-auth | sed -n '1p;$p'
else
  echo "警告：fail2ban 现有配置校验不过（$(fail2ban-client -t 2>&1 | grep -m1 -o 'ERROR.*' || echo 未知)）。" >&2
  echo "      qlengine-auth 已写入 /etc/fail2ban/jail.d/qlengine.conf，但未加载；修好那处配置后执行 fail2ban-client reload。" >&2
fi
rm -f /tmp/qlengine-nginx.conf /tmp/qlengine-fail2ban.conf /tmp/qlengine-nginx-t.log
sleep 1
echo "入口 :$port 就绪：未带凭证 → $(curl -sk -m 3 -o /dev/null -w '%{http_code}' https://127.0.0.1:$port/health)"
EOF
    ;;

  proxy-user)
    name="${2:?用法：htpasswd -nB <name> | deploy/deploy.sh proxy-user <name>}"
    IFS= read -r line
    case "$line" in "$name:\$2y\$"*) ;; *) echo "stdin 应为一行 \`htpasswd -nB $name\` 的输出（bcrypt）" >&2; exit 1 ;; esac
    # 远端脚本与哈希行一起经 stdin 传：哈希行放在脚本末尾的 heredoc 里，不出现在命令行与进程列表
    { cat <<'EOF'
set -euo pipefail
name=$1
f=/etc/nginx/.htpasswd-qlengine
[ -f $f ] || { echo "先执行 deploy.sh proxy" >&2; exit 1; }
IFS= read -r line <<'HASH'
EOF
      printf '%s\nHASH\n' "$line"
      cat <<'EOF'
tmp=$(mktemp)
grep -v "^$name:" $f > $tmp || true
printf '%s\n' "$line" >> $tmp
install -m 0640 -o root -g nginx $tmp $f && rm -f $tmp
echo "账号 $name 已更新（共 $(wc -l < $f) 个）"
EOF
    } | remote "$name"
    ;;

  cert)
    dst="${2:-$HOME/.config/harness/qlengine-ca.pem}"
    mkdir -p "$(dirname "$dst")"
    scp -q -o BatchMode=yes "$HOST:/etc/nginx/qlengine/cert.pem" "$dst"
    openssl x509 -in "$dst" -noout -subject -enddate -fingerprint -sha256
    echo "→ $dst"
    ;;

  tunnel)
    echo "127.0.0.1:$LOCAL_PORT → $HOST:127.0.0.1:7870（Ctrl-C 断开）"
    exec ssh -N -o BatchMode=yes -o ExitOnForwardFailure=yes -o ServerAliveInterval=30 -L "127.0.0.1:$LOCAL_PORT:127.0.0.1:7870" "$HOST"
    ;;

  *)
    sed -n '2,29p' "$0"
    exit 1
    ;;
esac
