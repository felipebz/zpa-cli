package br.com.felipezorzo.zpa.cli.typereport

import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.annotation.JsonPropertyOrder
import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.felipebz.zpa.api.annotations.ZpaExperimentalApi
import com.felipebz.zpa.api.project.*
import com.felipebz.zpa.oracle.metadata.OracleMetadataResult
import java.io.File

/**
 * Describes every `%TYPE`/`%ROWTYPE` reference retained by indexed package declarations (values, subtypes, record
 * fields, collection elements, parameters and function returns) with [ProjectSnapshot.describeExternalType]. Each
 * reference is described in the compilation schema of its own package ([ProjectExternalContext.compilationSchema]);
 * inconclusive statuses are reported as such, never as absences or errors.
 */
@OptIn(ZpaExperimentalApi::class)
object TypeReport {
    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonPropertyOrder("schemaVersion", "metadata", "summary", "references")
    data class Report(
        val schemaVersion: Int,
        val metadata: Metadata,
        val summary: Map<String, Int>,
        val references: List<Reference>
    )

    @JsonInclude(JsonInclude.Include.NON_NULL)
    data class Metadata(
        val available: Boolean,
        val origin: String?,
        val defaultCompilationSchema: String?,
        val sessionUser: String?,
        val database: String?,
        val container: String?,
        val completeSchemas: List<String>?,
        val notVisible: List<String>?
    )

    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonPropertyOrder("file", "startLine", "startColumn", "declaration", "kind", "role", "element", "reference", "compilationSchema", "status")
    data class Reference(
        val file: String,
        /** One-based, as in the JSON analysis report. */
        val startLine: Int,
        /** Zero-based, as in the JSON analysis report. */
        val startColumn: Int,
        val declaration: String,
        val kind: String,
        val role: String,
        val element: String,
        val reference: String,
        val compilationSchema: String?,
        val status: String,
        val relation: String?,
        val type: Datatype?,
        val rowObjectType: Datatype?,
        val rowFields: List<Field>?,
        val sourceSteps: List<String>?,
        val note: String?
    )

    @JsonInclude(JsonInclude.Include.NON_NULL)
    data class Datatype(
        val display: String,
        val name: String,
        val owner: String?,
        val length: Int?,
        val lengthSemantics: String?,
        val precision: Int?,
        val scale: Int?,
        val category: String?
    )

    @JsonInclude(JsonInclude.Include.NON_NULL)
    data class Field(val position: Int?, val name: String, val type: Datatype?)

    private class Site(val view: ProjectDeclarationView, val owner: ProjectQualifiedName, val element: String, val reference: ProjectTypeReference)

    private val NOTES = mapOf(
        ProjectExternalTypeDescription.Status.NOT_EXTERNAL to "resolved by project sources or not a table/view reading",
        ProjectExternalTypeDescription.Status.CAPTURE_NOT_EXCLUDED to "a declaration the analysis cannot see may capture the name",
        ProjectExternalTypeDescription.Status.SOURCE_INCOMPLETE to "some project files failed to prepare",
        ProjectExternalTypeDescription.Status.UNAVAILABLE to "no database metadata was supplied",
        ProjectExternalTypeDescription.Status.SCHEMA_CONTEXT_MISSING to "the compilation schema is unknown (use --compilation-schema)",
        ProjectExternalTypeDescription.Status.INCOMPLETE to "the metadata does not cover this name",
        ProjectExternalTypeDescription.Status.RELATION_NOT_FOUND to "no table or view of that name in the read schema (Oracle may still resolve it, e.g. through a synonym)",
        ProjectExternalTypeDescription.Status.COLUMN_NOT_FOUND to "the relation has no such column in the read metadata",
        ProjectExternalTypeDescription.Status.AMBIGUOUS to "two relation readings exist",
        ProjectExternalTypeDescription.Status.CONFLICT to "a project declaration and a relation compete for the name"
    )

