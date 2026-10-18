#!/usr/bin/env bash
# Fetch the target at its pinned commit, apply the StateVerdict adapter, and build its services.
#   targets/piomin-saga/prepare.sh             -> build/targets/piomin-saga/<service>/target/*.jar
#   targets/piomin-saga/prepare.sh --upstream  -> build/targets/piomin-saga-upstream/..., unpatched
#                                                 (the baseline for the overhead benchmark)
# The target's code is downloaded at build time and never committed to this repository.
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
ROOT="$(cd "${HERE}/../.." && pwd)"
# shellcheck source=target.env
source "${HERE}/target.env"
UPSTREAM=false
[ "${1:-}" = "--upstream" ] && UPSTREAM=true
SRC="${ROOT}/build/targets/piomin-saga"
${UPSTREAM} && SRC="${ROOT}/build/targets/piomin-saga-upstream"

rm -rf "${SRC}" && mkdir -p "${SRC}"
git -C "${SRC}" init -q
git -C "${SRC}" fetch -q --depth 1 "${REPO}" "${COMMIT}"
git -C "${SRC}" checkout -q FETCH_HEAD
actual="$(git -C "${SRC}" rev-parse HEAD)"
[ "${actual}" = "${COMMIT}" ] || { echo "expected commit ${COMMIT}, got ${actual}"; exit 1; }
if ${UPSTREAM}; then
  echo "Upstream build: no adapter applied"
else
  git -C "${SRC}" apply "${HERE}/adapter.patch"
  git -C "${SRC}" add -A
  echo "Applied the adapter:$(git -C "${SRC}" diff --cached --shortstat)"
  # The adapter depends on StateVerdict's dispatch library; install it into the local repository.
  (cd "${ROOT}" && mvn -B -q install -pl libs/stateverdict-dispatch-spring -am -DskipTests)
fi
(cd "${SRC}" && mvn -B -q package -DskipTests)
ls "${SRC}"/*-service/target/*-service-*.jar
