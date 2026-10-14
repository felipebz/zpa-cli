package br.com.felipezorzo.zpa.cli.oracle

import oracle.jdbc.pool.OracleDataSource
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.security.SecureRandom
import java.sql.SQLException
import javax.sql.DataSource

/**
 * Disposable users in an Oracle database reached through environment variables, never through literals:
 * `ZPA_ORACLE_JDBC_URL`, `ZPA_ORACLE_ADMIN_USER`, `ZPA_ORACLE_ADMIN_PASSWORD`. Tests are skipped when they are unset.
 */
class OracleDatabase private constructor(val jdbcUrl: String, private val admin: DataSource) : AutoCloseable {
    private val users = ArrayList<String>()
    private val random = SecureRandom()

    /** A new user with the given system privileges; its password is random and never printed. */
    fun createUser(prefix: String, vararg privileges: String): User {
        val name = prefix + "_" + (1..6).map { ALPHABET[random.nextInt(ALPHABET.length)] }.joinToString("")
        val password = "P" + (1..24).map { ALPHABET[random.nextInt(ALPHABET.length)] }.joinToString("")
        admin.connection.use { c ->
            c.createStatement().use { s ->
                s.execute("CREATE USER $name IDENTIFIED BY \"$password\" QUOTA UNLIMITED ON users")
                users += name
                s.execute("GRANT CREATE SESSION" + privileges.joinToString("") { ", $it" } + " TO $name")
            }
        }
        return User(name, password, OracleDataSource().apply {
            setURL(jdbcUrl)
            user = name
            setPassword(password)
        })
    }

    override fun close() {
        admin.connection.use { c ->
            c.createStatement().use { s -> users.forEach { s.execute("DROP USER $it CASCADE") } }
        }
    }

    class User(val name: String, val password: String, val dataSource: DataSource) {
        fun execute(vararg statements: String) = dataSource.connection.use { c ->
            c.createStatement().use { s -> statements.forEach { s.execute(it) } }
        }

        /** True when Oracle compiles and runs the anonymous block. */
        fun accepts(block: String): Boolean = try {
            execute(block)
            true
        } catch (e: SQLException) {
            false
        }

        fun <T> query(sql: String, row: (java.sql.ResultSet) -> T): List<T> = dataSource.connection.use { c ->
            c.createStatement().use { s ->
                s.executeQuery(sql).use { rs -> generateSequence { if (rs.next()) row(rs) else null }.toList() }
            }
        }

        override fun toString() = "User($name)"
    }

    companion object {
        private const val ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"

        fun connect(): OracleDatabase {
            val jdbcUrl = System.getenv("ZPA_ORACLE_JDBC_URL")
            val adminUser = System.getenv("ZPA_ORACLE_ADMIN_USER")
            val adminPassword = System.getenv("ZPA_ORACLE_ADMIN_PASSWORD")
            assumeTrue(jdbcUrl != null && adminUser != null && adminPassword != null, "ZPA_ORACLE_JDBC_URL/ADMIN_USER/ADMIN_PASSWORD not set")
            val admin = OracleDataSource().apply {
                setURL(jdbcUrl)
                user = adminUser
                setPassword(adminPassword)
            }
            admin.connection.use { it.isValid(5) }
            return OracleDatabase(jdbcUrl!!, admin)
        }
    }
}