    fun build(snapshot: ProjectSnapshot, context: ProjectExternalContext, acquisition: OracleMetadataResult?): Report {
        val references = sites(snapshot).map { site ->
            val schema = context.compilationSchema(site.owner).orElse(null)
            val d = snapshot.describeExternalType(site.reference, site.owner, context.metadata, schema)
            val lookup = d.lookup.orElse(null)
            val relation = lookup?.relation?.orElse(null)
            val resolved = d.status == ProjectExternalTypeDescription.Status.RESOLVED
            val rowtype = d.anchor.orElse(null) == ProjectTypeReference.Anchor.ROWTYPE
            Reference(
                file = site.view.fileId,
                startLine = site.reference.sourceRange.startLine,
                startColumn = site.reference.sourceRange.startColumn,
                declaration = site.view.qualifiedName.toString(),
                kind = site.view.kind.name,
                role = site.view.role.name,
                element = site.element,
                reference = "${site.reference.name}%${site.reference.anchor.get()}",
                compilationSchema = schema?.let(::name),
                status = d.status.name,
                relation = d.relationName.orElse(null)?.let { it.segments.joinToString(".") { s -> name(s) } },
                type = if (resolved && !rowtype) lookup?.column?.orElse(null)?.datatype?.orElse(null)?.let(::datatype) else null,
                rowObjectType = if (resolved && rowtype) relation?.rowObjectType?.orElse(null)?.let(::datatype) else null,
                rowFields = if (resolved && rowtype) relation?.rowFields?.map { Field(it.position.takeIf { p -> p.isPresent }?.asInt, name(it.name), it.datatype.orElse(null)?.let(::datatype)) } else null,
                sourceSteps = d.source.steps.map { it.qualifiedName.toString() }.takeIf { it.isNotEmpty() },
                note = NOTES[d.status]
            )
        }.sortedWith(compareBy({ it.file }, { it.startLine }, { it.startColumn }, { it.declaration }, { it.element }))
        val summary = references.groupingBy { it.status }.eachCount().toSortedMap()
        return Report(
            schemaVersion = 1,
            metadata = Metadata(
                available = context.metadata.origin.isPresent,
                origin = context.metadata.origin.orElse(null),
                defaultCompilationSchema = context.defaultCompilationSchema.orElse(null)?.let(::name),
                sessionUser = acquisition?.sessionUser,
                database = acquisition?.database,
                container = acquisition?.container,
                completeSchemas = acquisition?.completeSchemas?.toList(),
                notVisible = acquisition?.notVisible?.map { it.toString() }
            ),
            summary = summary,
            references = references
        )
    }

    fun write(report: Report, file: File) {
        jacksonObjectMapper().enable(SerializationFeature.INDENT_OUTPUT).writeValue(file, report)
    }

    private fun sites(snapshot: ProjectSnapshot): List<Site> {
        val sites = ArrayList<Site>()
        fun add(view: ProjectDeclarationView, owner: ProjectQualifiedName, element: String, reference: ProjectTypeReference?) {
            if (reference != null && reference.kind == ProjectTypeReference.Kind.ANCHORED) sites += Site(view, owner, element, reference)
        }
        for (view in snapshot.declarations) {
            when (view) {
                is ProjectPackageValueView -> add(view, view.owner, "type", view.typeRef)
                is ProjectSubtypeView -> add(view, view.owner, "base type", view.baseType)
                is ProjectPackageTypeView -> {
                    add(view, view.owner, "element", view.elementType.orElse(null))
                    view.recordFields.forEach { add(view, view.owner, "field ${it.name.originalSpelling}", it.typeRef) }
                }
                is ProjectPackageSubprogramView -> {
                    view.parameters.forEach { add(view, view.packageOwner, "parameter ${it.name.originalSpelling}", it.typeRef) }
                    if (view is ProjectFunctionView) add(view, view.packageOwner, "return", view.returnType)
                }
            }
        }
        return sites
    }

    /** A dictionary-style identifier: its exact name, as Oracle stores it. */
    private fun name(identifier: ProjectIdentifier): String {
        val spelling = identifier.originalSpelling
        return if (identifier.isQuoted) spelling.removeSurrounding("\"") else spelling.uppercase()
    }

    private fun datatype(type: ProjectExternalDatatype): Datatype {
        val length = type.length.takeIf { it.isPresent }?.asInt
        val semantics = type.lengthSemantics.orElse(null)
        val precision = type.precision.takeIf { it.isPresent }?.asInt
        val scale = type.scale.takeIf { it.isPresent }?.asInt
        val owner = type.typeOwner.orElse(null)?.let(::name)
        val base = if (owner != null) "$owner.${type.name}" else type.name
        val display = when {
            length != null -> "$base($length${semantics?.let { " $it" } ?: ""})"
            precision != null && (type.name == "NUMBER" || type.name == "FLOAT") -> "$base($precision${if (scale != null && type.name == "NUMBER") ",$scale" else ""})"
            scale != null && type.name == "NUMBER" -> "$base(*,$scale)"
            else -> base
        }
        return Datatype(display, type.name, owner, length, semantics?.name, precision, scale, type.category.orElse(null)?.name)
    }
}
