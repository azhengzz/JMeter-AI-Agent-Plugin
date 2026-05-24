# JDBC Connection Configuration

## Description

JDBC Connection Configuration creates a database connection pool that JDBC Sampler elements can use. It uses Apache Commons DBCP2 for connection pooling.

The connection pool is initialized at test start (`testStarted`) and stored as a JMeter variable object under the **Variable Name** (`dataSource`). All JDBC Samplers in the same thread group reference this pool by the same variable name.

### Pooling Modes

| poolMax Value | Mode | Behavior |
|---------------|------|----------|
| `0` | Per-thread | Each thread gets its own single-connection pool |
| `>0` | Shared | A shared pool with `poolMax` connections used by all threads |

## Parameters

| Property | Required | Default | Description | Example |
|----------|----------|---------|-------------|---------|
| `dataSource` | Yes | `""` | Variable name holding the connection pool. JDBC Sampler must use the same name. | `"mydb"` |
| `driver` | Yes | `""` | JDBC driver class name. Select from dropdown or enter custom class. Driver JAR must be in JMeter's classpath. (See supported drivers below) | `"com.mysql.jdbc.Driver"` |
| `dbUrl` | Yes | `""` | JDBC connection URL. | `"jdbc:mysql://localhost:3306/testdb"` |
| `username` | No | `""` | Database username. If empty, driver default is used. | `"root"` |
| `password` | No | `""` | Database password. | `"secret"` |
| `poolMax` | No | `"0"` | Max connection pool size. `0` = per-thread pooling, `>0` = shared pool. | `"10"` |
| `timeout` | No | `"10000"` | Max wait time (ms) to get a connection from pool. | `"10000"` |
| `autocommit` | No | `true` | Auto-commit mode for connections. | `"true"` |
| `transactionIsolation` | No | `"DEFAULT"` | Transaction isolation level (see table below). | `"TRANSACTION_READ_COMMITTED"` |
| `keepAlive` | No | `true` | Keep idle connections alive by validating and evicting stale ones. | `"true"` |
| `connectionAge` | No | `"5000"` | Max age (ms) of idle connections before eviction (requires keepAlive=true). | `"5000"` |
| `checkQuery` | No | `""` | SQL query to validate idle connections (requires keepAlive=true). | `"SELECT 1"` |
| `trimInterval` | No | `"60000"` | Interval (ms) between idle connection eviction runs (requires keepAlive=true). | `"60000"` |
| `preinit` | No | `false` | Eagerly initialize the connection pool at test start. | `"false"` |
| `initQuery` | No | `""` | SQL statements run when a connection is first created (one per line). | `"SET NAMES utf8mb4"` |
| `connectionProperties` | No | `""` | JDBC connection properties (semicolon-separated key=value). | `"ssl=true;connectTimeout=5000"` |
| `poolPreparedStatements` | No | `"-1"` | Max pooled prepared statements. `-1` = disabled, `0` = unlimited, `>0` = limit. | `"10"` |

### Transaction Isolation Levels

| Level | Value |
|-------|-------|
| `DEFAULT` | Use driver/database default |
| `TRANSACTION_NONE` | No transactions |
| `TRANSACTION_READ_UNCOMMITTED` | Can read uncommitted data |
| `TRANSACTION_READ_COMMITTED` | Only read committed data |
| `TRANSACTION_REPEATABLE_READ` | Consistent reads within a transaction |
| `TRANSACTION_SERIALIZABLE` | Full serialization of transactions |

### Supported JDBC Drivers

