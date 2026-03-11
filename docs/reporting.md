# Reporting Formats

The analyzer supports multiple output formats from a single scan. You can request one format, several formats, or keep the default console-only output.

## Available Formats

- `console`: human-readable terminal output grouped by file
- `html`: self-contained readable report with summary sections and a findings table
- `csv`: one row per finding for spreadsheet or downstream processing
- `sarif`: machine-readable JSON for GitHub code scanning
- `rdjsonl`: machine-readable JSON Lines for reviewdog PR comments

SARIF and rdjsonl serve different workflow integrations:

- `sarif` is for GitHub Code Scanning
- `rdjsonl` is for reviewdog pull-request comments

If a workflow enables both, the same finding can surface in both channels.

## CLI Usage

Console is the default when no explicit `--format` values are provided:

```bash
sap-cc-static-code-analysis scan --repo /workspace/commerce
```

Generate HTML:

```bash
sap-cc-static-code-analysis scan \
  --repo /workspace/commerce \
  --format console \
  --format html \
  --html-out build/reports/sapcc-lint.html
```

Generate CSV:

```bash
sap-cc-static-code-analysis scan \
  --repo /workspace/commerce \
  --format console \
  --format csv \
  --csv-out build/reports/sapcc-lint.csv
```

Generate both HTML and CSV in one run:

```bash
sap-cc-static-code-analysis scan \
  --repo /workspace/commerce \
  --format console \
  --format html \
  --html-out build/reports/sapcc-lint.html \
  --format csv \
  --csv-out build/reports/sapcc-lint.csv
```

Generate everything:

```bash
sap-cc-static-code-analysis scan \
  --repo /workspace/commerce \
  --format console \
  --format html \
  --html-out build/reports/sapcc-lint.html \
  --format csv \
  --csv-out build/reports/sapcc-lint.csv \
  --format sarif \
  --sarif-out build/reports/sapcc-lint.sarif \
  --format rdjsonl \
  --rdjsonl-out build/reports/sapcc-lint.rdjsonl
```

## Required Output Flags

These formats require an output path:

- `html` requires `--html-out`
- `csv` requires `--csv-out`
- `sarif` requires `--sarif-out`
- `rdjsonl` requires `--rdjsonl-out`

If a required output path is missing, the CLI returns an argument error.

## HTML Report

The HTML report is intended for direct human review.

It includes:

- scan summary
- counts by severity
- counts by domain
- counts by rule
- scanned-domain inventory
- full findings table

The page is self-contained and does not depend on external CSS, JavaScript, or network access. It includes lightweight client-side filtering and column sorting for the findings table.

Use HTML when:

- you want a readable downloadable artifact from CI
- you want to share a report with someone outside the terminal
- you want a compact summary plus a full findings table in one file

## CSV Report

The CSV report is intended for downstream processing and spreadsheet use.

It emits one row per finding with these columns:

- `severity`
- `domain`
- `rule_id`
- `file`
- `line`
- `column`
- `message`
- `entity_key`

Use CSV when:

- you want to sort or filter findings in a spreadsheet
- you want to import findings into another system
- you want a flat export for scripting

## Workflow Usage

The reusable workflow can generate HTML and CSV reports independently.

Examples:

HTML only:

```yaml
with:
  version: v0.1.4
  html_report: true
```

CSV only:

```yaml
with:
  version: v0.1.4
  csv_report: true
```

Both:

```yaml
with:
  version: v0.1.4
  html_report: true
  csv_report: true
```

When enabled through the reusable workflow:

- HTML is uploaded as artifact `sapcc-lint-html-report`
- CSV is uploaded as artifact `sapcc-lint-csv-report`

If the reusable workflow also keeps its default `upload_sarif: true` and `reviewdog_comments: true` settings, the pull request can receive both Code Scanning alerts and reviewdog comments alongside those artifacts.

See [github-actions.md](github-actions.md) for the full reusable workflow interface.
