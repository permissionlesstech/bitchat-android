#!/usr/bin/env bash
set -euo pipefail

usage() {
  cat <<'EOF'
Create an isolated Android UI review worktree.

Usage:
  create_review_worktree.sh --target <PR-URL|PR-NUMBER|BRANCH|COMMIT> [options]

Options:
  --base <REF>       Base for a branch/commit target (default: main)
  --repo-root <DIR>  Repository root (default: current repository)
  -h, --help         Show this help

The script never removes an existing worktree. It prints and writes session.env
with the before/after SHAs and artifact paths.
EOF
}

target=""
base_ref=""
repo_root="."

while [[ $# -gt 0 ]]; do
  case "$1" in
    --target)
      target="${2:-}"
      shift 2
      ;;
    --base)
      base_ref="${2:-}"
      shift 2
      ;;
    --repo-root)
      repo_root="${2:-}"
      shift 2
      ;;
    -h|--help)
      usage
      exit 0
      ;;
    *)
      echo "error: unknown argument: $1" >&2
      usage >&2
      exit 2
      ;;
  esac
done

if [[ -z "$target" ]]; then
  echo "error: --target is required" >&2
  exit 2
fi
if [[ "$target" == -* || "$base_ref" == -* ]]; then
  echo "error: target and base refs cannot begin with '-'" >&2
  exit 2
fi

repo_root="$(git -C "$repo_root" rev-parse --show-toplevel)"
cd "$repo_root"

target_kind="ref"
pr_number=""
base_name=""
head_ref=""

if [[ "$target" =~ ^[0-9]+$ ]]; then
  target_kind="pr"
  pr_number="${BASH_REMATCH[0]}"
elif [[ "$target" =~ ^https://github\.com/([^/]+)/([^/]+)/pull/([0-9]+)/?$ ]]; then
  target_kind="pr"
  pr_number="${BASH_REMATCH[3]}"
  url_repo="${BASH_REMATCH[1]}/${BASH_REMATCH[2]}"
  current_repo="$(gh repo view --json nameWithOwner --jq .nameWithOwner)"
  if [[ "${url_repo,,}" != "${current_repo,,}" ]]; then
    echo "error: PR URL targets $url_repo but this checkout is $current_repo" >&2
    exit 2
  fi
fi

resolve_target_ref() {
  local ref="$1"
  local resolved=""

  if git show-ref --verify --quiet "refs/heads/${ref}"; then
    git rev-parse --verify "refs/heads/${ref}^{commit}"
    return
  fi

  if [[ "$ref" == refs/* || "$ref" == *"~"* || "$ref" == *"^"* ]] &&
    resolved="$(git rev-parse --verify "${ref}^{commit}" 2>/dev/null)"; then
    printf '%s\n' "$resolved"
    return
  fi

  if git check-ref-format --branch "$ref" >/dev/null 2>&1; then
    if git fetch origin "$ref" >/dev/null 2>&1; then
      git rev-parse --verify 'FETCH_HEAD^{commit}'
      return
    fi
  fi

  if resolved="$(git rev-parse --verify "${ref}^{commit}" 2>/dev/null)"; then
    printf '%s\n' "$resolved"
    return
  fi

  echo "error: cannot resolve ref: $ref" >&2
  return 1
}

resolve_base_ref() {
  local ref="$1"
  local resolved=""

  if git check-ref-format --branch "$ref" >/dev/null 2>&1; then
    if git fetch origin "$ref" >/dev/null 2>&1; then
      git rev-parse --verify 'FETCH_HEAD^{commit}'
      return
    fi
  fi

  if resolved="$(git rev-parse --verify "${ref}^{commit}" 2>/dev/null)"; then
    printf '%s\n' "$resolved"
    return
  fi

  echo "error: cannot resolve base ref: $ref" >&2
  return 1
}

if [[ "$target_kind" == "pr" ]]; then
  repo_slug="$(gh repo view --json nameWithOwner --jq .nameWithOwner)"
  pr_data="$(gh pr view "$pr_number" --repo "$repo_slug" \
    --json baseRefName,headRefOid \
    --jq '"\(.baseRefName) \(.headRefOid)"')"
  read -r base_name reported_head_sha <<<"$pr_data"

  head_ref="refs/pr-visual-review/pull/${pr_number}/head"
  git fetch origin "+pull/${pr_number}/head:${head_ref}"
  git fetch origin "+refs/heads/${base_name}:refs/remotes/origin/${base_name}"

  after_sha="$(git rev-parse --verify "${head_ref}^{commit}")"
  if [[ "$after_sha" != "$reported_head_sha" ]]; then
    latest_reported_head="$(gh pr view "$pr_number" --repo "$repo_slug" \
      --json headRefOid --jq .headRefOid)"
    if [[ "$after_sha" != "$latest_reported_head" ]]; then
      echo "error: PR head changed while resolving; rerun for a consistent target" >&2
      exit 1
    fi
  fi

  base_sha="$(git rev-parse --verify "refs/remotes/origin/${base_name}^{commit}")"
else
  after_sha="$(resolve_target_ref "$target")"
  base_name="${base_ref:-main}"
  base_sha="$(resolve_base_ref "$base_name")"
fi

before_sha="$(git merge-base "$base_sha" "$after_sha")"
if [[ -z "$before_sha" ]]; then
  echo "error: no merge-base found between $base_name and $target" >&2
  exit 1
fi

session_dir="$(mktemp -d /tmp/bitchat-ui-review.XXXXXX)"
worktree_path="${session_dir}/worktree"
artifact_dir="${session_dir}/artifacts"
mkdir -p "$artifact_dir"

git worktree add --detach "$worktree_path" "$before_sha"

if [[ -f "${repo_root}/local.properties" && ! -e "${worktree_path}/local.properties" ]]; then
  ln -s "${repo_root}/local.properties" "${worktree_path}/local.properties"
fi

session_env="${session_dir}/session.env"
{
  printf 'SESSION_DIR=%q\n' "$session_dir"
  printf 'WORKTREE_PATH=%q\n' "$worktree_path"
  printf 'ARTIFACT_DIR=%q\n' "$artifact_dir"
  printf 'SOURCE_REPO_ROOT=%q\n' "$repo_root"
  printf 'TARGET_KIND=%q\n' "$target_kind"
  printf 'TARGET_INPUT=%q\n' "$target"
  printf 'BASE_NAME=%q\n' "$base_name"
  printf 'BEFORE_SHA=%q\n' "$before_sha"
  printf 'AFTER_SHA=%q\n' "$after_sha"
  printf 'PR_NUMBER=%q\n' "$pr_number"
} > "$session_env"

printf 'SESSION_DIR=%s\n' "$session_dir"
printf 'WORKTREE_PATH=%s\n' "$worktree_path"
printf 'ARTIFACT_DIR=%s\n' "$artifact_dir"
printf 'BEFORE_SHA=%s\n' "$before_sha"
printf 'AFTER_SHA=%s\n' "$after_sha"
printf 'TARGET_KIND=%s\n' "$target_kind"
if [[ -n "$pr_number" ]]; then
  printf 'PR_NUMBER=%s\n' "$pr_number"
fi
printf 'SESSION_ENV=%s\n' "$session_env"
