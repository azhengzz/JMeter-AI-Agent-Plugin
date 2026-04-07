# If Controller

## Description

If Controller allows conditional execution of its child elements. Child samplers/controllers execute only when the specified condition evaluates to true.

## Source Code

Based on Apache JMeter source: `org.apache.jmeter.control.IfController`

## Parameters

| Property | Required | Default | Description | Example |
|----------|----------|---------|-------------|---------|
| `IfController.condition` | Yes | `""` (empty string) | Condition expression to evaluate | `${user_id} != ""` |
| `IfController.useExpression` | No | `true` | Use expression instead of JavaScript evaluation | `true` |
| `IfController.evaluateAll` | No | `false` | Evaluate all child elements even if condition is false | `false` |

## Parameter Details

### IfController.condition
- **Required**: Yes
- **Default**: Empty string `""`
- **Description**: The condition expression that determines if children should execute

### IfController.useExpression
- **Required**: No
- **Default**: `true`
- **Description**: 
  - When `true`: Condition is evaluated as a simple expression (variable check)
  - When `false`: Condition is evaluated as JavaScript code

### IfController.evaluateAll
- **Required**: No
- **Default**: `false`
- **Description**:
  - When `false`: Children are only executed if condition is true
  - When `true`: Children are executed but results may not be saved

## Usage Examples

### Example 1: Variable-Based Condition

```
create_jmeter_element with:
- elementType: "ifcontroller"
- elementName: "如果Token存在则执行"
- properties:
  - IfController.condition: "${token} != '' && ${token} != '\${token}'"
```

### Example 2: Numeric Comparison with JEXL3

```
create_jmeter_element with:
- elementType: "ifcontroller"
- elementName: "如果响应时间小于500ms"
- properties:
  - IfController.condition: "${__jexl3(${response_time} < 500)}"
```

### Example 3: Check Last Sample Success

```
create_jmeter_element with:
- elementType: "ifcontroller"
- elementName: "如果上一个请求成功"
- properties:
  - IfController.condition: "${JMeterThread.last_sample_ok}"
```

### Example 4: Expression Mode (Simple Check)

```
create_jmeter_element with:
- elementType: "ifcontroller"
- elementName: "表达式模式-检查变量"
- properties:
  - IfController.condition: "true"
  - IfController.useExpression: "true"
```

## Condition Evaluation Modes

### Expression Mode (useExpression=true)
Evaluates condition as a simple expression or variable check:
```
${variable}                 // Check if variable is not empty
${variable} == 'value'       // Check equality
${variable} != ''           // Check not empty
true                        // Always true
false                       // Always false
```

### JavaScript Mode (useExpression=false)
Evaluates condition as JavaScript code:
```
${counter} < 10
${user_count} > 5 && ${user_count} < 20
prev.isSuccessful() == true
```

**Note**: JavaScript mode is **deprecated** and has performance implications. Use expression mode with JEXL3 functions instead.

## Special Values

The condition can also use special values:
- **`LAST`** or **blank**: Evaluates based on the success of the last sample
- **`${JMeterThread.last_sample_ok}`**: Variable that contains the last sample result

## Common Patterns

### Check Variable Exists and Not Empty
```
${__jexl3(vars.get("token") != null && vars.get("token") != "")}
```

### Check Response Code
```
${__jexl3(prev.getResponseCode().equals("200"))}
```

### Check Response Contains Text
```
${__jexl3(prev.getResponseDataAsString().contains("success"))}
```

### Check Last Sample Failed
```
${__jexl3(!prev.isSuccessful())}
```

### Numeric Comparison
```
${__jexl3(${counter} < 10)}
${__jexl3(${count} >= 5)}
```

## Best Practices

1. **Use expression mode**: Keep `useExpression: true` (default)
2. **Use JEXL3 functions**: More powerful and safer than JavaScript
3. **Handle empty variables**: Always check for null and empty string
4. **Use descriptive names**: `如果Token有效则执行`, `检查状态码200`
5. **Avoid JavaScript**: JavaScript mode has performance penalty

## Source Code Behavior

From the source code, the controller evaluates the condition:
1. If `evaluateAll` is `true` or it's the first iteration, evaluate condition
2. If `useExpression` is `true`: Use simple expression evaluation
3. If `useExpression` is `false`: Use JavaScript engine (Nashorn or Rhino)
4. Children execute only if condition evaluates to `true`

## Notes

- The condition is evaluated for each iteration
- Variable replacement happens before condition evaluation
- In expression mode, use JEXL3 for complex conditions
- JavaScript mode has a performance warning in the GUI
- Consider using JSR223 Script for complex conditional logic
