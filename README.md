# ZPA CLI

[![Build](https://github.com/felipebz/zpa-cli/actions/workflows/build.yml/badge.svg?branch=main)](https://github.com/felipebz/zpa-cli/actions/workflows/build.yml)

This is a command-line interface to the [Z PL/SQL Analyzer](https://github.com/felipebz/zpa). It is a code analyzer for Oracle PL/SQL and Oracle Forms projects.

## Downloading

Official releases are available for download on the ["Releases" page](https://github.com/felipebz/zpa-cli/releases).

## Requirements

* Java 11 or newer

## Usage

Currently, the zpa-cli supports these options:

* `--sources`: **[required]** Path to the folder containing the files to be analyzed.
* `--forms-metadata`: Path to the Oracle Forms [metadata file](https://github.com/felipebz/zpa/wiki/Oracle-Forms-support).
* `--extensions`: File extensions to analyze, separated by comma. The default value is `sql,pkg,pks,pkb,fun,pcd,tgg,prc,tpb,trg,typ,tab,tps`.
* `--output-format`: Format of the output. The default value is `console`.  
* `--output-file`: Path to the output file.

Output formats:
* `console`: writes the analysis result on the standard output
* `sq-generic-issue-import`: generates a XML file using the ["Generic Issue Data" format](https://docs.sonarqube.org/latest/analysis/generic-issue/) that can be used in SonarCloud or in a SonarQube server (as an alternative to the dedicated [Z PL/SQL Analyzer Plugin](https://github.com/felipebz/zpa)).

### Example

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
