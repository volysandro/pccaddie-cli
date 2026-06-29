#!/usr/bin/env python3
"""Analyze PC CADDIE scorecard exports.

Reads a CSV produced by `pccaddie.bb export-scorecards` and generates golf
performance statistics, charts, and a Markdown report.

Usage:
    analyze.py [CSV] [--outdir DIR]

If CSV is omitted, the most recent ``scorecards-*.csv`` near the project is used.

Score Differential is the simple WHS form computed per round from that round's
own Course Rating and Slope Rating:

    Score Differential = (113 / Slope Rating) x (Gross Score - Course Rating)

For the 9-hole rounds in this export the ratings are 9-hole ratings, so this is
a genuine 9-hole differential. No adjusted-gross (net double bogey) cap and no
9->18 projection are applied; everything is computed straight from gross scores.
"""
from __future__ import annotations

import argparse
import glob
import os
from datetime import datetime

import numpy as np
import pandas as pd
import matplotlib

matplotlib.use("Agg")  # headless / file output only
import matplotlib.pyplot as plt
import matplotlib.dates as mdates
import seaborn as sns

STANDARD_SLOPE = 113  # WHS neutral slope
HOLE_RANGE = range(1, 19)

sns.set_theme(style="whitegrid", context="talk")
PALETTE = sns.color_palette("crest", n_colors=8)


# ---------------------------------------------------------------------------
# Loading & feature engineering
# ---------------------------------------------------------------------------
def find_default_csv() -> str | None:
    here = os.getcwd()
    root = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
    candidates: list[str] = []
    for base in (here, root):
        candidates += glob.glob(os.path.join(base, "scorecards-*.csv"))
        candidates += glob.glob(os.path.join(base, "scorecards.csv"))
    candidates = sorted(set(candidates), key=os.path.getmtime, reverse=True)
    return candidates[0] if candidates else None


def load(path: str) -> pd.DataFrame:
    # utf-8-sig transparently strips the BOM the exporter writes for Excel.
    df = pd.read_csv(path, encoding="utf-8-sig", dtype={"scorecard_id": str, "club_id": str})
    df["date"] = pd.to_datetime(df["date"], errors="coerce")
    df = df.sort_values("date").reset_index(drop=True)

    numeric = ["handicap", "playing_handicap", "new_handicap", "course_rating",
               "slope_rating", "par", "gross_total"]
    numeric += [f"hole_{i}_{k}" for i in HOLE_RANGE for k in ("gross", "par", "si")]
    for col in numeric:
        if col in df.columns:
            df[col] = pd.to_numeric(df[col], errors="coerce")
    return df


def holes_played(row: pd.Series) -> int:
    return int(sum(pd.notna(row.get(f"hole_{i}_par")) and row.get(f"hole_{i}_par") > 0
                   for i in HOLE_RANGE))


def enrich(df: pd.DataFrame) -> pd.DataFrame:
    df = df.copy()
    df["holes_played"] = df.apply(holes_played, axis=1)
    df["to_par"] = df["gross_total"] - df["par"]
    df["to_par_per_hole"] = df["to_par"] / df["holes_played"].clip(lower=1)
    # Simple 9-hole Score Differential from raw gross (no AGS / no 18-projection).
    df["differential"] = (STANDARD_SLOPE / df["slope_rating"]) * (df["gross_total"] - df["course_rating"])
    df["year"] = df["date"].dt.year
    df["month"] = df["date"].dt.to_period("M").dt.to_timestamp()
    return df


def per_hole_long(df: pd.DataFrame) -> pd.DataFrame:
    """Explode wide per-hole columns into a tidy long frame: one row per
    (round, hole) with par, stroke index, gross, and result."""
    records = []
    for _, row in df.iterrows():
        for i in HOLE_RANGE:
            par = row.get(f"hole_{i}_par")
            gross = row.get(f"hole_{i}_gross")
            if pd.isna(par) or par <= 0 or pd.isna(gross) or gross <= 0:
                continue
            records.append({
                "date": row["date"], "course": row["course"], "club": row["club"],
                "hole": i, "par": int(par), "si": row.get(f"hole_{i}_si"),
                "gross": int(gross), "vs_par": int(gross - par),
            })
    long = pd.DataFrame.from_records(records)
    if not long.empty:
        long["result"] = long["vs_par"].map(result_label)
    return long


RESULT_ORDER = ["Eagle+", "Birdie", "Par", "Bogey", "Double", "Triple+"]
RESULT_COLORS = dict(zip(RESULT_ORDER, sns.color_palette("RdYlGn_r", len(RESULT_ORDER))))


def result_label(vs_par: int) -> str:
    if vs_par <= -2:
        return "Eagle+"
    return {-1: "Birdie", 0: "Par", 1: "Bogey", 2: "Double"}.get(vs_par, "Triple+")


