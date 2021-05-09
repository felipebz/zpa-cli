# zpa-cli (Z PL/SQL Analyzer - Command-line Interface)

[![Build Status](https://dev.azure.com/felipebz/z-plsql-analyzer/_apis/build/status/zpa-cli?branchName=main)](https://dev.azure.com/felipebz/z-plsql-analyzer/_build/latest?definitionId=9&branchName=main)

This is a command-line interface to the [Z PL/SQL Analyzer](https://github.com/felipebz/zpa). It is a code analyzer for Oracle PL/SQL and Oracle Forms projects.

## Downloading

Official releases are available for download on the ["Releases" page](https://github.com/felipebz/zpa-cli/releases).

## Requirements

* Java 11 or newer

## Usage

Currently, the zpa-cli supports these options:

* `--sources`: **[required]** Path to the folder containing the files to be analyzed.
* `--forms-metadata`: Path to the Oracle Forms [metadata file](https://github.com/felipebz/zpa/wiki/Oracle-Forms-support).
* `--extensions`: File extensions to analyze separated by comma. The default value is `sql,pkg,pks,pkb`.
* `--output-file`: Path to the output file. The default value is `zpa-issues.json`.

The output file follows the ["Generic Issue Data" format](https://docs.sonarqube.org/latest/analysis/generic-issue/) and it can be used in SonarCloud or in a SonarQube server (as an alternative to the dedicated [Z PL/SQL Analyzer Plugin](https://github.com/felipebz/zpa)).

### Example

Running an analysis:

`./zpa-cli/bin/zpa-cli --sources .`

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
