#!/usr/bin/env bash
# ==============================================================================================
# CI gate — the PostgreSQL RLS / Testcontainers suite must ACTUALLY RUN, and must never be skipped.
#
# Why this exists: `mvn verify` exits 0 when a test is skipped, and Testcontainers silently no-ops
# (or the test simply never executes) when Docker is unavailable. Either way the RLS tenant-isolation
# proof would be ABSENT from CI while every check still looks green. This gate turns that silent gap
# into a hard build failure.
#
# It asserts, from the surefire reports that `mvn verify` just produced:
#   1. Docker is reachable (Testcontainers cannot start otherwise).
#   2. RlsTenantIsolationTest has a report — i.e. it actually executed.
#   3. It executed MORE THAN ZERO tests.
#   4. It has ZERO skips, ZERO failures, ZERO errors.
#   5. NO test in the entire run was skipped (the suite contains no @Disabled / assumption-based
#      tests, so a skip always means the environment failed, never that the test was inapplicable).
#
# EXIT CODE: 0 only when all of the above hold; 1 otherwise (fails the pipeline).
#
# STATUS: WRITTEN, NOT YET EXECUTED IN CI. Verified only against synthetic surefire XML fixtures
# locally (no Maven/JVM in the authoring sandbox). See docs/audits/PHASE-A-REMEDIATION-DESIGN-2026-10-11.md.
#
# USAGE (from the backend/ directory, after `mvn verify`):
#   ../scripts/ci-rls-gate.sh
#   REPORTS_DIR=backend/target/surefire-reports ../scripts/ci-rls-gate.sh
# ==============================================================================================
set -euo pipefail

REPORTS_DIR="${REPORTS_DIR:-target/surefire-reports}"
RLS_CLASS="${RLS_TEST_CLASS:-com.jotheca.tenancy.RlsTenantIsolationTest}"
RLS_REPORT="${REPORTS_DIR}/TEST-${RLS_CLASS}.xml"

die() { echo "::error::$*"; echo "RLS GATE FAILED: $*"; exit 1; }

# ---------------------------------------------------------------- 1. Docker must be reachable
if ! command -v docker >/dev/null 2>&1; then
  die "docker is not on PATH — Testcontainers cannot start, so the RLS suite cannot run."
fi
if ! docker info >/dev/null 2>&1; then
  die "Docker daemon is unreachable (docker info failed) — Testcontainers cannot start, so the RLS suite cannot run."
fi
echo "RLS GATE: docker daemon reachable."

# ---------------------------------------------------------------- 2. the report must exist
[ -d "$REPORTS_DIR" ] || die "no surefire reports at ${REPORTS_DIR} — did 'mvn verify' run the tests?"
[ -f "$RLS_REPORT" ] || die "${RLS_REPORT} is missing — ${RLS_CLASS} never executed."

# ---------------------------------------------------------------- 3-5. parse the reports
REPORTS_DIR="$REPORTS_DIR" RLS_REPORT="$RLS_REPORT" python3 - <<'PY' || exit 1
import glob
import os
import sys
import xml.etree.ElementTree as ET

reports = os.environ["REPORTS_DIR"]
rls_report = os.environ["RLS_REPORT"]


def attr(root, name):
    try:
        return int(root.get(name, 0))
    except (TypeError, ValueError):
        return 0


# --- the RLS suite itself ---------------------------------------------------------------
root = ET.parse(rls_report).getroot()
tests, failures, errors, skipped = (attr(root, "tests"), attr(root, "failures"),
                                    attr(root, "errors"), attr(root, "skipped"))
print(f"RLS GATE: {os.path.basename(rls_report)} -> tests={tests} "
      f"failures={failures} errors={errors} skipped={skipped}")

if tests == 0:
    print(f"::error::{rls_report} executed ZERO tests — the RLS tenant-isolation proof is absent.")
    sys.exit(1)
if skipped != 0:
    print(f"::error::{rls_report} SKIPPED {skipped} test(s) — Testcontainers/Docker was not "
          f"available. Skipped is NOT a pass.")
    sys.exit(1)
if failures or errors:
    print(f"::error::{rls_report} reported {failures} failure(s) and {errors} error(s).")
    sys.exit(1)

# --- nothing anywhere in the run may be skipped -----------------------------------------
total, offenders = 0, []
for path in sorted(glob.glob(os.path.join(reports, "TEST-*.xml"))):
    r = ET.parse(path).getroot()
    n = attr(r, "skipped")
    total += attr(r, "tests")
    if n:
        offenders.append((os.path.basename(path), n))

print(f"RLS GATE: {total} tests executed across "
      f"{len(glob.glob(os.path.join(reports, 'TEST-*.xml')))} report(s).")
if offenders:
    for name, n in offenders:
        print(f"::error::{name} skipped {n} test(s).")
    print("::error::Skipped tests are not acceptable — the suite has no @Disabled/assumption-based "
          "tests, so a skip always means the environment could not run it.")
    sys.exit(1)

print("RLS GATE PASSED: the PostgreSQL RLS suite really ran, and nothing was skipped.")
PY
