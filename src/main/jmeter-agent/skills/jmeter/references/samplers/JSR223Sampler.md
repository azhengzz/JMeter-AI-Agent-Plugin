# JSR223 Sampler

## Description

JSR223 Sampler allows you to execute custom code using JSR223 compliant scripting languages. Groovy is the recommended language due to its performance and thread safety.

## Parameters

| Property | Required | Description | Example |
|----------|----------|-------------|---------|
| `scriptLanguage` | Yes | Scripting language | `groovy` |
| `script` | Yes* | Script code to execute | See examples below |
| `filename` | Yes* | Path to external script file | `/path/to/script.groovy` |
| `parameters` | No | Parameters to pass to script | `param1,param2` |
| `cacheKey` | No | Enable script compilation caching | `true` (enabled), `false` (disabled) |

*Note: Either script or filename must be specified.

## Usage Examples

### Example 1: Simple Groovy Script

```
create_jmeter_element with:
- elementType: "jsr223sampler"
- elementName: "生成随机用户ID"
- properties:
  - scriptLanguage: "groovy"
  - cacheKey: "true"
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
  - cacheKey: "true"
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

### Example 3: Using External Script File

```
create_jmeter_element with:
- elementType: "jsr223sampler"
- elementName: "执行脚本文件"
- properties:
  - scriptLanguage: "groovy"
  - filename: "/path/to/script.groovy"
  - cacheKey: "true"
```

### Example 4: Database Query without JDBC Sampler

```
create_jmeter_element with:
- elementType: "jsr223sampler"
- elementName: "查询数据库"
- properties:
  - scriptLanguage: "groovy"
  - cacheKey: "true"
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

### Example 5: Passing Parameters

```
create_jmeter_element with:
- elementType: "jsr223sampler"
- elementName: "带参数的脚本"
- properties:
  - scriptLanguage: "groovy"
  - parameters: "userId=12345,action=query"
  - cacheKey: "true"
  - script: |
    // Parameters available as 'Parameters' string and 'args' array
    String userId = Parameters
    String[] args = bsh.args

    log.info("Full parameters: " + userId)
    log.info("First arg: " + args[0])
```

## Built-in Variables

### JMeter Variables
- `vars`: JMeterVariables - access/set JMeter variables
- `props`: Properties - access JMeter properties
- `ctx`: JMeterContext - access current context
- `prev`: SampleResult - access previous sample result
- `sampler`: Sampler - access current sampler
- `SampleResult`: Current SampleResult object for this sampler
- `out`: PrintWriter - output to console
- `log`: Logger - write to JMeter log file
- `Parameters`: Parameters string (if parameters provided)
- `args`: Parameters array (if parameters provided)

### SampleResult Methods
- `SampleResult.setResponseData(data)`: Set response data
- `SampleResult.setResponseCode(code)`: Set response code
- `SampleResult.setSuccessful(true/false)`: Set sample success
- `SampleResult.setResponseMessage(message)`: Set response message

## Script Languages

| Language | Value | Performance | Notes |
|----------|-------|-------------|-------|
| Groovy | `groovy` | Best | Recommended |
| BeanShell | `beanshell` | Slow | Deprecated |
| Java | `java` | Good | Requires Java knowledge |
| JavaScript | `javascript` | Medium | Engine dependent |
| JEXL | `jexl` | Medium | Expression language |
| JEXL2 | `jexl2` | Medium | Expression language |

## Best Practices

1. **Use Groovy**: Best performance with JSR223 + Groovy
2. **Enable caching**: Set `cacheKey: "true"` for better performance
3. **Thread safety**: Groovy scripts are thread-safe when cached
4. **Reuse code**: Use filename for complex scripts
5. **Error handling**: Add try-catch blocks for robustness
6. **Disable caching for dynamic scripts**: Set `cacheKey: "false"` if script changes at runtime

## Script Caching

When `cacheKey: "true"` (default):
- Script is compiled and cached after first execution
- Subsequent executions use cached compiled script
- Much better performance for static scripts
- Thread-safe with Groovy

When `cacheKey: "false"`:
- Script is recompiled on each execution
- Necessary for scripts that change dynamically
- Slower performance
- Use only when needed

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

## Example: Accessing Parameters

```groovy
// Parameters passed as: "param1=value1,param2=value2"
String params = Parameters  // Full parameter string
String[] args = bsh.args     // Array of parameters

// Access individual parameters
String param1 = args[0]      // "param1=value1"
String param2 = args[1]      // "param2=value2"

// Parse parameters if needed
params.split(',').each { pair ->
    def (key, value) = pair.split('=')
    log.info("Key: ${key}, Value: ${value}")
}
```

## Notes

- JSR223 scripts have access to all JMeter APIs
- Scripts run with the same permissions as JMeter
- Enable script caching (`cacheKey: "true"`) for better performance
- Groovy is recommended over other languages
- Use external script files for complex or reusable scripts
- Parameters are available as `Parameters` (String) and `args` (String[])