# ---------------------------------------------------------------------------
# Charts
# ---------------------------------------------------------------------------
def _save(fig, outdir, name) -> str:
    fig.tight_layout()
    fig.savefig(os.path.join(outdir, name), dpi=120, bbox_inches="tight")
    plt.close(fig)
    return name


def _save_dated(fig, ax, outdir, name) -> str:
    """Save a chart whose x-axis is dates, rotating the tick labels so the
    month/year labels don't overlap."""
    ax.xaxis.set_major_formatter(mdates.DateFormatter("%b %Y"))
    fig.autofmt_xdate(rotation=30)
    return _save(fig, outdir, name)


def chart_differential_over_time(df, outdir) -> str:
    d = df.dropna(subset=["differential"])
    fig, ax = plt.subplots(figsize=(12, 6))
    ax.plot(d["date"], d["differential"], "o-", color=PALETTE[3], alpha=0.5, label="Per round")
    roll = d.set_index("date")["differential"].rolling(8, min_periods=3).mean()
    ax.plot(roll.index, roll.values, color=PALETTE[6], lw=3, label="Rolling mean (8)")
    ax.set_title("9-hole Score Differential over time")
    ax.set_ylabel("Score Differential")
    ax.legend()
    return _save_dated(fig, ax, outdir, "differential_over_time.png")


def chart_handicap_over_time(df, outdir) -> str:
    d = df.dropna(subset=["handicap"])
    if d.empty:
        return ""
    fig, ax = plt.subplots(figsize=(12, 6))
    ax.plot(d["date"], d["handicap"], "o-", color=PALETTE[4], label="HCPI at round")
    ax.set_title("Handicap Index over time")
    ax.set_ylabel("HCPI (lower is better)")
    ax.invert_yaxis()
    ax.legend()
    return _save_dated(fig, ax, outdir, "handicap_over_time.png")


def chart_to_par_over_time(df, outdir) -> str:
    d = df.dropna(subset=["to_par_per_hole"])
    fig, ax = plt.subplots(figsize=(12, 6))
    mean = d["to_par_per_hole"].mean()
    colors = [PALETTE[2] if v <= mean else PALETTE[6] for v in d["to_par_per_hole"]]
    ax.bar(d["date"], d["to_par_per_hole"], width=4, color=colors)
    ax.axhline(mean, color="black", ls="--", lw=1, label=f"Mean {mean:+.2f}/hole")
    ax.set_title("Scoring vs par per hole, by round")
    ax.set_ylabel("Strokes over par / hole")
    ax.legend()
    return _save_dated(fig, ax, outdir, "to_par_over_time.png")


def chart_rolling_gross(df, outdir) -> str:
    d = df.dropna(subset=["gross_total"])
    fig, ax = plt.subplots(figsize=(12, 6))
    ax.plot(d["date"], d["gross_total"], "o", color=PALETTE[2], alpha=0.4, label="Gross")
    roll = d.set_index("date")["gross_total"].rolling(5, min_periods=2).mean()
    ax.plot(roll.index, roll.values, color=PALETTE[6], lw=3, label="Rolling mean (5)")
    ax.set_title("Gross score over time")
    ax.set_ylabel("Gross (9 holes)")
    ax.legend()
    return _save_dated(fig, ax, outdir, "gross_over_time.png")


def chart_result_distribution(long, outdir) -> str:
    if long.empty:
        return ""
    counts = long["result"].value_counts().reindex(RESULT_ORDER).fillna(0)
    pct = 100 * counts / counts.sum()
    fig, ax = plt.subplots(figsize=(10, 6))
    bars = ax.bar(counts.index, counts.values, color=[RESULT_COLORS[r] for r in counts.index])
    for bar, p in zip(bars, pct.values):
        ax.text(bar.get_x() + bar.get_width() / 2, bar.get_height(), f"{p:.0f}%",
                ha="center", va="bottom")
    ax.set_title("Distribution of hole results")
    ax.set_ylabel("Holes")
    return _save(fig, outdir, "result_distribution.png")


def chart_result_mix_by_par(long, outdir) -> str:
    if long.empty:
        return ""
    pivot = (long.groupby(["par", "result"]).size().unstack(fill_value=0)
             .reindex(columns=RESULT_ORDER, fill_value=0))
    pct = pivot.div(pivot.sum(axis=1), axis=0) * 100
    fig, ax = plt.subplots(figsize=(10, 6))
    bottom = np.zeros(len(pct))
    for r in RESULT_ORDER:
        ax.bar([f"Par {int(p)}" for p in pct.index], pct[r], bottom=bottom,
               label=r, color=RESULT_COLORS[r])
        bottom += pct[r].values
    ax.set_title("Result mix by par type")
    ax.set_ylabel("% of holes")
    ax.legend(bbox_to_anchor=(1.02, 1), loc="upper left", fontsize=12)
    return _save(fig, outdir, "result_mix_by_par.png")


