# Scanning Repositories And Subdirectories

This document explains how the analyzer interprets `--repo`, how recursive discovery works, and how to exclude nested directories or files from a scan.

## What `--repo` Means

The path passed to `--repo` is the scan root.

- The analyzer walks that directory recursively.
- File discovery happens under that root only.
- Relative exclude patterns are evaluated against that root.
- `.sapcc-lint.yml` is loaded from that root by default.

Examples:

Scan an entire SAP Commerce checkout:

```bash
sap-cc-static-code-analysis scan --repo /workspace/commerce
```

Scan only a custom extension tree:

```bash
sap-cc-static-code-analysis scan --repo /workspace/commerce/core-customize/eurofred
```

Scan a subdirectory but keep using a config file from the repository root:

```bash
sap-cc-static-code-analysis scan \
  --repo /workspace/commerce/core-customize/eurofred \
  --config /workspace/commerce/.sapcc-lint.yml
```

## Recursive File Discovery

Discovery is recursive from the scan root.

The scanner currently recognizes these domain entrypoints:

- `type-system`: files ending in `-items.xml`
- `project`: `extensioninfo.xml` and `localextensions.xml`
- `manifest`: `manifest.json` only when it is directly inside a `core-customize` directory
- `impex`: files ending in `.impex`
- `bean-system`: files ending in `-beans.xml`, plus XML files with a `beans` root tag
- `cockpit-ng`: XML files with `config`, `widgets`, or `action-definition` root tags
- `business-process`: XML files with a `process` root tag

Because discovery is recursive, passing a parent directory means all matching files in nested directories are eligible unless excluded.

## Default Ignored Directories

The scanner always excludes these paths relative to the scan root:

- `.git/**`
- `.gradle/**`
- `.idea/**`
- `.kotlin/**`
- `build/**`
- `out/**`
- `node_modules/**`

These defaults are built in so local tool output and dependency folders do not pollute the scan.

## Excluding Nested Directories Or Files

Use `.sapcc-lint.yml` to exclude paths with glob patterns:

```yaml
paths:
  exclude:
    - generated/**
    - legacy/**
    - patches/archive/**
    - "**/testsrc/**"
```

Important details:

- patterns are matched relative to the `--repo` root
- glob syntax uses the platform path matcher
- both files and parent directories are checked against the exclude rules
- excluding a parent directory excludes everything below it

If you scan `/workspace/commerce/core-customize/eurofred`, then this:

```yaml
paths:
  exclude:
    - eurofredpatches/releases/**
```

means:

- `/workspace/commerce/core-customize/eurofred/eurofredpatches/releases/**` is skipped
- sibling paths under the same scan root are still scanned

## Domain Targeting

Use repeated `--domain` flags to reduce the scan surface:

```bash
sap-cc-static-code-analysis scan \
  --repo /workspace/commerce \
  --domain type-system \
  --domain bean-system
```

This is useful when:

- you want fast focused feedback
- you are validating only one rule family
- one domain is currently too noisy for the repository state

If no `--domain` flags are provided, all supported domains are candidates unless disabled in config.

## Partial-Repository Behavior

Scanning a subdirectory is supported, but repository context may be incomplete.

That matters most for rules that depend on:

- extension registry completeness
- local type-system completeness
- external SAP Commerce platform types
- cross-file or cross-extension references

The analyzer tries to stay partial-repo-safe where practical, but a narrower scan root can still change which findings are emitted.

Useful tools for managing that:

- `analysis.mode: auto` to keep the default context-sensitive behavior
- `analysis.mode: local` when you intentionally want only local context
- `capabilities.requireFullContextFor` to skip selected rules unless the repository context is complete enough

Example:

```yaml
analysis:
  mode: auto
capabilities:
  requireFullContextFor:
    - TypeNameMustPointToExistingType
    - ImpExUnknownTypeAttributeInspection
```

## Practical Patterns

Full repository validation:

```bash
sap-cc-static-code-analysis scan --repo /workspace/commerce
```

Custom extensions only:

```bash
sap-cc-static-code-analysis scan \
  --repo /workspace/commerce/core-customize/eurofred \
  --config /workspace/commerce/.sapcc-lint.yml
```

Backoffice-only targeted validation:

```bash
sap-cc-static-code-analysis scan \
  --repo /workspace/commerce/core-customize/eurofred \
  --domain bean-system \
  --domain cockpit-ng
```

Generated or archived content excluded:

```yaml
paths:
  exclude:
    - generated/**
    - archive/**
    - vendor/**
```
