package br.com.felipezorzo.zpa.cli.oracle

import br.com.felipezorzo.zpa.cli.CliExecutionException
import com.felipebz.zpa.api.annotations.ZpaExperimentalApi
import com.felipebz.zpa.oracle.metadata.OracleMetadataAcquisition
import com.felipebz.zpa.oracle.metadata.OracleMetadataException
import com.felipebz.zpa.oracle.metadata.OracleMetadataResult
import com.felipebz.zpa.utils.log.Loggers
import java.nio.file.Path
import java.sql.SQLException

/**
 * Acquires Oracle metadata once per CLI run, before scanning. The connection is opened, used in one read-only
 * transaction and closed by [OracleMetadataAcquisition]; only the detached, immutable result is kept. Failures abort
 * the run with a message from which the URL and the password are removed: no partial metadata is ever used.
 */
@OptIn(ZpaExperimentalApi::class)
object OracleMetadataLoader {
    private val LOG = Loggers.getLogger(OracleMetadataLoader::class.java)

    fun load(settings: OracleSettings, driversDirectory: Path?): OracleMetadataResult {
        val driver = try {
            JdbcDrivers.find(settings.url, driversDirectory)
        } catch (e: JdbcDrivers.NoDriverException) {
            throw CliExecutionException(e.message!!)
        } catch (e: SQLException) {
            throw CliExecutionException("JDBC driver lookup failed${errorCode(e)}: ${redact(e.message, settings)}")
        } catch (e: RuntimeException) {
            throw CliExecutionException("JDBC driver lookup failed: ${redact(e.message, settings)}")
        }
        val dataSource = JdbcDriverDataSource(driver, settings.url, settings.user, settings.password)
        LOG.info("Reading Oracle metadata as ${settings.user} (schemas ${settings.schemas}, relations ${settings.relations.map { "${it.first}.${it.second}" }})...")
        val result = try {
            OracleMetadataAcquisition.acquire(dataSource, settings.request())
        } catch (e: OracleMetadataException) {
            throw CliExecutionException("Oracle metadata acquisition failed: ${redact(e.message, settings)}")
        } catch (e: SQLException) {
            throw CliExecutionException("Oracle metadata acquisition failed${errorCode(e)}: ${redact(e.message, settings)}")
        } catch (e: RuntimeException) {
            // Driver or contract failures (e.g. a connection not in auto-commit): message only, never a stack trace.
            throw CliExecutionException("Oracle metadata acquisition failed: ${redact(e.message, settings)}")
        }
        LOG.info(
            "Oracle metadata: ${result.relationCount} relations, ${result.columnCount} columns from ${result.database}/${result.container} " +
                "as ${result.sessionUser}; complete schemas ${result.completeSchemas}" +
                (if (result.notVisible.isEmpty()) "" else "; not visible ${result.notVisible}")
        )
        return result
    }

    private fun errorCode(e: SQLException) = if (e.errorCode != 0) " (error ${e.errorCode})" else ""

    /** Removes the URL, the password and any `user/password@` fragment from a driver message. */
    fun redact(message: String?, settings: OracleSettings): String {
        var text = message ?: "unknown error"
        text = text.replace(settings.url, "<oracle-url>")
        if (settings.password.isNotEmpty()) text = text.replace(settings.password, "***")
        return text.replace(Regex("[^\\s/@:]+/[^\\s@]+@"), "***@")
    }
}
