# JSR223 Sampler

## Description

JSR223 Sampler allows you to execute custom code using JSR223 compliant scripting languages. Groovy is the recommended language due to its performance and thread safety.

## Parameters

| Property | Required | Description | Example |
|----------|----------|-------------|---------|
| `scriptLanguage` | Yes | Scripting language | `groovy` |
| `script` | Yes* | Script code to execute | See examples below |
| `scriptFile` | Yes* | Path to external script file | `/path/to/script.groovy` |
| `parameters` | No | Parameters to pass to script | `param1,param2` |

*Note: Either script or scriptFile must be specified.

## Usage Examples

### Example 1: Simple Groovy Script

```
create_jmeter_element with:
- elementType: "jsr223sampler"
- elementName: "生成随机用户ID"
- properties:
  - scriptLanguage: "groovy"
  - script: |
    import java.util.UUID
    String userId = UUID.randomUUID().toString()
    vars.put("user_id", userId)
    SampleResult.setResponseData(userId.getBytes())
```

### Example 2: Make HTTP Request

```
create_jmeter_element with:
- elementType: "jsr223sampler"
- elementName: "调用外部API"
- properties:
  - scriptLanguage: "groovy"
  - script: |
    import groovy.json.JsonSlurper
    import groovy.json.JsonBuilder

    // Prepare request
    def url = "https://api.example.com/users"
    def connection = new URL(url).openConnection()
    connection.setRequestMethod("GET")
    connection.setRequestProperty("Authorization", "Bearer ${token}")

    // Send request
    def response = connection.inputStream.text
    def json = new JsonSlurper().parseText(response)

    // Store result
    vars.put("api_response", response)
    SampleResult.setResponseData(response.getBytes())
```

### Example 3: Database Query without JDBC Sampler

```
create_jmeter_element with:
- elementType: "jsr223sampler"
- elementName: "查询数据库"
- properties:
  - scriptLanguage: "groovy"
  - script: |
    import groovy.sql.Sql

    def sql = Sql.newInstance("jdbc:mysql://localhost:3306/mydb", "user", "pass", "com.mysql.jdbc.Driver")
    def rows = sql.rows("SELECT * FROM users WHERE status = 'active'")

    rows.each { row ->
      log.info("User: ${row.username}")
    }

    sql.close()
    SampleResult.setResponseData("Found ${rows.size()} active users".getBytes())
```

## Built-in Variables

### JMeter Variables
- `vars`: JMeterVariables - access/set JMeter variables
- `props`: Properties - access JMeter properties
- `ctx`: JMeterContext - access current context
- `prev`: SampleResult - access previous sample result
- `sampler`: Sampler - access current sampler
- `out`: PrintWriter - output to console
- `log`: Logger - write to JMeter log file

### SampleResult Methods
- `SampleResult.setResponseData(data)`: Set response data
- `SampleResult.setResponseCode(code)`: Set response code
- `SampleResult.setSuccessful(true/false)`: Set sample success
- `SampleResult.setResponseMessage(message)`: Set response message

## Best Practices

1. **Use Groovy**: Best performance with JSR223 + Groovy
2. **Cache compilation**: Enable "Cache compiled script if available"
3. **Thread safety**: Groovy scripts are thread-safe
4. **Reuse code**: Use scriptFile for complex scripts
5. **Error handling**: Add try-catch blocks for robustness

## Example: Error Handling

```groovy
try {
    // Your code here
    def result = someOperation()
    SampleResult.setSuccessful(true)
    SampleResult.setResponseData(result.toString().getBytes())
} catch (Exception e) {
    log.error("Error in script", e)
    SampleResult.setSuccessful(false)
    SampleResult.setResponseCode("500")
    SampleResult.setResponseMessage(e.message)
}
```

## Notes

- JSR223 scripts have access to all JMeter APIs
- Scripts run with the same permissions as JMeter
- For better performance, enable script caching
- Groovy is recommended over other languages (beanshell, javascript, etc.)
