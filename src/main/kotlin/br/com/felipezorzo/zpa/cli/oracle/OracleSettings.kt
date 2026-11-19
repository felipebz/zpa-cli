package br.com.felipezorzo.zpa.cli.oracle

import br.com.felipezorzo.zpa.cli.Arguments
import br.com.felipezorzo.zpa.cli.CliValidationException
import br.com.felipezorzo.zpa.cli.oracle.OracleSettings.Companion.dictionaryName
import com.felipebz.zpa.api.annotations.ZpaExperimentalApi
import com.felipebz.zpa.api.project.ProjectIdentifier
import com.felipebz.zpa.api.project.ProjectSchemaPaths
import com.felipebz.zpa.oracle.metadata.OracleMetadataRequest
import com.felipebz.zpa.oracle.metadata.OracleRelationName
import java.nio.file.Path

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
    val relations: List<Pair<String, String>>,
    val publicSynonyms: Boolean = true
) {
    fun request(): OracleMetadataRequest {
        val builder = OracleMetadataRequest.builder()
        schemas.forEach { builder.schema(it) }
        relations.forEach { (schema, name) -> builder.relation(schema, name) }
        if (publicSynonyms) builder.publicSynonyms()
        return builder.build()
    }

    override fun toString(): String =
        "OracleSettings(user=$user, schemas=$schemas, relations=$relations, publicSynonyms=$publicSynonyms)"

    companion object {
        /** `jdbc:oracle:thin:user/password@...` or a `password=` property. */
        private val CREDENTIALS_IN_URL = Regex("^jdbc:oracle:[a-z0-9]+:[^@]*/[^@]*@|password\\s*=", RegexOption.IGNORE_CASE)

        /**
         * The settings when Oracle metadata is requested (`--oracle-url`), null otherwise. Every other Oracle option
         * without `--oracle-url` is an error, and so is any Oracle option with `--syntax-only`.
         */
        fun from(args: Arguments, environment: (String) -> String?): OracleSettings? {
            val anyOption = args.oracleUrl.isNotBlank() || args.oracleUser.isNotBlank() || args.oracleSchemas.isNotBlank() ||
                args.oracleRelations.isNotBlank() || args.oraclePublicSynonyms != null
            if (args.syntaxOnly && (anyOption || args.compilationSchema.isNotBlank())) {
                throw CliValidationException("Oracle metadata options cannot be used with --syntax-only")
            }
            // Schemas apply to project lookups, which --syntax-only does not make.
            if (args.syntaxOnly && args.schemaPaths.isNotBlank()) {
                throw CliValidationException("--schema-paths cannot be used with --syntax-only")
            }
            if (!anyOption) return null
            if (args.oracleUrl.isBlank()) {
                throw CliValidationException(
                    "--oracle-user, --oracle-schemas, --oracle-relations and --oracle-public-synonyms require --oracle-url"
                )
            }
            if (args.oracleUser.isBlank()) throw CliValidationException("--oracle-url requires --oracle-user")
            if (CREDENTIALS_IN_URL.containsMatchIn(args.oracleUrl)) {
                // The URL is deliberately not echoed: it carries a credential.
                throw CliValidationException("--oracle-url must not contain credentials; use --oracle-user and the password environment variable")
            }
            val publicSynonyms = publicSynonyms(args.oraclePublicSynonyms)
            val schemas = names(args.oracleSchemas, "--oracle-schemas").map { dictionaryName(it, "--oracle-schemas") }.distinct()
            val relations = names(args.oracleRelations, "--oracle-relations").map(::relation).distinct()
            if (schemas.isEmpty() && relations.isEmpty()) {
                throw CliValidationException("--oracle-url requires --oracle-schemas or --oracle-relations")
            }
            val variable = args.oraclePasswordEnv
            if (variable.isBlank()) throw CliValidationException("--oracle-password-env must name an environment variable")
            val password = environment(variable)
            if (password.isNullOrEmpty()) {
                throw CliValidationException("The database password environment variable '$variable' is not set")
            }
            return OracleSettings(args.oracleUrl, args.oracleUser, password, schemas, relations, publicSynonyms)
        }

        /** The `--compilation-schema` value as an identifier (see [dictionaryName]), or null. */
        fun compilationSchema(args: Arguments): ProjectIdentifier? {
            val name = args.compilationSchema.trim()
            if (name.isEmpty()) return null
            return ProjectIdentifier.fromSource("\"${dictionaryName(name, "--compilation-schema")}\"")
        }

        /** The `--schema-paths` mapping, relative to [baseDirectory]; empty when not given. */
        fun schemaPaths(args: Arguments, baseDirectory: Path): ProjectSchemaPaths {
            if (args.schemaPaths.isBlank()) return ProjectSchemaPaths.none()
            return try {
                ProjectSchemaPaths.parse(baseDirectory, args.schemaPaths)
            } catch (e: IllegalArgumentException) {
                throw CliValidationException("Invalid --schema-paths value: ${e.message}")
            }
        }

        private fun relation(entry: String): Pair<String, String> {
            val name = try {
                OracleRelationName.parse(entry)
            } catch (_: IllegalArgumentException) {
                throw CliValidationException("Invalid --oracle-relations entry '$entry': expected SCHEMA.RELATION")
            }
            return name.schema to name.name
        }

        /** [name] as Oracle reads an identifier: unquoted, upper-cased; in double quotes, exactly as written. */
        private fun dictionaryName(name: String, option: String): String = try {
            OracleRelationName.dictionaryNameOf(name)
        } catch (_: IllegalArgumentException) {
            throw CliValidationException("Invalid $option entry '$name'")
        }

        /** `--oracle-public-synonyms`: on when not given. */
        private fun publicSynonyms(value: String?): Boolean = when (value?.trim()?.lowercase()) {
            null, "true" -> true
            "false" -> false
            else -> throw CliValidationException("Invalid --oracle-public-synonyms value '$value': expected true or false")
        }

        private fun names(value: String, option: String): List<String> {
            if (value.isBlank()) return emptyList()
            return value.split(',').map { it.trim() }.onEach {
                if (it.isEmpty()) throw CliValidationException("Invalid $option entry '$it': names are comma-separated")
            }
        }
    }
}