| Driver Class | Database | URL Pattern |
|-------------|----------|-------------|
| `com.mysql.jdbc.Driver` | MySQL | `jdbc:mysql://host:3306/dbname` |
| `org.postgresql.Driver` | PostgreSQL | `jdbc:postgresql://host:5432/dbname` |
| `oracle.jdbc.OracleDriver` | Oracle | `jdbc:oracle:thin:@host:1521:sid` |
| `com.ingres.jdbc.IngresDriver` | Ingres | `jdbc:ingres://host:II7/dbname` |
| `com.microsoft.sqlserver.jdbc.SQLServerDriver` | SQL Server | `jdbc:sqlserver://host:1433;databaseName=db` |
| `com.microsoft.jdbc.sqlserver.SQLServerDriver` | SQL Server (old) | `jdbc:microsoft:sqlserver://host:1433;DatabaseName=db` |
| `org.apache.derby.jdbc.ClientDriver` | Apache Derby | `jdbc:derby://host:1527/dbname` |
| `org.hsqldb.jdbc.JDBCDriver` | HSQLDB | `jdbc:hsqldb:hsql://host/dbname` |
| `com.ibm.db2.jcc.DB2Driver` | IBM DB2 | `jdbc:db2://host:50000/dbname` |
| `org.h2.Driver` | H2 | `jdbc:h2:mem:test` |
| `org.firebirdsql.jdbc.FBDriver` | Firebird | `jdbc:firebirdsql://host:3050/dbname` |
| `org.mariadb.jdbc.Driver` | MariaDB | `jdbc:mariadb://host:3306/dbname` |
| `org.sqlite.JDBC` | SQLite | `jdbc:sqlite:/path/to/file.db` |
| `net.sourceforge.jtds.jdbc.Driver` | SQL Server (jTDS) | `jdbc:jtds:sqlserver://host:1433/dbname` |
| `com.exasol.jdbc.EXADriver` | Exasol | `jdbc:exa:host:8563;schema=SCHEMA` |

## Usage Examples

### Example 1: MySQL Connection (Shared Pool)

```
create_jmeter_element with:
- elementType: "jdbcdatasource"
- elementName: "MySQL连接池"
- properties:
  - dataSource: "mydb"
  - driver: "com.mysql.cj.jdbc.Driver"
  - dbUrl: "jdbc:mysql://localhost:3306/testdb?useSSL=false&serverTimezone=UTC"
  - username: "root"
  - password: "123456"
  - poolMax: "10"
  - timeout: "10000"
  - autocommit: true
```

### Example 2: Per-Thread Pooling with Keep-Alive

```
create_jmeter_element with:
- elementType: "jdbcdatasource"
- elementName: "PostgreSQL连接-每线程独立"
- properties:
  - dataSource: "pgdb"
  - driver: "org.postgresql.Driver"
  - dbUrl: "jdbc:postgresql://db-server:5432/perfdb"
  - username: "tester"
  - password: "tester_pwd"
  - poolMax: "0"
  - keepAlive: true
  - checkQuery: "SELECT 1"
  - connectionAge: "5000"
  - trimInterval: "60000"
```

### Example 3: Connection with Init Query and Custom Properties

```
create_jmeter_element with:
- elementType: "jdbcdatasource"
- elementName: "Oracle连接-带初始化SQL"
- properties:
  - dataSource: "oracledb"
  - driver: "oracle.jdbc.OracleDriver"
  - dbUrl: "jdbc:oracle:thin:@oracle-server:1521:orcl"
  - username: "scott"
  - password: "tiger"
  - poolMax: "5"
  - autocommit: false
  - transactionIsolation: "TRANSACTION_READ_COMMITTED"
  - initQuery: "ALTER SESSION SET NLS_DATE_FORMAT='YYYY-MM-DD HH24:MI:SS'"
  - connectionProperties: "defaultRowPrefetch=100"
```

## Best Practices

1. **Variable Name**: Use a descriptive name for `dataSource` (e.g., `"mydb"`, `"orderdb"`) and ensure JDBC Sampler references the same name
2. **Pool sizing**: Use `poolMax: "0"` (per-thread) for most load tests. Use shared pool only when connections are limited
3. **Driver JAR**: Place the JDBC driver JAR in JMeter's `lib/` directory or add to classpath
4. **Keep-alive**: Enable `keepAlive: true` with a `checkQuery` like `SELECT 1` for long-running tests to prevent stale connections
5. **Password security**: Avoid hardcoding passwords; use JMeter properties (`${__P(db.password,)}`) or CSV data
6. **Preinit**: Enable `preinit: true` to catch connection issues early at test start rather than during sampling

## Notes

- The connection pool is created at test start and destroyed at test end
- Multiple JDBC Connection Configurations can coexist if they use different `dataSource` variable names
- The `dataSource` variable name must match the "Variable Name" field in JDBC Sampler
- When `poolMax: "0"`, each thread creates its own connection (no sharing overhead)
- Connection properties are passed directly to the JDBC driver
