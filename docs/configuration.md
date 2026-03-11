# Configuration Reference

The analyzer reads repository configuration from `.sapcc-lint.yml` in the scan root unless you pass `--config`.

## Configuration File Resolution

Default behavior:

- `--repo /path/to/repo` looks for `/path/to/repo/.sapcc-lint.yml`

Override behavior:

- `--config /path/to/file.yml` loads that file instead

If the configured path does not exist, the analyzer falls back to default settings.

## Supported Schema

```yaml
analysis:
  mode: auto

capabilities:
  requireFullContextFor:
    - TypeNameMustPointToExistingType

paths:
  exclude:
    - generated/**

domains:
  impex:
    enabled: true
  bean-system:
    enabled: false

rules:
  BSDomElementsInspection:
    severity: error
  ImpExMissingValueGroupInspection:
    severity: warning
  ImpExOrphanValueGroupInspection:
    enabled: false
```

## `analysis`

```yaml
analysis:
  mode: auto
```

Supported values:

- `auto`
- `local`

Guidance:

- use `auto` for the default context-aware behavior
- use `local` when you want the scan to stay intentionally local to the selected tree

## `capabilities.requireFullContextFor`

This list names rule IDs that should be skipped unless the analyzer believes repository context is complete enough.

Example:

```yaml
capabilities:
  requireFullContextFor:
    - TypeNameMustPointToExistingType
    - ImpExUnknownTypeAttributeInspection
```

Use this when:

- you often scan only a subdirectory
- some rules are too noisy without full extension and type coverage
- you want a conservative scan that prefers skipping over speculative failures

## `paths.exclude`

Exclude nested files or directories relative to the scan root.

Example:

```yaml
paths:
  exclude:
    - generated/**
    - legacy/**
    - patches/archive/**
    - "**/testsrc/**"
```

Notes:

- patterns use glob matching
- matches are evaluated relative to `--repo`
- parent-directory matches exclude all descendants
- built-in excludes still apply even if you do not configure anything

Built-in excludes:

- `.git/**`
- `.gradle/**`
- `.idea/**`
- `.kotlin/**`
- `build/**`
- `out/**`
- `node_modules/**`

## `domains`

Disable or enable whole domains.

Example:

```yaml
domains:
  impex:
    enabled: false
  cockpit-ng:
    enabled: false
```

Supported domain keys:

- `type-system`
- `project`
- `manifest`
- `impex`
- `bean-system`
- `cockpit-ng`
- `business-process`

This setting composes with CLI `--domain` flags:

- CLI `--domain` narrows the candidate domain set
- config `domains.<name>.enabled: false` can still disable a domain inside that narrowed set

## `rules`

Rules are configured by rule ID.

Supported rule settings:

- `enabled`: `true` or `false`
- `severity`: `error`, `warning`, or `off`

Example:

```yaml
rules:
  ImpExMissingValueGroupInspection:
    severity: warning
  BSDomElementsInspection:
    severity: error
  ImpExOrphanValueGroupInspection:
    enabled: false
  TSDeploymentTypeCodeReservedInspection:
    severity: off
```

Behavior:

- `enabled: false` disables the rule
- `severity: off` also disables the rule
- `severity: warning` keeps the rule visible without failing the scan
- `severity: error` makes that rule fail the scan when findings are emitted

## Practical Recipes

Only type-system and bean checks:

```yaml
domains:
  impex:
    enabled: false
  cockpit-ng:
    enabled: false
  business-process:
    enabled: false
```

Downgrade noisy ImpEx checks while keeping them visible:

```yaml
rules:
  ImpExMissingValueGroupInspection:
    severity: warning
  ImpExOrphanValueGroupInspection:
    severity: warning
  ImpExUnknownMacrosInspection:
    severity: warning
```

Turn off a single rule completely:

```yaml
rules:
  BSDomElementsInspection:
    enabled: false
```

Favor conservative subdirectory scans:

```yaml
analysis:
  mode: auto
capabilities:
  requireFullContextFor:
    - TypeNameMustPointToExistingType
    - ImpExUnknownTypeAttributeInspection
```

## What Is Not Configurable Today

The current CLI does not provide:

- a global `--only-errors` filter
- a global `--only-warnings` filter
- per-run include or exclude rule flags on the command line

Use rule overrides in `.sapcc-lint.yml` for that behavior.
