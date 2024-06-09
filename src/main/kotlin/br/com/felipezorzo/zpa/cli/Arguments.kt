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
}
