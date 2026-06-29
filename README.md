# pccaddie

A small command-line tool for the [PC CADDIE mobile portal](https://mobile.pccaddie.net)
(`mobile.pccaddie.net`). It logs into the shared portal and lets you:

- search the club directory,
- list a club's courses,
- check tee-time availability for any day, and
- export your complete online-scorecard history to CSV.

It's a single [Babashka](https://babashka.org) script with no build step. A
companion [analysis project](analysis/) turns the scorecard export into
performance statistics and charts.

## Requirements

- [Babashka](https://github.com/babashka/babashka#installation) (`bb`)
- A PC CADDIE portal login

## Setup

Copy the example configuration and fill in your login:

```bash
cp config.example.edn config.edn
```

```clojure
{:base-url     "https://mobile.pccaddie.net/clubs/pcco/app.php"
 :user-agent   "Mozilla/5.0 (Linux; Android 10; Mobile)"
 :login        {:user "you@example.com" :password "secret"}
 :default-club 214}                      ; 214 = Golfclub Bern / Golfpark Moossee
```

`config.edn` is git-ignored so your credentials stay out of version control. The
password can instead be supplied through the `PCCADDIE_PASSWORD` environment
variable, which takes precedence over the file:

```bash
export PCCADDIE_PASSWORD='secret'
```

Make the script executable once:

```bash
chmod +x pccaddie.bb
```

## Usage

```
./pccaddie.bb --search '<keyword>'        List clubs matching a keyword (+ ids)
./pccaddie.bb show-courses [--id <ID>]    List a club's courses (+ course ids)
./pccaddie.bb show-teetimes [options]     Show tee-time availability
./pccaddie.bb export-scorecards [options] Export every online scorecard to CSV
```

`--id <ID>` overrides the configured default club for a single run only; it never
edits `config.edn`. Change `:default-club` there to set the default permanently.

### Find a club

```bash
./pccaddie.bb --search bern
```

### List a club's courses

```bash
./pccaddie.bb show-courses --id 214
```

### Tee times

```bash
./pccaddie.bb show-teetimes --date tomorrow --free
./pccaddie.bb show-teetimes --date 2026-07-15 --course 9
```

| Option | Description |
| --- | --- |
| `--date <YYYY-MM-DD\|today\|tomorrow\|+N>` | Day to query (default: today) |
| `--course <id\|name>` | Restrict to one course (id from `show-courses`, or a name substring) |
| `--id <ID>` | Query a club other than the default |
| `--free` | Only show times with free seats |
| `--no-color` | Disable ANSI colours |

### Export scorecards

```bash
./pccaddie.bb export-scorecards
./pccaddie.bb export-scorecards --out my-rounds.csv
```

Scorecards are account-wide: the export contains every round you have recorded
across all courses and clubs, regardless of which club is configured. The output
is UTF-8 with a BOM so it opens cleanly in Excel.

| Option | Description |
| --- | --- |
| `--out <file>` | Output path (default: `scorecards-<today>.csv`) |
| `--id <ID>` | Use a club other than the default (the full list is returned either way) |

The CSV has one row per scorecard:

| Column | Meaning |
| --- | --- |
| `date` | Date played (`YYYY-MM-DD`) |
| `club` | Club name |
| `course` | Course / layout name |
| `tee` | Tee played |
| `gender` | Tee gender |
| `holes` | Holes played (e.g. `18`, `9A`) |
| `handicap` | Handicap Index at the time |
| `playing_handicap` | Course (playing) handicap |
| `new_handicap` | Resulting handicap |
| `course_rating` | Course Rating |
| `slope_rating` | Slope Rating |
| `par` | Course par |
| `gross_total` | Total gross score |
| `note` | Free-text note |
| `scorecard_id`, `club_id` | Source identifiers |
| `hole_N_gross`, `hole_N_par`, `hole_N_si` | Per-hole gross, par and stroke index (N = 1–18) |

## Analysis

The [`analysis/`](analysis/) directory contains a repeatable Python pipeline that
reads a scorecard export and produces a Markdown report with charts: score
differential over time, handicap progression, scoring by par type, per-hole
performance, and more. See [analysis/README.md](analysis/README.md).

```bash
./pccaddie.bb export-scorecards   # refresh the data
analysis/run.sh                   # build the report in analysis/output/
```

## Notes

- The portal is a third-party service; this tool simply automates the browser
  requests a logged-in user would make. Use it with your own account.
- Exported scorecards and the generated reports contain personal data and are
  git-ignored by default.
