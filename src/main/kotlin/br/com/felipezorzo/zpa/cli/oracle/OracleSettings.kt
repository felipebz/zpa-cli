package br.com.felipezorzo.zpa.cli.oracle

import br.com.felipezorzo.zpa.cli.Arguments
import br.com.felipezorzo.zpa.cli.CliValidationException
import com.felipebz.zpa.api.annotations.ZpaExperimentalApi
import com.felipebz.zpa.api.project.ProjectIdentifier
import com.felipebz.zpa.oracle.metadata.OracleMetadataRequest

/**
 * Validated database settings. [url] and the password are secrets-bearing values: they are never included in
 * messages or logs (see [OracleMetadataLoader]).
 */
@OptIn(ZpaExperimentalApi::class)
class OracleSettings(
    val url: String,
    val user: String,
    val password: String,
    val schemas: List<String>,
    val relations: List<Pair<String, String>>
) {
    fun request(): OracleMetadataRequest {
        val builder = OracleMetadataRequest.builder()
        schemas.forEach { builder.schema(it) }
        relations.forEach { (schema, name) -> builder.relation(schema, name) }
        return builder.build()
    }

    override fun toString(): String = "OracleSettings(user=$user, schemas=$schemas, relations=$relations)"

    companion object {
        /** `jdbc:oracle:thin:user/password@...` or a `password=` property. */
        private val CREDENTIALS_IN_URL = Regex("^jdbc:oracle:[a-z0-9]+:[^@]*/[^@]*@|password\\s*=", RegexOption.IGNORE_CASE)

        /**
         * The settings when Oracle metadata is requested (`--oracle-url`), null otherwise. Every other Oracle option
         * without `--oracle-url` is an error, and so is any Oracle option with `--syntax-only`.
         */
        fun from(args: Arguments, environment: (String) -> String?): OracleSettings? {
            val anyOption = args.oracleUrl.isNotBlank() || args.oracleUser.isNotBlank() || args.oracleSchemas.isNotBlank() ||
                args.oracleRelations.isNotBlank()
            if (args.syntaxOnly && (anyOption || args.compilationSchema.isNotBlank())) {
                throw CliValidationException("Oracle metadata options cannot be used with --syntax-only")
            }
            if (!anyOption) return null
            if (args.oracleUrl.isBlank()) {
                throw CliValidationException("--oracle-user, --oracle-schemas and --oracle-relations require --oracle-url")
            }
            if (args.oracleUser.isBlank()) throw CliValidationException("--oracle-url requires --oracle-user")
            if (CREDENTIALS_IN_URL.containsMatchIn(args.oracleUrl)) {
                // The URL is deliberately not echoed: it carries a credential.
                throw CliValidationException("--oracle-url must not contain credentials; use --oracle-user and the password environment variable")
            }
            val schemas = names(args.oracleSchemas, "--oracle-schemas")
            val relations = names(args.oracleRelations, "--oracle-relations").map { relation ->
                val parts = relation.split('.')
                if (parts.size != 2 || parts.any { it.isEmpty() }) {
                    throw CliValidationException("Invalid --oracle-relations entry '$relation': expected SCHEMA.RELATION")
                }
                parts[0] to parts[1]
            }
            if (schemas.isEmpty() && relations.isEmpty()) {
                throw CliValidationException("--oracle-url requires --oracle-schemas or --oracle-relations")
            }
            val variable = args.oraclePasswordEnv
            if (variable.isBlank()) throw CliValidationException("--oracle-password-env must name an environment variable")
            val password = environment(variable)
            if (password.isNullOrEmpty()) {
                throw CliValidationException("The database password environment variable '$variable' is not set")
            }
            return OracleSettings(args.oracleUrl, args.oracleUser, password, schemas, relations)
        }

        /** The `--compilation-schema` value as an exact identifier, or null. */
        fun compilationSchema(args: Arguments): ProjectIdentifier? {
            val name = args.compilationSchema.trim()
            if (name.isEmpty()) return null
            if (name.contains('"')) throw CliValidationException("--compilation-schema must be an exact dictionary name, without quotes")
            return ProjectIdentifier.fromSource("\"$name\"")
        }

        private fun names(value: String, option: String): List<String> {
            if (value.isBlank()) return emptyList()
            return value.split(',').map { it.trim() }.onEach {
                if (it.isEmpty() || it.contains('"')) throw CliValidationException("Invalid $option entry '$it': use exact dictionary names, comma-separated")
            }.distinct()
        }
    }
}
