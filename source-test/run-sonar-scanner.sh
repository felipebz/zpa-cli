#!/bin/bash

set -euo pipefail

docker run --rm \
    -v $PWD/source-test/utPLSQL:/src \
    felipebz/zpa-cli:nightly \
    --sources . \
    --output-file zpa-issues.json \
    --output-format sq-generic-issue-import

docker run --rm \
    -e SONAR_HOST_URL="https://sonarcloud.io" \
    -e SONAR_LOGIN=$SONARCLOUD_TOKEN \
    -v $PWD/source-test/utPLSQL:/usr/src \
    sonarsource/sonar-scanner-cli \
    -Dsonar.projectKey=utPLSQL-zpa-demo \
    -Dsonar.organization=$SONARCLOUD_ORGANIZATION \
    -Dsonar.externalIssuesReportPaths=zpa-issues.json \
    -Dsonar.pullrequest.provider= \
    -Dsonar.coverageReportPaths= \
    -Dsonar.testExecutionReportPaths= \
    -Dsonar.plsql.file.suffixes=sql,pkg,pks,pkb,fun,pcd,tgg,prc,tpb,trg,typ,tab,tps \
    -Dsonar.projectName="utPLSQL analyzed by ZPA" \
    -Dsonar.scm.disabled=true \
    -Dsonar.tests=
