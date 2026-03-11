# GitHub Actions Usage

This repository supports two GitHub Actions integration modes:

- an in-repo workflow for validating this repository itself
- a reusable workflow for consumer repositories

## In-Repo Workflow

The repository contains `.github/workflows/sapcc-lint.yml`.

That workflow:

- builds the analyzer from source in this repository
- runs the scanner against the checked-out tree
- uploads HTML and CSV reports as workflow artifacts
- uploads SARIF to GitHub code scanning
- posts reviewdog pull request comments on changed lines when permissions allow

Required permissions:

- `contents: read`
- `pull-requests: write`
- `security-events: write`

Use this mode when the analyzer repository itself is under validation.

## Reusable Workflow

The repository also publishes `.github/workflows/sapcc-lint-reusable.yml`.

Consumer repositories can call it like this:

```yaml
name: sapcc-lint

on:
  pull_request:

jobs:
  analyze:
    permissions:
      contents: read
      pull-requests: write
      security-events: write
    uses: <owner>/<repo>/.github/workflows/sapcc-lint-reusable.yml@v0.1.4
    with:
      version: v0.1.4
      repo_path: .
      html_report: true
      csv_report: true
```

## Reusable Workflow Inputs

- `version`: required release tag to download, for example `v0.1.4`
- `repo_path`: path to scan inside the caller workspace, default `.`
- `config_path`: optional config file path in the caller workspace, default empty
- `upload_sarif`: upload SARIF to code scanning, default `true`
- `pull_request_changed_files_only`: on pull requests, filter findings, artifacts, and gating to changed files while keeping full-repository analysis context, default `true`
- `html_report`: generate an HTML report and upload it as an artifact, default `false`
- `csv_report`: generate a CSV report and upload it as an artifact, default `false`
- `reviewdog_comments`: post reviewdog pull request comments, default `true`
- `html_output_path`: HTML output path in the caller workspace, default `build/reports/sapcc-lint.html`
- `csv_output_path`: CSV output path in the caller workspace, default `build/reports/sapcc-lint.csv`
- `sarif_output_path`: SARIF output path in the caller workspace, default `build/reports/sapcc-lint.sarif`

Operational notes:

- `repo_path` is passed to the analyzer as `--repo`
- if `config_path` is empty, the analyzer falls back to `<repo_path>/.sapcc-lint.yml`
- when `pull_request_changed_files_only` is `true`, the workflow computes changed files from the pull request merge diff and passes them to the analyzer as `--report-paths-file`
- HTML and CSV reports are generated and uploaded only when their corresponding workflow options are enabled
- SARIF and rdjsonl are generated only when their corresponding workflow options are enabled
- if a SARIF file exists but contains more than 25000 results, the workflow skips the upload step and emits a warning because GitHub code scanning would reject that report

## What The Reusable Workflow Does

The reusable workflow:

- checks out the caller repository
- downloads the analyzer ZIP from the requested GitHub release in `commerce-cloud-integrations/sap-commerce-static-code-analysis`
- unpacks the CLI
- runs the CLI against the caller workspace
- on pull requests, keeps full-repository analysis context but filters findings, artifacts, and gating to changed files by default
- uploads HTML and CSV artifacts when enabled
- uploads SARIF when enabled and within GitHub's per-run result limits
- runs reviewdog on pull requests when enabled and permissions allow
- preserves analyzer exit codes for job pass or fail behavior

## Reviewdog Behavior

Reviewdog comments are intentionally scoped to changed pull-request lines.

That means:

- both warning and error findings can comment
- unchanged-line findings still remain visible in console output and SARIF
- analyzer exit codes still control whether the job fails

If reviewdog cannot comment, the workflow emits a warning and keeps SARIF upload plus analyzer gating intact. Reviewdog comments are labeled `sap-cc-lint`.

## Releases

Tag pushes matching `v*` trigger `.github/workflows/release.yml`.

That workflow:

- runs `./gradlew test distZip`
- creates or updates the matching GitHub release
- publishes the analyzer ZIP from `build/distributions/`

Consumer repositories should pin both:

- the reusable workflow reference, for example `@v0.1.4`
- the `version` input, for example `v0.1.4`

Those values should normally match the same release tag.
