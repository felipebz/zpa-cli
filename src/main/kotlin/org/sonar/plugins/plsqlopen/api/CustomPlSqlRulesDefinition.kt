package org.sonar.plugins.plsqlopen.api

// The custom plugins currently extend CustomPlSqlRulesDefinition from sonar-zpa-plugin and we don't
// want to include the whole sonar-zpa-plugin as a dependency
abstract class CustomPlSqlRulesDefinition : ZpaRulesDefinition
