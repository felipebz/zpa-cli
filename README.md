# ZPA CLI

[![Build](https://github.com/felipebz/zpa-cli/actions/workflows/build.yml/badge.svg?branch=main)](https://github.com/felipebz/zpa-cli/actions/workflows/build.yml)

This is a command-line interface to the [Z PL/SQL Analyzer](https://github.com/felipebz/zpa). It is a code analyzer for Oracle PL/SQL and Oracle Forms projects.

## Downloading

Official releases are available for download on the ["Releases" page](https://github.com/felipebz/zpa-cli/releases).

## Requirements

* Java 21 or newer

## Usage

Currently, the zpa-cli supports these options:

* `--sources`: **[required]** Path to the folder containing project files. Defines the complete project source/context set.
* `--files`: One or more files to analyze, separated by space (e.g. `--files a.pks b.pkb` or repeated `--files a.pks --files b.pkb`), or `-` to read from standard input. Relative paths are resolved relative to `--sources`. Absolute paths are accepted only when they resolve inside `--sources`. For normal analysis, every target must be a member of the discovered sources under `--sources`. When omitted, all supported files discovered under `--sources` are analyzed.
* `--syntax-only`: Validates PL/SQL syntax only. Bypasses normal coding rules, custom plugin loading, Forms metadata, and project semantic preparation. When used with explicit `--files`, only the specified targets are resolved and parsed without recursively discovering the rest of the project.
* `--stdin-filename`: Virtual filename for source identity when reading from stdin (`--files -`). Must reside inside `--sources` and have a supported extension. Defaults to `stdin.sql`.
* `--fail-on`: Failure threshold for the exit code (`none`, `any`, `syntax`, `blocker`, `critical`, `major`, `minor`, `info`). In normal analysis mode, the default is `none`. When `--syntax-only` is requested without `--fail-on`, the default threshold is `syntax`. An explicit `--fail-on none` overrides this default in syntax-only mode.
* `--forms-metadata`: Path to the Oracle Forms [metadata file](https://github.com/felipebz/zpa/wiki/Oracle-Forms-support).
* `--extensions`: File extensions to analyze, separated by comma. The default value is `sql,pkg,pks,pkb,fun,pcd,tgg,prc,tpb,trg,typ,tab,tps`.
* `--output-format`: Format of the output. Supported formats: `console`, `json`, `sq-generic-issue-import`. The default value is `console`.
* `--output-file`: Path to the output file. When specified with `json`, writes the report to the file without writing to stdout.
* `--config`: Path to the configuration file. The file format must comply with the [provided JSON schema](schema.json).
  You can refer to the example [zpa-config-example.json](zpa-config-example.json) for guidance. If the configuration
  file is not provided, only the rules marked as "activated by default" will be executed.

### Project context vs analysis targets

* `--sources` defines the complete project context. All discovered project files are used for project declaration index preparation and cross-file semantic resolution.
* `--files` optionally restricts the files that are actually scanned for diagnostics and reported. Filesystem targets must be a subset of discovered project sources (`filesystemTargets ⊆ discoveredProjectSources`).
* **stdin limitation**: Standard input (`--files -`) is currently supported only with `--syntax-only`. Project-aware semantic analysis with stdin requires project-overlay support, which is deferred to a future milestone.

### Output formats:
* `console`: writes human-readable analysis results to standard output, grouped by file and sorted deterministically, including position, severity, rule key, and message.
* `json`: outputs a deterministic, schema-versioned machine-readable JSON document (`schemaVersion: 1`). Safe to pipe: stdout contains only JSON (when `--output-file` is not used), while logs and progress messages go to stderr.
  - `validation`: `{ "passed": boolean, "threshold": string }`. Authoritative validation outcome matching exit code 0 (`passed: true`) vs 1 (`passed: false`) against the configured `--fail-on` threshold. Diagnostics may exist when `validation.passed` is true (e.g. with `--fail-on none`).
  - `diagnostics`: list of findings sorted stably by normalized file, range, rule, and message:
    - `kind`: `"SYNTAX"` (parse/syntax diagnostics from `ParsingErrorCheck`) or `"RULE"` (standard coding rules).
    - `file`: normalized file path relative to `--sources`.
    - `range`: `{ "startLine": int?, "startColumn": int?, "endLine": int?, "endColumn": int? }` or `null` for file-level issues. Coordinates: `startLine` is 1-based, `startColumn` is 0-based, `endLine` is 1-based, `endColumn` is 0-based exclusive. Unavailable coordinates are represented as `null`.
    - `rule`: rule identifier (e.g. `zpa:ParsingError`, `zpa:PackageBodyParameterNocopy`).
    - `severity`: `"BLOCKER"`, `"CRITICAL"`, `"MAJOR"`, `"MINOR"`, `"INFO"`.
    - `message`: localized diagnostic message.
* `sq-generic-issue-import`: generates a JSON file using SonarQube's ["Generic Issue Data" format](https://docs.sonarqube.org/latest/analysis/generic-issue/) that can be used in SonarCloud or in a SonarQube server.

### Exit codes:
* `0`: analysis completed without an enabled validation failure (threshold not exceeded)
* `1`: requested validation condition failed (findings met or exceeded the `--fail-on` threshold)
* `2`: invalid invocation, command-line arguments, or target file error (e.g. nonexistent target file, target outside `--sources`, stdin used without `--syntax-only`)
* `3`: internal execution failure
### Examples

Full project analysis:
```sh
zpa-cli --sources .
```

Focused human validation:
```sh
zpa-cli --sources . --files src/packages/customer.pkb
```

Focused machine validation:
```sh
zpa-cli --sources . --files src/packages/customer.pkb --output-format json
```

Syntax validation:
```sh
zpa-cli --sources . --files src/packages/customer.pkb --syntax-only
```

Stdin validation:
```sh
cat generated.sql | zpa-cli --sources . --files - --stdin-filename src/packages/customer.pkb --syntax-only
```

Running an analysis:

`./zpa-cli/bin/zpa-cli --sources . --output-file zpa-issues.json --output-format sq-generic-issue-import`

Then you can send the results to a SonarCloud or SonarQube server setting the `sonar.externalIssuesReportPaths` property:

```
sonar-scanner 
  -Dsonar.organization=$SONARCLOUD_ORGANIZATION \
  -Dsonar.projectKey=myproject \
  -Dsonar.sources=. \
  -Dsonar.host.url=https://sonarcloud.io \
  -Dsonar.externalIssuesReportPaths=zpa-issues.json
```

Check the [demo project on SonarCloud](https://sonarcloud.io/project/issues?id=utPLSQL-zpa-demo&resolved=false)!

## Contributing

Please read our [contributing guidelines](CONTRIBUTING.md) to see how you can contribute to this project.
