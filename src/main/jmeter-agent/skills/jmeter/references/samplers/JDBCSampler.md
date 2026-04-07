# JDBC Sampler

## Description

JDBC Sampler allows you to execute database queries using JDBC. It supports SQL queries for testing database performance under load.

## Parameters

| Property | Required | Description | Example |
|----------|----------|-------------|---------|
| `dataSource` | Yes | JDBC DataSource configuration name | `myDatabase` |
| `queryType` | Yes | Type of query: Select, Update, Callable, etc. | `Select Statement` |
| `query` | Yes | SQL query to execute | `SELECT * FROM users WHERE id = ?` |
| `queryArguments` | No | Query parameters (for prepared statements) | `${user_id}` |
| `queryArgumentsTypes` | No | JDBC types for parameters | `VARCHAR`, `INTEGER`, `NUMERIC` |
| `variableNames` | No | Variable names to store result columns | `user_name,email` |
| `resultVariable` | No | Variable name to store entire result set | `results` |
| `queryTimeout` | No | Query timeout in seconds | `30` |

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

### Example 3: Prepared Statement

```
create_jmeter_element with:
- elementType: "jdbcsampler"
- elementName: "按名称查询产品"
- properties:
  - dataSource: "mysql_db"
  - queryType: "Select Statement"
  - query: "SELECT * FROM products WHERE name = ?"
  - queryArguments: "${product_name}"
  - queryArgumentsTypes: "VARCHAR"
  - variableNames: "id,price,stock"
```

## Prerequisites

1. **JDBC Connection Pool**: Configure JDBC Connection Configuration in test plan
2. **Database Driver**: Add appropriate JDBC driver JAR to JMeter classpath

## JDBC Connection Configuration

```
create_jmeter_element with:
- elementType: "jdbcconnectionpool"
- elementName: "MySQL连接池"
- properties:
  - dataSource: "mysql_db"
  - url: "jdbc:mysql://localhost:3306/mydb"
  - driver: "com.mysql.jdbc.Driver"
  - username: "test_user"
  - password: "test_pass"
  - poolMax: 10
```

## Best Practices

1. **Use connection pooling**: Reuse connections for better performance
2. **Parameterized queries**: Use prepared statements to prevent SQL injection
3. **Result variable handling**: Use variableNames to extract specific columns
4. **Timeout settings**: Set appropriate query timeouts
5. **Connection cleanup**: Ensure proper connection pool configuration

## Notes

- Result columns can be accessed as `${variableName_1}`, `${variableName_2}`, etc.
- For SELECT queries, use `variableNames` to specify column names to save
- For UPDATE/INSERT/DELETE, row count is saved as `${updateCount}`
- CallableStatement support allows execution of stored procedures
