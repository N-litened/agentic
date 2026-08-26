#!/usr/bin/env bash
# Offline stand-in used by bash-fake-agent-script-roundtrip.
#
# The host process has already started a clojure.core.server socket REPL.
# This script connects, sets current-result so the throwing function
# RETURNS :healed-offline, then exits 0.
#
#   bash test/fake_agent.sh 127.0.0.1 PORT

set -euo pipefail

host="${1:-127.0.0.1}"
port="${2:?port required}"

payload="$(cat <<'EOF'
(alter-var-root #'com.latypoff.agentic.control/current-result (constantly {:action :return :value :healed-offline}))
:repl/quit
EOF
)"

# Wait until the socket REPL accept thread is up.
for _ in $(seq 1 100); do
  if (echo >/dev/tcp/"$host"/"$port") >/dev/null 2>&1; then
    break
  fi
  sleep 0.05
done

send() {
  if command -v nc >/dev/null 2>&1; then
    # OpenBSD nc: -N closes the socket after stdin EOF.
    # Traditional nc: -q 1 quits 1s after EOF. Fall back to a bare pipe.
    if nc -h 2>&1 | grep -q -- '-N'; then
      printf '%s\n' "$payload" | nc -N -w 2 "$host" "$port"
    elif nc -h 2>&1 | grep -q -- '-q'; then
      printf '%s\n' "$payload" | nc -q 1 -w 2 "$host" "$port"
    else
      printf '%s\n' "$payload" | nc -w 2 "$host" "$port"
    fi
  else
    exec 3<>"/dev/tcp/$host/$port"
    printf '%s\n' "$payload" >&3
    sleep 0.2
    exec 3>&-
  fi
}

send >/dev/null 2>&1 || true
exit 0
