package br.com.felipezorzo.zpa.cli

import com.beust.jcommander.Parameter

class Arguments {
    @Parameter(names = ["--sources"], description = "Folder with files", required = true)
    var sources: String = ""

    @Parameter(names = ["--forms-metadata"], description = "Oracle Forms metadata file")
    var formsMetadata: String = ""

    @Parameter(names = ["--extensions"], description = "Extensions to analyze")
    var extensions: String = "sql,pkg,pks,pkb,fun,pcd,tgg,prc,tpb,trg,typ,tab,tps"

    @Parameter(names = ["--output-format"], description = "Format of the output file")
    var outputFormat: String = "console"

    @Parameter(names = ["--output-file"], description = "Output filename")
    var outputFile: String = ""

    @Parameter(names = ["--config"], description = "Config file")
    var configFile: String = ""

    @Parameter(names = ["--files"], description = "Files to analyze", variableArity = true)
    var files: List<String> = ArrayList()

    @Parameter(names = ["--syntax-only"], description = "Perform syntax validation only")
    var syntaxOnly: Boolean = false

    @Parameter(names = ["--stdin-filename"], description = "Virtual filename when reading from stdin")
    var stdinFilename: String = ""

    @Parameter(names = ["--fail-on"], description = "Failure threshold for validation exit code (none, any, syntax, blocker, critical, major, minor, info)")
    var failOn: String? = null

    @Parameter(names = ["--oracle-url"], description = "JDBC URL of an Oracle database to read schema metadata from (enables Oracle metadata acquisition)")
    var oracleUrl: String = ""

    @Parameter(names = ["--oracle-user"], description = "Database user for --oracle-url")
    var oracleUser: String = ""

    @Parameter(names = ["--oracle-password-env"], description = "Name of the environment variable holding the database password")
    var oraclePasswordEnv: String = "ZPA_ORACLE_PASSWORD"

    @Parameter(names = ["--oracle-schemas"], description = "Comma-separated schemas to read completely (exact dictionary names, e.g. HR)")
    var oracleSchemas: String = ""

    @Parameter(names = ["--oracle-relations"], description = "Comma-separated SCHEMA.RELATION names to read individually (exact dictionary names)")
    var oracleRelations: String = ""

    @Parameter(names = ["--compilation-schema"], description = "Schema in which analyzed units without an explicit schema compile (exact dictionary name)")
    var compilationSchema: String = ""

    @Parameter(names = ["--type-report"], description = "Write a JSON report of %TYPE/%ROWTYPE references in package declarations to this file")
    var typeReport: String = ""

    @Parameter(names = ["--help", "-h"], help = true, description = "Display help information")
    var help: Boolean = false
}
