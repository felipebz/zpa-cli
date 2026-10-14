Copy the Oracle JDBC driver here to enable Oracle metadata acquisition (--oracle-url).

Use ojdbc17.jar (or ojdbc11.jar), downloaded from Maven Central
(com.oracle.database.jdbc:ojdbc17) or from Oracle. Every *.jar file in this
directory is searched for a java.sql.Driver that accepts the configured URL.
The driver is not bundled with zpa-cli.
