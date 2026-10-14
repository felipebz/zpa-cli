package br.com.felipezorzo.zpa.cli.oracle

import java.io.PrintWriter
import java.net.URLClassLoader
import java.nio.file.Path
import java.sql.Connection
import java.sql.Driver
import java.sql.SQLException
import java.sql.SQLFeatureNotSupportedException
import java.util.*
import java.util.logging.Logger
import javax.sql.DataSource
import kotlin.io.path.isDirectory
import kotlin.io.path.listDirectoryEntries

/**
 * Locates a JDBC driver accepting a URL among the JARs of the distribution's `drivers` directory and the application
 * class path. The driver is used directly (not through `DriverManager`, which ignores drivers from other class
 * loaders), so no driver needs to be bundled with the CLI.
 *
 * A provider that cannot be loaded ([ServiceConfigurationError], e.g. a broken or incompatible JAR) is skipped and
 * counted, so it neither hides a working driver nor escapes as an unexpected failure. Other [Error]s propagate.
 */
object JdbcDrivers {
    class NoDriverException(message: String) : Exception(message)

    /**
     * @throws NoDriverException if no loadable driver accepts [url]
     * @throws SQLException if a driver fails while checking [url]; its message may contain the URL
     */
    fun find(url: String, driversDirectory: Path?): Driver {
        val jars = driversDirectory?.takeIf { it.isDirectory() }?.listDirectoryEntries("*.jar").orEmpty().sorted()
        val loader = if (jars.isEmpty()) JdbcDrivers::class.java.classLoader
        else URLClassLoader(jars.map { it.toUri().toURL() }.toTypedArray(), JdbcDrivers::class.java.classLoader)
        val providers = ServiceLoader.load(Driver::class.java, loader).iterator()
        var unloadable = 0
        while (true) {
            val driver = try {
                if (!providers.hasNext()) break
                providers.next()
            } catch (e: ServiceConfigurationError) {
                unloadable++
                continue
            }
            if (driver.acceptsURL(url)) return driver
        }
        val where = driversDirectory?.let { "the '${it.fileName}' directory of the distribution ($it)" } ?: "the class path"
        val skipped = if (unloadable == 0) "" else " $unloadable JDBC driver provider(s) could not be loaded and were skipped."
        throw NoDriverException("No JDBC driver accepts the configured --oracle-url. Copy the Oracle JDBC driver (ojdbc17.jar) to $where.$skipped")
    }
}

/** A plain, non-pooled, non-transactional DataSource: every call opens a new connection in auto-commit mode. */
class JdbcDriverDataSource(private val driver: Driver, private val url: String, private val user: String, private val password: String) : DataSource {
    override fun getConnection(): Connection = getConnection(user, password)

    override fun getConnection(username: String, password: String): Connection {
        val properties = Properties()
        properties["user"] = username
        properties["password"] = password
        return driver.connect(url, properties) ?: throw SQLException("The JDBC driver did not accept the URL")
    }

    override fun getLogWriter(): PrintWriter? = null
    override fun setLogWriter(out: PrintWriter?) = Unit
    override fun setLoginTimeout(seconds: Int) = Unit
    override fun getLoginTimeout(): Int = 0
    override fun getParentLogger(): Logger = throw SQLFeatureNotSupportedException()
    override fun <T : Any?> unwrap(iface: Class<T>): T = throw SQLException("Not a wrapper")
    override fun isWrapperFor(iface: Class<*>): Boolean = false
    override fun toString(): String = "JdbcDriverDataSource(user=$user)"
}
