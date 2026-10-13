#!/usr/bin/env bash
# qlengine 本机运行脚本。
#
#   ./run.sh build     打包（跑测试）
#   ./run.sh start     后台起服务
#   ./run.sh stop      停
#   ./run.sh status    看状态 + 函数白名单
#   ./run.sh logs      跟日志
#   ./run.sh diff      跑对拍（需要服务在跑）
#
# openjdk 是 keg-only，/usr/libexec/java_home 找不到它，所以这里写死 JAVA_HOME
# 而不是靠系统探测。
set -euo pipefail

cd "$(dirname "$0")"

JAVA_HOME="${JAVA_HOME:-/opt/homebrew/opt/openjdk}"
export JAVA_HOME
export PATH="$JAVA_HOME/bin:/opt/homebrew/bin:$PATH"

JAR="target/qlengine-0.1.0-SNAPSHOT.jar"
PORT="${QLENGINE_PORT:-7870}"
LOG="${QLENGINE_LOG:-/tmp/qlengine.log}"

case "${1:-status}" in
  build)
    mvn -B package
    ;;

  start)
    [ -f "$JAR" ] || { echo "没有 $JAR，先 ./run.sh build"; exit 1; }
    pgrep -f "$JAR" >/dev/null && { echo "已经在跑（pid $(pgrep -f "$JAR"))"; exit 0; }
    nohup java -jar "$JAR" > "$LOG" 2>&1 &
    for _ in $(seq 1 30); do
      curl -sf -m 1 "http://127.0.0.1:$PORT/health" >/dev/null && break
      sleep 0.3
    done
    curl -sf -m 2 "http://127.0.0.1:$PORT/health" \
      && echo "  日志 $LOG" \
      || { echo "起不来，看 $LOG"; tail -20 "$LOG"; exit 1; }
    ;;

  stop)
    if pkill -f "$JAR"; then
      # pkill 发完信号就返回，进程还没退。不等的话紧跟着的 start 会看到残影，
      # 报「已经在跑」然后什么都不做。
      for _ in $(seq 1 20); do
        pgrep -f "$JAR" >/dev/null || break
        sleep 0.2
      done
      echo "已停"
    else
      echo "本来就没在跑"
    fi
    ;;

  status)
    curl -sf -m 2 "http://127.0.0.1:$PORT/health" && echo \
      || { echo "没在跑"; exit 1; }
    curl -s "http://127.0.0.1:$PORT/functions"; echo
    ;;

  logs)
    tail -f "$LOG"
    ;;

  diff)
    curl -sf -m 2 "http://127.0.0.1:$PORT/health" >/dev/null \
      || { echo "服务没在跑，先 ./run.sh start"; exit 1; }
    mkdir -p out
    python3 tools/diff_check.py --json out/diff-full.json "${@:2}"
    ;;

  *)
    sed -n '2,12p' "$0"
    exit 1
    ;;
esac
