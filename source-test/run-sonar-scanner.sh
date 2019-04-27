#!/bin/bash

set -euo pipefail

SONAR_SCANNER_VERSION=3.3.0.1492
TOOLS_PATH=`pwd`/source-test/tools

export LANG=en_US.UTF-8
export PATH=$PATH:$TOOLS_PATH/sonar-scanner-$SONAR_SCANNER_VERSION/bin:$TOOLS_PATH/zpa-cli/bin

mkdir -p $TOOLS_PATH
cd $TOOLS_PATH

# Extract sonar-scanner

if [ ! -x "./sonar-scanner-$SONAR_SCANNER_VERSION/bin/sonar-scanner" ]; then
  wget https://binaries.sonarsource.com/Distribution/sonar-scanner-cli/sonar-scanner-cli-$SONAR_SCANNER_VERSION.zip
  unzip sonar-scanner-cli-$SONAR_SCANNER_VERSION.zip
fi

# Extract zpa-cli

rm -rf zpa-cli/
tar xvf ../../build/distributions/zpa-cli.tar

# Execute an analysis

cd ../utPLSQL/

$TOOLS_PATH/zpa-cli/bin/zpa-cli --sources . --output zpa-issues.json
sonar-scanner -Dsonar.projectKey=utPLSQL-zpa-demo \
    -Dsonar.organization=$SONARCLOUD_ORGANIZATION \
    -Dsonar.host.url=https://sonarcloud.io \
    -Dsonar.login=$SONARCLOUD_TOKEN \
    -Dsonar.externalIssuesReportPaths=zpa-issues.json \
    -Dsonar.pullrequest.provider= \
    -Dsonar.coverageReportPaths= \
    -Dsonar.testExecutionReportPaths= \
    -Dsonar.plsql.file.suffixes=sql,pkg,pks,pkb,fun,pcd,tgg,prc,tpb,trg

rm -rf zpa-issues.json .scannerwork/