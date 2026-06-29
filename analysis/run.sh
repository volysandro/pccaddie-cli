#!/usr/bin/env bash
# Repeatable runner: creates the virtualenv on first use, installs deps, then
# runs the analysis. Pass a CSV path and/or --outdir; both are optional.
#
#   ./run.sh                                 # newest scorecards-*.csv -> output/
#   ./run.sh ../scorecards-2026-06-29.csv    # a specific export
#   ./run.sh --outdir /tmp/report            # custom output dir
set -euo pipefail

DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
VENV="$DIR/.venv"

if [ ! -x "$VENV/bin/python" ]; then
  echo "Creating virtualenv at $VENV"
  python3 -m venv "$VENV"
  "$VENV/bin/python" -m pip install --quiet --upgrade pip
  "$VENV/bin/python" -m pip install --quiet -r "$DIR/requirements.txt"
fi

exec "$VENV/bin/python" "$DIR/analyze.py" "$@"