def chart_by_par_type(long, outdir) -> str:
    if long.empty:
        return ""
    agg = long.groupby("par")["vs_par"].agg(["mean", "count"])
    fig, ax = plt.subplots(figsize=(9, 6))
    bars = ax.bar([f"Par {int(p)}\n(n={int(c)})" for p, c in zip(agg.index, agg["count"])],
                  agg["mean"], color=sns.color_palette("crest", len(agg)))
    for bar, v in zip(bars, agg["mean"]):
        ax.text(bar.get_x() + bar.get_width() / 2, bar.get_height(), f"{v:+.2f}",
                ha="center", va="bottom")
    ax.axhline(0, color="black", lw=1)
    ax.set_title("Average strokes vs par, by par type")
    ax.set_ylabel("Avg strokes over par")
    return _save(fig, outdir, "by_par_type.png")


def chart_hole_performance(long, outdir) -> str:
    """Average score vs par per hole, for the most-played course."""
    if long.empty:
        return ""
    top = long["course"].value_counts().idxmax()
    agg = long[long["course"] == top].groupby("hole")["vs_par"].mean()
    fig, ax = plt.subplots(figsize=(12, 6))
    colors = [PALETTE[2] if v <= 0 else PALETTE[6] for v in agg.values]
    ax.bar(agg.index, agg.values, color=colors)
    ax.axhline(0, color="black", lw=1)
    ax.set_title(f"Avg vs par by hole - {top}")
    ax.set_xlabel("Hole")
    ax.set_ylabel("Avg strokes over par")
    ax.set_xticks(list(agg.index))
    return _save(fig, outdir, "hole_performance.png")


def chart_differential_histogram(df, outdir) -> str:
    d = df["differential"].dropna()
    if d.empty:
        return ""
    fig, ax = plt.subplots(figsize=(10, 6))
    sns.histplot(d, kde=True, ax=ax, color=PALETTE[4], bins=12)
    ax.axvline(d.mean(), color="black", ls="--", label=f"Mean {d.mean():.1f}")
    ax.set_title("Distribution of 9-hole Score Differentials")
    ax.set_xlabel("Score Differential")
    ax.legend()
    return _save(fig, outdir, "differential_histogram.png")


def chart_rounds_by_course(df, outdir) -> str:
    counts = df["course"].value_counts().head(12).sort_values()
    fig, ax = plt.subplots(figsize=(11, max(4, 0.5 * len(counts) + 2)))
    ax.barh(counts.index, counts.values, color=sns.color_palette("crest", len(counts)))
    for i, v in enumerate(counts.values):
        ax.text(v, i, f" {v}", va="center")
    ax.set_title("Rounds played by course")
    ax.set_xlabel("Rounds")
    return _save(fig, outdir, "rounds_by_course.png")


def chart_monthly_average(df, outdir) -> str:
    d = df.dropna(subset=["to_par_per_hole"])
    if d.empty:
        return ""
    agg = d.groupby("month")["to_par_per_hole"].mean()
    fig, ax = plt.subplots(figsize=(12, 6))
    ax.plot(agg.index, agg.values, "o-", color=PALETTE[5], lw=2)
    ax.set_title("Monthly average scoring vs par per hole")
    ax.set_ylabel("Avg strokes over par / hole")
    return _save_dated(fig, ax, outdir, "monthly_average.png")


# ---------------------------------------------------------------------------
# Summary & report
# ---------------------------------------------------------------------------
def summary_stats(df, long) -> dict:
    d = df.dropna(subset=["differential"])
    counts = long["result"].value_counts() if not long.empty else pd.Series(dtype=int)
    n = len(long)
    better = int(counts.get("Birdie", 0) + counts.get("Eagle+", 0))
    return {
        "Rounds": len(df),
        "Date range": f"{df['date'].min():%Y-%m-%d} -> {df['date'].max():%Y-%m-%d}",
        "Courses played": df["course"].nunique(),
        "Clubs played": df["club"].nunique(),
        "Holes recorded": n,
        "Avg gross (per round)": f"{df['gross_total'].mean():.1f}",
        "Best gross": f"{df['gross_total'].min():.0f}",
        "Avg vs par / hole": f"{df['to_par_per_hole'].mean():+.2f}",
        "Best round (vs par)": f"{df['to_par'].min():+.0f}",
        "Avg score differential": f"{d['differential'].mean():.1f}",
        "Best score differential": f"{d['differential'].min():.1f}",
        "Latest recorded HCPI": (f"{df.dropna(subset=['handicap'])['handicap'].iloc[-1]:.1f}"
                                 if df["handicap"].notna().any() else "n/a"),
        "Birdies or better": better,
        "Pars": int(counts.get("Par", 0)),
        "Par-or-better rate": f"{100 * (counts.get('Par', 0) + better) / n:.1f}%" if n else "n/a",
    }


