# BeanShell Sampler

## Description

BeanShell Sampler allows you to execute BeanShell scripts to perform custom operations during your test. It can generate custom responses, modify variables, or perform any custom logic.

**Note:** JSR223 Sampler with Groovy is recommended for better performance and thread safety.

## Parameters

| Property | Required | Description | Example |
|----------|----------|-------------|---------|
| `BeanShellSampler.parameters` | No | Parameters to pass to script | `param1=value1,param2=value2` |
| `BeanShellSampler.filename` | No | External script file path | `/scripts/test.bsh` |
| `BeanShellSampler.query` | Yes* | BeanShell script code | See examples below |
| `BeanShellSampler.resetInterpreter` | No | Reset interpreter before execution | `false` |

*Note: Either script (query) or filename must be specified.

## Available Variables

| Variable | Description |
|----------|-------------|
| `SampleResult` | Current SampleResult object |
| `ResponseCode` | HTTP response code (default: "200") |
| `ResponseMessage` | Response message (default: "OK") |
| `IsSuccess` | Success status (default: true) |
| `vars` | JMeterVariables |
| `props` | JMeterProperties |
| `log` | Logger |
| `Parameters` | Parameters string (if parameters provided) |
| `bsh.args` | Parameters array (if parameters provided) |

## Usage Examples

### Example 1: Generate Custom Response

```
create_jmeter_element with:
- elementType: "beanshellsampler"
- elementName: "生成测试数据"
- properties:
  - BeanShellSampler.query: |
    String testData = "id=" + (int)(Math.random() * 1000);
    SampleResult.setResponseData(testData.getBytes());
    ResponseCode = "200";
    IsSuccess = true;
```

### Example 2: Print Current Time

```
create_jmeter_element with:
- elementType: "beanshellsampler"
- elementName: "打印当前时间"
- properties:
  - BeanShellSampler.query: |
    import java.util.Date;
    Date now = new Date();
    String timeStr = now.toString();
    
    print("Current time: " + timeStr);
    log.info("Current time: " + timeStr);
    
    SampleResult.setResponseData(timeStr.getBytes());
    ResponseCode = "200";
    IsSuccess = true;
```

### Example 3: Extract and Process Data

```
create_jmeter_element with:
- elementType: "beanshellsampler"
- elementName: "处理提取的数据"
- properties:
  - BeanShellSampler.query: |
    String userId = vars.get("user_id");
    String userName = vars.get("user_name");
    
    String result = "User: " + userName + " (ID: " + userId + ")";
    SampleResult.setResponseData(result.getBytes());
    
    if (userId == null) {
        ResponseCode = "404";
        ResponseMessage = "User not found";
        IsSuccess = false;
    }
```

### Example 4: Using Parameters

```
create_jmeter_element with:
- elementType: "beanshellsampler"
- elementName: "带参数的脚本"
- properties:
  - BeanShellSampler.parameters: "delay=1000,retry=3"
  - BeanShellSampler.query: |
    // Access parameters
    String params = Parameters;
    String[] args = bsh.args;
    
    print("Full parameters: " + params);
    print("First param: " + args[0]);
    
    // Parse parameters
    for (int i = 0; i < args.length; i++) {
        String[] pair = args[i].split("=");
        print("Key: " + pair[0] + ", Value: " + pair[1]);
    }
```

### Example 5: Using External Script File

```
create_jmeter_element with:
- elementType: "beanshellsampler"
- elementName: "执行外部脚本"
- properties:
  - BeanShellSampler.filename: "/scripts/my-script.bsh"
  - BeanShellSampler.resetInterpreter: false
```

### Example 6: Pause with Custom Duration

```
create_jmeter_element with:
- elementType: "beanshellsampler"
- elementName: "动态暂停"
- properties:
  - BeanShellSampler.parameters: "1000"
  - BeanShellSampler.query: |
    String delay = Parameters;
    long sleepTime = Long.parseLong(delay);
    Thread.sleep(sleepTime);
    SampleResult.setResponseData(("Paused for " + sleepTime + "ms").getBytes());
```

### Example 7: Read from File

```
create_jmeter_element with:
- elementType: "beanshellsampler"
- elementName: "读取配置文件"
- properties:
  - BeanShellSampler.query: |
    import java.io.*;
    BufferedReader reader = new BufferedReader(new FileReader("data.txt"));
    String line;
    StringBuilder result = new StringBuilder();
    while ((line = reader.readLine()) != null) {
        result.append(line).append("\n");
    }
    reader.close();
    SampleResult.setResponseData(result.toString().getBytes());
```

### Example 8: Database Operation (Manual)

```
create_jmeter_element with:
- elementType: "beanshellsampler"
- elementName: "手动数据库查询"
- properties:
  - BeanShellSampler.query: |
    import java.sql.*;
    Class.forName("com.mysql.jdbc.Driver");
    Connection conn = DriverManager.getConnection("jdbc:mysql://localhost:3306/mydb", "user", "pass");
    Statement stmt = conn.createStatement();
    ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM users");
    
    if (rs.next()) {
        int count = rs.getInt(1);
        SampleResult.setResponseData(("Total users: " + count).getBytes());
        vars.put("user_count", String.valueOf(count));
    }
    
    rs.close();
    stmt.close();
    conn.close();
```

## Return Value

The script can return a String value:
- If non-null: Sets response data to returned string
- If null: Response data is set via `SampleResult.setResponseData()`

## Response Control

Set these variables to control the sample result:

```beanshell
ResponseCode = "200";           // HTTP status code
ResponseMessage = "OK";         // Status message
IsSuccess = true;               // Success/failure
SampleResult.setResponseData(data);  // Response body
SampleResult.setDataType(data_type);  // Content type
```

## Error Handling

```beanshell
try {
    // Your code here
    print("Operation successful");
} catch (Exception e) {
    ResponseCode = "500";
    ResponseMessage = e.toString();
    IsSuccess = false;
    print("Error: " + e.toString());
}
```

## Best Practices

1. **Prefer JSR223 + Groovy**: Better performance and thread safety
2. **Use try-catch**: Handle exceptions gracefully
3. **Set response values**: Always set ResponseCode, ResponseMessage, IsSuccess
4. **Return values**: Return String to set response data
5. **Reset interpreter**: Only when needed (performance impact)

## Tips

1. **Debug output**: Use `print()` to output to console
2. **Logging**: Use `log.info()`, `log.warn()`, `log.error()`
3. **Variable access**: Use `vars.get()` and `vars.put()`
4. **External scripts**: Use .bsh files for complex logic
5. **Parameters**: Access via `Parameters` (String) or `bsh.args` (array)

## Common Pitfalls

1. **Thread safety**: BeanShell has thread safety issues
2. **Performance**: Significantly slower than JSR223
3. **Memory leaks**: Can leak memory without resetInterpreter
4. **Deprecated**: Being phased out in favor of JSR223
5. **Limited API**: Not all Java classes available

## Comparison with JSR223

| Feature | BeanShell | JSR223 + Groovy |
|---------|-----------|-----------------|
| Performance | Slow (interpreted) | Fast (compiled cache) |
| Thread Safety | Issues | Safe |
| Syntax | Limited Java | Full Java/Groovy |
| Maintenance | Deprecated | Recommended |

## Notes

- Executes during test run like a regular sampler
- Can generate custom response data
- Has access to all JMeter variables and properties
- Return null if using `SampleResult.setResponseData()`
- Consider migrating to JSR223 for new scripts
