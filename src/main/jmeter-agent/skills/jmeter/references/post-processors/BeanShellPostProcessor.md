# BeanShell Post-Processor

## Description

BeanShell Post-Processor executes BeanShell scripts after a sampler completes. It allows custom processing of response data and variable manipulation.

**Note:** JSR223 Post-Processor with Groovy is recommended for better performance and thread safety.

## Parameters

| Property | Required | Description | Example |
|----------|----------|-------------|---------|
| `parameters` | No | Parameters to pass to script | `param1=value1,param2=value2` |
| `filename` | No | External script file path | `/scripts/post-process.bsh` |
| `resetInterpreter` | No | Reset interpreter before execution | `false` |
| `script` | No | Inline BeanShell script | See examples below |

## Available Variables

| Variable | Description |
|----------|-------------|
| `ctx` | JMeterContext (current thread context) |
| `vars` | JMeterVariables (variables storage) |
| `props` | JMeterProperties (global properties) |
| `prev` | Previous SampleResult (response data) |
| `data` | Response data as byte array |
| `log` | Logger for output |

## Usage Examples

### Example 1: Extract and Modify Variable

```beanshell
// Get response data
String response = new String(data);

// Extract value using regex
import java.util.regex.*;
Pattern p = Pattern.compile("token\":\"([^\"]+)\"");
Matcher m = p.matcher(response);
if (m.find()) {
    String token = m.group(1);
    vars.put("auth_token", token);
    log.info("Extracted token: " + token);
}
```

### Example 2: Parse JSON Response

```beanshell
import org.json.JSONObject;

// Parse JSON response
String response = new String(data);
JSONObject json = new JSONObject(response);

// Extract values
String userId = json.getString("userId");
String status = json.getString("status");

// Store in variables
vars.put("user_id", userId);
vars.put("api_status", status);
```

### Example 3: Calculate Response Time

```beanshell
// Get response time
long responseTime = prev.getTime();

// Categorize performance
if (responseTime < 100) {
    vars.put("performance_level", "FAST");
} else if (responseTime < 500) {
    vars.put("performance_level", "NORMAL");
} else {
    vars.put("performance_level", "SLOW");
}

log.info("Response time: " + responseTime + "ms - " + vars.get("performance_level"));
```

### Example 4: Conditional Logic

```beanshell
// Check response code
String responseCode = prev.getResponseCode();

if (responseCode.equals("200")) {
    vars.put("request_success", "true");
    // Parse success response
    String response = new String(data);
    // Process response...
} else if (responseCode.equals("401")) {
    vars.put("request_success", "false");
    vars.put("error_reason", "Unauthorized");
} else {
    vars.put("request_success", "false");
    vars.put("error_reason", "Unknown error");
}
```

### Example 5: Base64 Encoding

```beanshell
import org.apache.commons.codec.binary.Base64;

// Encode credentials
String username = vars.get("username");
String password = vars.get("password");
String credentials = username + ":" + password;

String encoded = Base64.encodeBase64String(credentials.getBytes());
vars.put("basic_auth_header", "Basic " + encoded);
```

### Example 6: Timestamp Formatting

```beanshell
import java.text.SimpleDateFormat;
import java.util.Date;

// Get current timestamp
long timestamp = System.currentTimeMillis();
Date date = new Date(timestamp);

// Format date
SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
String formattedDate = sdf.format(date);

vars.put("current_timestamp", formattedDate);
log.info("Timestamp: " + formattedDate);
```

### Example 7: List Processing

```beanshell
// Split CSV response into list
String response = new String(data);
String[] items = response.split(",");

// Store items
for (int i = 0; i < items.length; i++) {
    vars.put("item_" + (i + 1), items[i].trim());
}

vars.put("item_count", String.valueOf(items.length));
```

### Example 8: Using External Parameters

```beanshell
// Script receives: userId=12345,action=query
String userId = Parameters; // Full parameter string
String action = bsh.args[0]; // First parameter as array

log.info("Processing for user: " + userId);
log.info("Action: " + action);

// Use parameters in logic
if (action.equals("query")) {
    vars.put("api_endpoint", "/api/users/" + userId);
}
```

## Script File vs Inline Script

### Using Script File

```
filename: /scripts/extract-token.bsh
parameters: userId=${user_id}
resetInterpreter: false
script: (empty)
```

### Using Inline Script

```
filename: (empty)
script: |
  String response = new String(data);
  // Your script here...
```

## Best Practices

1. **Use JSR223 + Groovy**: Better performance and thread safety
2. **Avoid complex logic**: Keep scripts simple and readable
3. **Use log.debug()**: For debug output instead of log.info()
4. **Handle exceptions**: Wrap code in try-catch blocks
5. **Test first**: Validate script logic independently

## Tips

1. **Debug Sampler**: Add Debug Sampler to view variable values
2. **Reset interpreter**: Only reset when necessary (performance impact)
3. **External scripts**: Use .bsh files for reusable complex logic
4. **BeanShell limitations**: Limited Java syntax support
5. **Migrate to Groovy**: Consider migrating existing scripts to Groovy

## Comparison with JSR223

| Feature | BeanShell | JSR223 + Groovy |
|---------|-----------|-----------------|
| Performance | Slow (interpreted) | Fast (compiled cache) |
| Thread Safety | Issues | Safe |
| Syntax | Limited Java | Full Java/Groovy |
| Maintenance | Deprecated | Recommended |

## Common Pitfalls

1. **Thread safety**: BeanShell has thread safety issues
2. **Performance**: Significantly slower than JSR223
3. **Memory leaks**: Can leak memory without resetInterpreter
4. **Limited API**: Not all Java classes available
5. **Deprecated**: Being phased out in favor of JSR223

## Notes

- BeanShell interpreter is shared across threads (use resetInterpreter=true to isolate)
- Scripts execute after sampler but before assertions
- `data` variable contains raw response bytes
- `prev` provides full SampleResult access
- Consider migrating to JSR223 + Groovy for new scripts
