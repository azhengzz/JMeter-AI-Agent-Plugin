# Debug Post-Processor

## Description

Debug Post-Processor displays JMeter variables, properties, and sampler information in the View Results Tree listener. It's essential for debugging and troubleshooting test plans.

## Parameters

| Property | Required | Description | Default |
|----------|----------|-------------|---------|
| `displayJMeterVariables` | No | Display JMeter variables | `false` |
| `displayJMeterProperties` | No | Display JMeter properties | `false` |
| `displaySamplerProperties` | No | Display sampler properties | `false` |
| `displaySystemProperties` | No | Display system properties | `false` |

## Usage Examples

### Example 1: Debug JMeter Variables

```
create_jmeter_element with:
- elementType: "debugpostprocessor"
- elementName: "显示JMeter变量"
- properties:
  - displayJMeterVariables: true
  - displayJMeterProperties: false
  - displaySamplerProperties: false
  - displaySystemProperties: false
```

### Example 2: Debug All Information

```
create_jmeter_element with:
- elementType: "debugpostprocessor"
- elementName: "完整调试信息"
- properties:
  - displayJMeterVariables: true
  - displayJMeterProperties: true
  - displaySamplerProperties: true
  - displaySystemProperties: true
```

### Example 3: Debug Sampler Properties

```
create_jmeter_element with:
- elementType: "debugpostprocessor"
- elementName: "显示采样器属性"
- properties:
  - displayJMeterVariables: false
  - displayJMeterProperties: false
  - displaySamplerProperties: true
  - displaySystemProperties: false
```

### Example 4: Combined with Extractor

```
// First, extract some data
create_jmeter_element with:
- elementType: "jsonpostprocessor"
- elementName: "提取Token"
- properties:
  - JSONPostProcessor.referenceNames: "token"
  - JSONPostProcessor.jsonPathExprs: "$.data.token"
  - JSONPostProcessor.match_numbers: "1"

// Then debug to verify extraction
create_jmeter_element with:
- elementType: "debugpostprocessor"
- elementName: "调试-检查Token"
- properties:
  - displayJMeterVariables: true
  - displayJMeterProperties: false
  - displaySamplerProperties: false
  - displaySystemProperties: false
```

## Viewing Debug Output

### Steps:

1. Add a **View Results Tree** listener to your test plan
2. Enable the desired Debug Post-Processor options
3. Run the test
4. In View Results Tree, select the sample result
5. Look for **Sub results** containing the debug output

### Debug Output Sections

#### JMeter Variables
```
JMeterVariables:
base_url=https://api.example.com
user_id=12345
auth_token=abc123xyz
product_count=5
```

#### JMeter Properties
```
JMeterProperties:
jmeter.engine.remote.mode=false
jmeter.save.saveservice.response_data=true
jmeter.threads.uelementtag=complement
```

#### Sampler Properties
```
SamplerProperties:
TestElement.gui_class=org.apache.jmeter.protocol.http.control.gui.HttpTestSampleGui
TestElement.test_class=org.apache.jmeter.protocol.http.sampler.HTTPSamplerProxy
HTTPSampler.domain=api.example.com
HTTPSampler.port=443
```

#### System Properties
```
SystemProperties:
java.version=17.0.1
os.name=Windows 10
user.home=C:\Users\TestUser
file.separator=\
```

## Best Practices

1. **Use during development**: Enable for debugging, disable for production
2. **Targeted debugging**: Only enable sections you need
3. **Remove before load test**: Debug output impacts performance
4. **Combine with View Results Tree**: Essential for viewing output
5. **Check variable scope**: Verify variable visibility across thread groups

## Tips

1. **Quick check**: Enable only JMeter Variables for most debugging
2. **Performance**: Disable all options for actual load testing
3. **Variable lifecycle**: See when variables are created/destroyed
4. **Cross-thread issues**: Debug properties for cross-thread variables
5. **Sub results**: Debug output appears as sub-samples in results

## Common Use Cases

### Verify Extraction

After a JSON/Regex Extractor, verify the variable was created:
```
JMeter Variables:
user_id=123
```

### Check Property Scope

Verify if a value is stored as variable or property:
```
JMeter Variables: (not found)
JMeter Properties: global_value=test
```

### Debug Sampler Configuration

Verify sampler properties are set correctly:
```
Sampler Properties:
HTTPSampler.path=/api/users
HTTPSampler.method=GET
```

### Trace Variable Changes

Add multiple Debug Post-Processors to track variable changes throughout test flow.

## Performance Impact

| Setting | Performance Impact |
|---------|-------------------|
| All enabled | HIGH (avoid in load tests) |
| Variables only | LOW |
| Properties only | LOW |
| System properties | MEDIUM |

## Notes

- Output appears as sub-samples in View Results Tree
- Affects test performance - disable for load testing
- Only displays data for samplers in same scope
- Useful for troubleshooting variable extraction issues
- Does not modify any variables or properties
