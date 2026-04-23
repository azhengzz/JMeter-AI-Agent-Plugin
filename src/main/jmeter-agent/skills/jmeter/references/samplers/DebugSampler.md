# Debug Sampler

## Description

The Debug Sampler generates a sample containing the values of all JMeter variables and/or properties. The values can be seen in the View Results Tree Listener Response Data pane.

## Parameters

| Property | Required | Default | Description | Example |
|----------|----------|---------|-------------|---------|
| `displayJMeterProperties` | No | `true` | Include JMeter properties? | `"false"` |
| `displayJMeterVariables` | No | `true` | Include JMeter variables? | `"true"` |
| `displaySystemProperties` | No | `false` | Include System properties? | `"false"` |

## Usage Examples

### Example 1: Debug All Variables and Properties

```
create_jmeter_element with:
- elementType: "debugsampler"
- elementName: "调试所有变量"
- properties:
  - displayJMeterProperties: "true"
  - displayJMeterVariables: "true"
  - displaySystemProperties: "false"
```

### Example 2: Show Only JMeter Variables

```
create_jmeter_element with:
- elementType: "debugsampler"
- elementName: "仅显示JMeter变量"
- properties:
  - displayJMeterProperties: "false"
  - displayJMeterVariables: "true"
  - displaySystemProperties: "false"
```

### Example 3: Full Debug with System Properties

```
create_jmeter_element with:
- elementType: "debugsampler"
- elementName: "完整调试输出"
- properties:
  - displayJMeterProperties: "true"
  - displayJMeterVariables: "true"
  - displaySystemProperties: "true"
```

## Best Practices

1. **Use during development**: Add Debug Samplers while building test plans to inspect variable values
2. **Disable in production**: Remove or disable Debug Samplers before running load tests as they add overhead
3. **Use with View Results Tree**: Combine with View Results Tree listener to see debug output
4. **Minimize properties display**: Disable System Properties unless specifically needed to reduce output size

## Notes

- This sampler generates a sample containing debug information as response data
- Useful for troubleshooting variable extraction and correlation issues
- Does not generate any network traffic
- Can be placed anywhere in the test plan to inspect variables at that point
