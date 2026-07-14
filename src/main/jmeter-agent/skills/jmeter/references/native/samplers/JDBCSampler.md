# JDBC Sampler
> **Source**: Apache JMeter built-in component (ships with JMeter, no extra install needed)

## Description

This sampler lets you send a JDBC Request (an SQL query) to a database. Before using this you need to set up a JDBC Connection Configuration Configuration element.

If the Variable Names list is provided, then for each row returned by a Select statement, the variables are set up with the value of the corresponding column (if a variable name is provided), and the count of rows is also set up. For example, if the Select statement returns 2 rows of 3 columns, and the variable list is `A,,C`, then the following variables will be set up:

```
A_#=2 (number of rows)
A_1=column 1, row 1
A_2=column 1, row 2
C_#=2 (number of rows)
C_1=column 3, row 1
C_2=column 3, row 2
```

If the Select statement returns zero rows, then the `A_#` and `C_#` variables would be set to `0`, and no other variables would be set. Old variables are cleared if necessary - e.g. if the first select retrieves six rows and a second select returns only three rows, the additional variables for rows four, five and six will be removed.

**Note:** The latency time is set from the time it took to acquire a connection.

## Parameters

| Property | Required | Default | Description | Example |
|----------|----------|---------|-------------|---------|
| `dataSource` | Yes | — | Variable Name of Pool declared in JDBC Connection Configuration. Name of the JMeter variable that the connection pool is bound to. | `"myDatabase"` |
| `queryType` | Yes | — | Type of query. See Query Type Values below. | `"Select Statement"` |
| `query` | Yes | — | SQL query to execute. Do not enter a trailing semi-colon. | `"SELECT * FROM users WHERE id = ?"` |
| `queryArguments` | No | — | Comma-separated list of parameter values. Use `]NULL[` to indicate a NULL parameter. Required if a prepared or callable statement has parameters. | `"${user_id}"` |
| `queryArgumentsTypes` | No | — | Comma-separated list of SQL parameter types (e.g. INTEGER, DATE, VARCHAR, DOUBLE) or integer values of Constants defined in `java.sql.Types`. Required if a prepared or callable statement has parameters. | `"VARCHAR,INTEGER"` |
| `variableNames` | No | — | Comma-separated list of variable names to hold values returned by Select statements, Prepared Select Statements or CallableStatement. | `"username,email"` |
| `resultVariable` | No | — | If specified, creates an Object variable containing a list of row maps where each map has column name as key and column data as value. | `"results"` |
| `queryTimeout` | No | — | Set a timeout in seconds for query. Empty value means 0 (infinite). `-1` means don't set any query timeout. Min: 0. | `"30"` |
| `resultSetMaxRows` | No | — | Limits the number of rows to iterate through the ResultSet. Empty value means `-1` (no limitation). | `"100"` |
| `resultSetHandler` | Yes | `"Store as String"` | Defines how ResultSet returned from callable statements be handled. See Result Set Handler Values below. | `"Store as String"` |

### Query Type Values

| Value | Description |
|-------|-------------|
| `Select Statement` | Execute a SELECT query |
| `Update Statement` | Execute INSERT, UPDATE, or DELETE statements |
| `Callable Statement` | Execute stored procedures |
| `Prepared Select Statement` | Execute parameterized SELECT queries |
| `Prepared Update Statement` | Execute parameterized INSERT, UPDATE, or DELETE queries |
| `Commit` | Commit current transaction (ignores SQL) |
| `Rollback` | Rollback current transaction (ignores SQL) |
| `AutoCommit(false)` | Disable auto-commit (ignores SQL) |
| `AutoCommit(true)` | Enable auto-commit (ignores SQL) |

**Note:** The types `Commit`, `Rollback`, `AutoCommit(false)` and `AutoCommit(true)` are special, as they ignore the given SQL statements and only change the state of the connection.

### Result Set Handler Values

| Value | Description |
|-------|-------------|
| `Store as String` | All variables stored as strings. CLOBs converted to Strings. BLOBs converted as UTF-8 encoded byte-array. |
| `Store as Object` | ResultSet variables stored as Object and can be accessed in subsequent tests. CLOBs handled as String. BLOBs stored as byte array. |
| `Count Records` | ResultSet variables iterated through showing the count of records as result. For BLOBs the size is stored. |

## Usage Examples

### Example 1: SELECT Query

```
create_jmeter_element with:
- elementType: "jdbcsampler"
- elementName: "查询用户信息"
- properties:
  - dataSource: "mysql_db"
  - queryType: "Select Statement"
  - query: "SELECT * FROM users WHERE id = ${user_id}"
  - variableNames: "username,email"
  - resultVariable: "user_data"
```

### Example 2: INSERT Query

```
create_jmeter_element with:
- elementType: "jdbcsampler"
- elementName: "插入订单"
- properties:
  - dataSource: "mysql_db"
  - queryType: "Update Statement"
  - query: "INSERT INTO orders (user_id, product_id, quantity) VALUES (${user_id}, ${product_id}, ${quantity})"
```

### Example 3: Prepared Statement with Parameters

```
create_jmeter_element with:
- elementType: "jdbcsampler"
- elementName: "按名称查询产品"
- properties:
  - dataSource: "mysql_db"
  - queryType: "Prepared Select Statement"
  - query: "SELECT * FROM products WHERE name = ? AND category = ?"
  - queryArguments: "${product_name},${category}"
  - queryArgumentsTypes: "VARCHAR,VARCHAR"
  - variableNames: "id,price,stock"
```

### Example 4: Callable Statement (Stored Procedure)

```
create_jmeter_element with:
- elementType: "jdbcsampler"
- elementName: "调用存储过程"
- properties:
  - dataSource: "mysql_db"
  - queryType: "Callable Statement"
  - query: "{call get_user_by_id(?,?)}"
  - queryArguments: "${user_id},]NULL["
  - queryArgumentsTypes: "INTEGER,OUT VARCHAR"
  - variableNames: "user_name"
```

## Best Practices

1. **Use connection pooling**: Reuse connections by configuring JDBC Connection Configuration
2. **Parameterized queries**: Use prepared statements to prevent SQL injection
3. **Result variable handling**: Use `variableNames` to extract specific columns
4. **Timeout settings**: Set appropriate query timeouts to prevent hanging
5. **Connection cleanup**: Ensure proper connection pool configuration
6. **Unique variable names**: Ensure Variable Name is unique across Test Plan

## Notes

- Before using this sampler, set up a JDBC Connection Configuration element
- Result columns can be accessed as `${variableName_1}`, `${variableName_2}`, etc.
- For UPDATE/INSERT/DELETE, the row count is saved
- CallableStatement supports INOUT and OUT parameters by prefixing types (e.g. `INOUT INTEGER`)
- Current versions of JMeter use UTF-8 as the character encoding
