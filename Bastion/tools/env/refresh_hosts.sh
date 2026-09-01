#!/usr/bin/env bash
# 为 GitHub / Gradle 相关域名测速选优，写回 /etc/hosts 与 ~/.user_hosts。
# 用法：bash /root/.codebuddy/artifact/refresh_hosts.sh
set -u

DOH="https://dns.alidns.com/resolve?name=%s&type=A"
HOSTS=/etc/hosts
UHOSTS="$HOME/.user_hosts"

# 域名 -> 额外候选 IP（DoH 结果 + 这些一起测）
declare -A EXTRA=(
  ["github.com"]="140.82.112.4 140.82.114.4 140.82.121.4 140.82.113.4 20.205.243.166"
  ["api.github.com"]="140.82.112.5 140.82.114.5 140.82.121.5 140.82.113.5 20.205.243.168"
  ["codeload.github.com"]="140.82.112.9 140.82.114.9 140.82.121.9 20.205.243.165"
  ["objects.githubusercontent.com"]="185.199.108.133 185.199.109.133 185.199.110.133 185.199.111.133"
  ["raw.githubusercontent.com"]="185.199.108.133 185.199.109.133 185.199.110.133 185.199.111.133"
  ["ghcr.io"]="140.82.112.33 140.82.114.33 20.205.243.164"
)

doh_ips() {
  curl -s --max-time 12 "$(printf "$DOH" "$1")" \
    | python3 -c "import json,sys
try:
    d=json.load(sys.stdin)
    print(' '.join(a['data'] for a in d.get('Answer',[]) if a['type']==1))
except Exception:
    print('')"
}

probe() {  # probe <domain> <ip> -> "code time" 或 "- 999"
  local d="$1" ip="$2" out
  out=$(curl -s --max-time 12 --resolve "$d:443:$ip" -o /dev/null \
        -w "%{http_code} %{time_total}" "https://$d/" 2>/dev/null)
  [ -z "$out" ] && echo "- 999" || echo "$out"
}

best_for() {
  local d="$1" cands best_ip="" best_t=999 code t ip
  cands="$(doh_ips "$d") ${EXTRA[$d]:-}"
  for ip in $cands; do
    read -r code t <<<"$(probe "$d" "$ip")"
    # 200/301/302/307 均视为握手成功
    case "$code" in
      200|301|302|303|307|308|401|403)
        if python3 -c "import sys; sys.exit(0 if float('$t')<float('$best_t') else 1)"; then
          best_t="$t"; best_ip="$ip"
        fi
        printf '    %-28s %-16s %s  %ss\n' "$d" "$ip" "$code" "$t" >&2
        ;;
      *) printf '    %-28s %-16s %s  (不可用)\n' "$d" "$ip" "${code:--}" >&2 ;;
    esac
  done
  [ -n "$best_ip" ] && echo "$best_ip"
}

echo "== 测速选优 =="
RESULT=()
for d in github.com api.github.com codeload.github.com objects.githubusercontent.com raw.githubusercontent.com ghcr.io; do
  echo "  [$d]"
  ip="$(best_for "$d")"
  if [ -n "$ip" ]; then
    echo "  -> $d 选 $ip"
    RESULT+=("$ip $d")
  else
    echo "  -> $d 全部失败，保留 hosts 原值"
    cur=$(awk -v h="$d" '$2==h && $1 !~ /^#/ {print $1; exit}' "$HOSTS")
    [ -n "$cur" ] && RESULT+=("$cur $d")
  fi
done

echo
echo "== 写回 hosts =="
for f in "$HOSTS" "$UHOSTS"; do
  [ -f "$f" ] || { echo "  跳过（不存在）：$f"; continue; }
  cp "$f" "$f.bak.$(date +%s)"
  python3 - "$f" "${RESULT[@]}" <<'PY'
import re, sys
path = sys.argv[1]
pairs = [tuple(a.split()) for a in sys.argv[2:]]
lines = open(path, encoding='utf-8').read().splitlines()
out, seen = [], set()
for ln in lines:
    m = re.match(r'^(\d+\.\d+\.\d+\.\d+)\s+([\w.\-]+)\s*$', ln.strip())
    if m and m.group(2) in dict(pairs):
        dom = m.group(2)
        if dom in seen:
            continue                      # 丢弃重复的旧行
        seen.add(dom)
        out.append(f"{dict(pairs)[dom]} {dom}")
    else:
        out.append(ln)
missing = [p for p in pairs if p[1] not in seen]
if missing:
    out.append("")
    out.append("# 自动测速写入（refresh_hosts.sh）")
    out.extend(f"{ip} {dom}" for ip, dom in missing)
open(path, 'w', encoding='utf-8').write("\n".join(out) + "\n")
print(f"  已更新 {path}：{len(pairs)} 个域名")
PY
done

echo
echo "== 复验 =="
for pair in "${RESULT[@]}"; do
  set -- $pair
  printf '  %-28s %-16s ' "$2" "$1"
  curl -s --max-time 12 -o /dev/null -w "%{http_code} %{time_total}s\n" "https://$2/"
done
