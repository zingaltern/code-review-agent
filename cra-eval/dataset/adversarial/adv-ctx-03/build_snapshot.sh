#!/usr/bin/env bash
# 重建 adv-ctx-03 合成快照（06§3：tgz 不入 git；三提交历史 root → constraint → head）。
# 用法：
#   bash build_snapshot.sh --emit-shas   # 首次填 meta：只打印 BASE_SHA/HEAD_SHA
#   bash build_snapshot.sh               # 默认：校验 meta SHA 对齐，生成 diff.patch 与 repo_snapshot.tgz
# 确定性：固定身份/日期/消息 + 隔离全局 git 配置；macOS 与 ubuntu-latest 上 commit SHA 一致（tgz 字节不要求一致）。
set -euo pipefail

CASE_DIR="$(cd "$(dirname "$0")" && pwd)"
cd "$CASE_DIR"

export GIT_CONFIG_GLOBAL=/dev/null
export GIT_CONFIG_SYSTEM=/dev/null
export GIT_CONFIG_NOSYSTEM=1

ROOT_TREE="$CASE_DIR/history/01-root"
CONSTRAINT_TREE="$CASE_DIR/history/02-constraint"
HEAD_TREE="$CASE_DIR/files"

ROOT_DATE="2026-01-10T10:00:00+08:00"
CONSTRAINT_DATE="2026-09-20T09:30:00+08:00"
HEAD_DATE="2026-10-20T12:00:00+08:00"

ROOT_MSG="chore: initial pagination skeleton"
CONSTRAINT_MSG="fix: pin default page size to 20 for frontend pagination contract (INC-2145, ref 3a7f5c2)"
HEAD_MSG="feat: raise default page size to 50; add page response builder"

EMIT_SHAS=0
if [ "${1:-}" = "--emit-shas" ]; then
  EMIT_SHAS=1
fi

TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT

git init -q -b main "$TMP/repo"
cd "$TMP/repo"
git config core.autocrlf false
git config core.eol lf
git config commit.gpgsign false
git config user.name "cra-eval-fixture"
git config user.email "fixture@example.invalid"

commit_tree() { # $1=source tree dir $2=author/committer date $3=message（--checksum：size+mtime 相同也须按内容覆盖，20→50 同长陷阱）
  rsync -a --checksum --delete --exclude .git "$1"/ ./
  git add -A
  GIT_AUTHOR_DATE="$2" GIT_COMMITTER_DATE="$2" git commit -q -m "$3"
}

commit_tree "$ROOT_TREE" "$ROOT_DATE" "$ROOT_MSG"
commit_tree "$CONSTRAINT_TREE" "$CONSTRAINT_DATE" "$CONSTRAINT_MSG"
commit_tree "$HEAD_TREE" "$HEAD_DATE" "$HEAD_MSG"

BASE_SHA="$(git rev-parse HEAD~1)"
HEAD_SHA="$(git rev-parse HEAD)"

if [ "$EMIT_SHAS" = "1" ]; then
  echo "BASE_SHA=$BASE_SHA"
  echo "HEAD_SHA=$HEAD_SHA"
  exit 0
fi

BASE_META="$(sed -n 's/.*"base_sha": *"\([0-9a-f]\{40\}\)".*/\1/p' "$CASE_DIR/meta.json")"
HEAD_META="$(sed -n 's/.*"head_sha": *"\([0-9a-f]\{40\}\)".*/\1/p' "$CASE_DIR/meta.json")"
if [ -z "$BASE_META" ] || [ -z "$HEAD_META" ]; then
  echo "ERROR: meta.json 缺少 base_sha/head_sha（先跑 --emit-shas 回填）" >&2
  exit 1
fi
if [ "$BASE_META" != "$BASE_SHA" ] || [ "$HEAD_META" != "$HEAD_SHA" ]; then
  echo "ERROR: meta SHA 与重建不一致：meta($BASE_META,$HEAD_META) rebuilt($BASE_SHA,$HEAD_SHA)" >&2
  exit 1
fi

git diff "$BASE_SHA" "$HEAD_SHA" > "$CASE_DIR/diff.patch"
tar -czf "$CASE_DIR/repo_snapshot.tgz" -C "$TMP/repo" .
echo "OK: base=$BASE_SHA head=$HEAD_SHA diff_lines=$(wc -l < "$CASE_DIR/diff.patch" | tr -d ' ')"
