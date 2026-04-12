# BeanShell Pre-Processor

## Description

BeanShell Pre-Processor executes BeanShell scripts before a sampler runs. Note: JSR223 with Groovy is recommended for better performance and thread safety.

## Parameters

| Property | Required | Description | Example |
|----------|----------|-------------|---------|
| `script` | Yes* | BeanShell script code to execute | See examples below |
| `filename` | Yes* | Path to external script file | `/path/to/script.bsh` |
| `parameters` | No | Parameters to pass to script | `param1 param2` |
| `resetInterpreter` | No | Reset interpreter before each execution | `false` |

## Usage Examples

### Example 1: Generate Timestamp

```
create_jmeter_element with:
- elementType: "beanshellpreprocessor"
- elementName: "生成时间戳"
- properties:
  - script: |
    import java.util.Date;
    String timestamp = new Date().toString();
    vars.put("request_timestamp", timestamp);
```

### Example 2: Prepare Request Data

```
create_jmeter_element with:
- elementType: "beanshellpreprocessor"
- elementName: "准备请求数据"
- properties:
  - script: |
    String orderId = "ORD-" + System.currentTimeMillis();
    vars.put("order_id", orderId);

    int quantity = Integer.parseInt(vars.get("quantity"));
    double price = Double.parseDouble(vars.get("price"));
    double total = quantity * price;
    vars.put("total_amount", String.valueOf(total));
```

### Example 3: Calculate Checksum

```
create_jmeter_element with:
- elementType: "beanshellpreprocessor"
- elementName: "计算请求签名"
- properties:
  - script: |
    import java.security.MessageDigest;

    String data = vars.get("user_id") + vars.get("timestamp") + "SECRET_KEY";
    MessageDigest md = MessageDigest.getInstance("MD5");
    byte[] digest = md.digest(data.getBytes());
    StringBuilder sb = new StringBuilder();
    for (byte b : digest) {
        sb.append(String.format("%02x", b));
    }
    vars.put("request_checksum", sb.toString());
```

## Built-in Variables

- `vars`: JMeterVariables - access/set JMeter variables
- `props`: Properties - access JMeter properties
- `ctx`: JMeterContext - access current context
- `sampler`: Sampler - access current sampler
- `prev`: SampleResult - previous sample result
- `Label`: String - element name
- `FileName`: String - script filename
- `Parameters`: String - script parameters
- `bsh.args`: String[] - parameters as array

## Best Practices

1. **Prefer JSR223 + Groovy**: Better performance and thread safety
2. **Keep scripts simple**: BeanShell has performance limitations
3. **Avoid resetInterpreter**: Causes significant performance degradation
4. **Use semicolons**: BeanShell requires proper Java syntax
5. **Cast explicitly**: Variables may need explicit type casting

## Notes

- BeanShell is being phased out in favor of JSR223
- Executes before the parent sampler
- Not thread-safe by default
- Script caching not available
- Consider migrating to JSR223 Pre-Processor with Groovy
