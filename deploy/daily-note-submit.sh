#!/usr/bin/env bash
set -euo pipefail
umask 077

inbox=${BLOG_EDITOR_DIRECTORY:-/var/lib/ease2k-blog-springboot-postgresql/editor}/daily-note-inbox
request="$inbox/request.json"
result="$inbox/result.json"
test -d "$inbox"
exec 9>"$inbox/submit.lock"
flock -w 8 9

if [[ -e "$request" ]]; then
    echo '上一次提交仍在等待处理。' >&2
    exit 1
fi

temporary=$(mktemp "$inbox/request.XXXXXXXX")
trap 'rm -f -- "$temporary"' EXIT
case "${1:-}" in
    --check) printf '%s\n' '{"check":true}' > "$temporary" ;;
    '') head -c 32769 > "$temporary" ;;
    *) echo '未知的小记提交选项。' >&2; exit 2 ;;
esac
if [[ ! -s "$temporary" || $(wc -c < "$temporary") -gt 32768 ]]; then
    echo '小记数据为空或超出大小限制。' >&2
    exit 2
fi

rm -f -- "$result"
mv -T -- "$temporary" "$request"
for ((attempt = 0; attempt < 20; attempt++)); do
    if [[ ! -e "$request" && -f "$result" ]]; then
        cat "$result"
        printf '\n'
        if grep -q '"status":"error"' "$result"; then exit 1; fi
        exit 0
    fi
    sleep 1
done
echo '站点尚未确认小记提交。' >&2
exit 1
