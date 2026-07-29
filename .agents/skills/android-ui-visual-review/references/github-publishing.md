# Publishing screenshots to a pull request

Follow this only after the user explicitly opts into a GitHub comment.

## Prepare a safe comment

The comment should contain:

- a unique hidden marker for idempotent verification
- verified Android release/API
- statement that before is the target's actual merge-base
- concise visual findings
- paired before/after image tables
- fixture disclosure when artificial state was used
- limitations and intentional non-changes

Do not include:

- local paths or usernames
- ADB serials, device names, peer IDs, addresses, or IPs
- real messages, contacts, locations, or account information
- build logs
- claims about physical behavior that screenshots do not prove

Scan the body locally for machine paths and selectors before posting.
Use [../assets/pr-comment-template.md](../assets/pr-comment-template.md) as the
starting structure, replacing every uppercase placeholder and adding one table
per comparison state.

## Upload image bytes without changing a source branch

Native `gh pr comment` accepts Markdown but not binary files. This skill bundles
an uploader that uses `gh api` to place images on a PR-scoped custom Git ref:

```sh
python3 \
  .agents/skills/android-ui-visual-review/scripts/upload_pr_images.py \
  --repo OWNER/REPO \
  --pr NUMBER \
  <ordered PNG files>
```

The helper:

- uploads only image bytes and basenames
- writes to `refs/uploads/issues/<NUMBER>`, outside `refs/heads/*`
- does not pass an author or committer override
- prints JSON containing stable GitHub blob URLs
- does not post a comment

This is a GitHub repository write even though it does not create a visible
branch. The user's approval to publish the screenshots authorizes this
PR-scoped storage. If repository policy rejects custom refs, use an authenticated
GitHub web attachment composer or ask the user for an approved image host. Do
not fall back to a source branch without separate authorization.

Use `--dry-run` first when validating new inputs:

```sh
python3 \
  .agents/skills/android-ui-visual-review/scripts/upload_pr_images.py \
  --dry-run \
  --repo OWNER/REPO \
  --pr NUMBER \
  <ordered PNG files>
```

## Post through gh

Build the final Markdown body with the returned URLs. Keep before and after in
the same table row. Put extra after-state details, such as an expanded menu, in
a separate labeled table.

Post once:

```sh
gh pr comment NUMBER \
  --repo OWNER/REPO \
  --body-file "<validated-comment.md>"
```

Record the returned comment URL.

## Verify the side effect

Read the comment back using `gh api` or `gh pr view`. Verify:

- the unique marker is present
- image embed count equals the requested screenshot count
- no local paths or identifiers were included
- the returned URL belongs to the intended PR

If the posting command's outcome is ambiguous, query for the marker before
retrying. Do not create duplicate comments.

Report the comment URL to the user. Leave the PR-scoped image ref in place while
the comment depends on it; deleting the ref can eventually break the images.
