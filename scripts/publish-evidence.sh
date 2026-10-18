#!/usr/bin/env bash
# Commit an evidence record to the `evidence` branch:
#   scripts/publish-evidence.sh RECORD.json WORKFLOW
# Every record is kept under runs/WORKFLOW/<run id>.json. runs/WORKFLOW/latest.json moves only for a
# citable (complete) record, so an outage never replaces the last verified figures.
set -euo pipefail
RECORD=$1 WORKFLOW=$2
RUN_ID=${GITHUB_RUN_ID:?}
dir="$(mktemp -d)/evidence"
trap 'git worktree remove --force "${dir}" > /dev/null 2>&1 || true' EXIT
git config --global user.name "github-actions[bot]"
git config --global user.email "41898282+github-actions[bot]@users.noreply.github.com"
if git ls-remote --exit-code origin refs/heads/evidence > /dev/null 2>&1; then
  git fetch -q --depth 1 origin evidence
  git worktree add -q --detach "${dir}" FETCH_HEAD
else
  git worktree add -q --detach "${dir}"
  git -C "${dir}" checkout -q --orphan "evidence-${RUN_ID}"
  git -C "${dir}" rm -rqf --ignore-unmatch .
  cat > "${dir}/README.md" <<'MD'
# Evidence

Machine-written records of StateVerdict's measurement runs, one file per run, committed by CI so
they outlive GitHub's artifact retention. A figure is published only if its run's record here says
`"citable": true`. `latest.json` in each folder is the most recent citable run.
MD
fi
mkdir -p "${dir}/runs/${WORKFLOW}"
cp "${RECORD}" "${dir}/runs/${WORKFLOW}/${RUN_ID}.json"
if jq -e '.citable' "${RECORD}" > /dev/null; then
  cp "${RECORD}" "${dir}/runs/${WORKFLOW}/latest.json"
fi
git -C "${dir}" add -A
git -C "${dir}" commit -q -m "evidence: ${WORKFLOW} run ${RUN_ID} ($(jq -r .status "${RECORD}"))"
for attempt in 1 2 3; do
  git -C "${dir}" push -q origin HEAD:refs/heads/evidence && exit 0
  git -C "${dir}" pull -q --rebase origin evidence || true
done
echo "::error title=Evidence::could not push the evidence record"; exit 1