def write_report(df, long, stats, charts, outdir) -> str:
    L = ["# Golf Scorecard Analysis", "",
         f"_Generated {datetime.now():%Y-%m-%d %H:%M} from {len(df)} scorecards._", "",
         "Score Differential = (113 / Slope) x (Gross - Course Rating), computed per "
         "round from that round's ratings (9-hole differentials here; no AGS, no 9->18 projection).",
         "", "## Summary", "", "| Metric | Value |", "| --- | --- |"]
    L += [f"| {k} | {v} |" for k, v in stats.items()]

    titles = {
        "differential_over_time.png": "Score differential over time (with rolling average)",
        "handicap_over_time.png": "Handicap index over time",
        "gross_over_time.png": "Gross score over time",
        "to_par_over_time.png": "Scoring vs par per round",
        "monthly_average.png": "Monthly average scoring",
        "differential_histogram.png": "Score differential distribution",
        "result_distribution.png": "Hole-result distribution",
        "result_mix_by_par.png": "Result mix by par type",
        "by_par_type.png": "Average performance by par type",
        "hole_performance.png": "Per-hole performance (most-played course)",
        "rounds_by_course.png": "Rounds by course",
    }
    L += ["", "## Charts", ""]
    for name in charts:
        if name:
            L += [f"### {titles.get(name, name)}", "", f"![{name}]({name})", ""]

    if not long.empty:
        L += ["## Scoring by par type", "",
              "| Par | Holes | Avg score | Avg vs par |", "| --- | --- | --- | --- |"]
        for par, g in long.groupby("par"):
            L.append(f"| {int(par)} | {len(g)} | {g['gross'].mean():.2f} | {g['vs_par'].mean():+.2f} |")
        L.append("")

    by_course = df.groupby("course").agg(
        rounds=("course", "size"), avg_gross=("gross_total", "mean"),
        avg_to_par_hole=("to_par_per_hole", "mean"), avg_diff=("differential", "mean"),
    ).sort_values("rounds", ascending=False)
    L += ["## By course", "",
          "| Course | Rounds | Avg gross | Avg vs par/hole | Avg differential |",
          "| --- | --- | --- | --- | --- |"]
    for course, r in by_course.iterrows():
        L.append(f"| {course} | {int(r['rounds'])} | {r['avg_gross']:.1f} | "
                 f"{r['avg_to_par_hole']:+.2f} | {r['avg_diff']:.1f} |")
    L.append("")

    path = os.path.join(outdir, "report.md")
    with open(path, "w", encoding="utf-8") as fh:
        fh.write("\n".join(L))
    return path


def main(argv=None):
    ap = argparse.ArgumentParser(description=__doc__,
                                 formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("csv", nargs="?", help="scorecards CSV (default: newest scorecards-*.csv)")
    ap.add_argument("--outdir", default=os.path.join(os.path.dirname(os.path.abspath(__file__)), "output"),
                    help="output directory for charts and report")
    args = ap.parse_args(argv)

    csv = args.csv or find_default_csv()
    if not csv or not os.path.exists(csv):
        ap.error("no CSV found - pass one explicitly or export scorecards first")
    os.makedirs(args.outdir, exist_ok=True)

    print(f"Reading {csv}")
    df = enrich(load(csv))
    long = per_hole_long(df)
    print(f"  {len(df)} rounds, {len(long)} holes")

    charts = [
        chart_differential_over_time(df, args.outdir),
        chart_handicap_over_time(df, args.outdir),
        chart_rolling_gross(df, args.outdir),
        chart_to_par_over_time(df, args.outdir),
        chart_monthly_average(df, args.outdir),
        chart_differential_histogram(df, args.outdir),
        chart_result_distribution(long, args.outdir),
        chart_result_mix_by_par(long, args.outdir),
        chart_by_par_type(long, args.outdir),
        chart_hole_performance(long, args.outdir),
        chart_rounds_by_course(df, args.outdir),
    ]

    stats = summary_stats(df, long)
    report = write_report(df, long, stats, charts, args.outdir)

    keep = ["date", "club", "course", "tee", "holes_played", "par", "gross_total",
            "to_par", "to_par_per_hole", "handicap", "playing_handicap",
            "course_rating", "slope_rating", "differential"]
    df[keep].to_csv(os.path.join(args.outdir, "rounds_enriched.csv"), index=False)

    print("\nSummary")
    for k, v in stats.items():
        print(f"  {k:28s} {v}")
    print(f"\nWrote report  -> {report}")
    print(f"Wrote charts  -> {args.outdir}/*.png")
    print(f"Wrote table   -> {args.outdir}/rounds_enriched.csv")


if __name__ == "__main__":
    main()
