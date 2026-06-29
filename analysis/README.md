# Scorecard analysis

Repeatable analysis of the scorecards exported by `pccaddie.bb export-scorecards`.
Python + pandas + matplotlib/seaborn.

## Quick start

```bash
# from the repo root: export the latest data, then analyze it
./pccaddie.bb export-scorecards
analysis/run.sh
```

`run.sh` creates a virtualenv (`analysis/.venv`) on first use, installs the
dependencies, and runs `analyze.py`. By default it picks the newest
`scorecards-*.csv` near the project and writes everything to `analysis/output/`.

```bash
analysis/run.sh ../scorecards-2026-06-29.csv      # specific export
analysis/run.sh --outdir /tmp/golf-report         # custom output dir
```

## Manual setup (instead of run.sh)

```bash
python3 -m venv analysis/.venv
analysis/.venv/bin/pip install -r analysis/requirements.txt
analysis/.venv/bin/python analysis/analyze.py
```

## Outputs (in `output/`)

- `report.md` — summary table + all charts + by-par-type and by-course tables
- `rounds_enriched.csv` — per-round table with derived metrics (differential, vs-par, …)
- `*.png` — individual charts

## Metrics

- **Score Differential** = `(113 / Slope) x (Gross - Course Rating)`, computed
  per round from that round's own rating/slope. For the 9-hole rounds in the
  export these are 9-hole differentials. No adjusted-gross (net double bogey)
  cap and no 9→18 projection are applied — everything is from raw gross scores.
- **vs par / hole**, **gross**, **HCPI over time**, **result distribution**
  (birdie/par/bogey/…), **by par type**, **per-hole performance**, **monthly
  averages**, **rounds by course**.

Re-export and re-run any time; the report regenerates from scratch.
