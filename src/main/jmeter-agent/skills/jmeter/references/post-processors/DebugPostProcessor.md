# Debug Post-Processor

## Description
The Debug PostProcessor creates a subSample with the details of the previous Sampler properties, JMeter variables, properties and/or System Properties.

The values can be seen in the View Results Tree Listener Response Data pane.

## Parameters
| Property | Required | Default | Description | Example |
|----------|----------|---------|-------------|---------|
| `displayJMeterVariables` | No | `true` | Whether to show JMeter variables in the sub-sampler results. | `"true"` |
| `displayJMeterProperties` | No | `false` | Whether to show JMeter properties in the sub-sampler results. | `"false"` |
| `displaySamplerProperties` | No | `true` | Whether to show sampler properties in the sub-sampler results. | `"true"` |
| `displaySystemProperties` | No | `false` | Whether to show system properties in the sub-sampler results. | `"false"` |

## Usage Examples
### Example 1: Debug JMeter Variables
```
create_jmeter_element with:
- elementType: "debugpostprocessor"
- elementName: "显示JMeter变量"
- properties:
  - displayJMeterVariables: "true"
  - displayJMeterProperties: "false"
  - displaySamplerProperties: "false"
  - displaySystemProperties: "false"
```

### Example 2: Show All Debug Information
```
create_jmeter_element with:
- elementType: "debugpostprocessor"
- elementName: "完整调试信息"
- properties:
  - displayJMeterVariables: "true"
  - displayJMeterProperties: "true"
  - displaySamplerProperties: "true"
  - displaySystemProperties: "true"
```

### Example 3: Debug After Extraction
```
// After a JSON Post Processor or Regex Extractor, verify extraction
create_jmeter_element with:
- elementType: "debugpostprocessor"
- elementName: "验证提取结果"
- properties:
  - displayJMeterVariables: "true"
  - displaySamplerProperties: "true"
```

## Best Practices
1. **Use during development only**: Disable or remove before production load tests
2. **Targeted debugging**: Only enable the sections you need to inspect
3. **Combine with View Results Tree**: Essential for viewing debug output
4. **Check variable scope**: Use to verify variable visibility across thread groups
5. **Remove before load test**: Debug output adds overhead and impacts performance

## Notes
- Output appears as sub-samples in the View Results Tree listener
- Affects test performance; disable for load testing
- Only displays data for samplers in the same scope
- Useful for troubleshooting variable extraction issues
- Does not modify any variables or properties
