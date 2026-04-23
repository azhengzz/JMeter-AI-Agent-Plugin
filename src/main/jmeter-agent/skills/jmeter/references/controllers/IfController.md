# If Controller

## Description

The If Controller allows the user to control whether the test elements below it (its children) are run or not. By default, the condition is evaluated only once on initial entry, but you have the option to have it evaluated for every runnable element contained in the controller.

The best option (default) is to check "Interpret Condition as Variable Expression?", then in the condition field you have 2 options:

- Option 1: Use a variable that contains `true` or `false`. If you want to test if last sample was successful, you can use `${JMeterThread.last_sample_ok}`
- Option 2: Use a function (`${__jexl3()}` is advised) to evaluate an expression that must return `true` or `false`

If you uncheck "Interpret Condition as Variable Expression?", If Controller will internally use JavaScript to evaluate the condition, which has a performance penalty that can be very big and make your test less scalable.

## Parameters

| Property | Required | Default | Description | Example |
|----------|----------|---------|-------------|---------|
| `IfController.condition` | Yes | — | Condition expression to evaluate. By default the condition is interpreted as JavaScript code that returns "true" or "false", but this can be overridden with the useExpression parameter. | `${JMeterThread.last_sample_ok}` |
| `IfController.useExpression` | No | `true` | If true, the condition must be an expression that evaluates to "true" (case is ignored). Checking this and using `__jexl3` or `__groovy` function in Condition is advised for performance. | `true` |
| `IfController.evaluateAll` | No | `false` | Should condition be evaluated for all children? If not checked, then the condition is only evaluated on entry. | `false` |

## Usage Examples

### Example 1: Variable-Based Condition

```
create_jmeter_element with:
- elementType: "ifcontroller"
- elementName: "如果Token存在则执行"
- properties:
  - IfController.condition: "${token} != '' && ${token} != '\${token}'"
```

### Example 2: JEXL3 Expression

```
create_jmeter_element with:
- elementType: "ifcontroller"
- elementName: "如果响应时间小于500ms"
- properties:
  - IfController.condition: "${__jexl3(${response_time} < 500)}"
  - IfController.useExpression: "true"
```

### Example 3: Check Last Sample Success

```
create_jmeter_element with:
- elementType: "ifcontroller"
- elementName: "如果上一个请求成功"
- properties:
  - IfController.condition: "${JMeterThread.last_sample_ok}"
  - IfController.useExpression: "true"
```

### Example 4: Evaluate for All Children

```
create_jmeter_element with:
- elementType: "ifcontroller"
- elementName: "每个子元素检查条件"
- properties:
  - IfController.condition: "${continue_flag}"
  - IfController.useExpression: "true"
  - IfController.evaluateAll: "true"
```

## Best Practices

1. **Use expression mode**: Keep `IfController.useExpression` as `true` (default) for best performance
2. **Use JEXL3 or Groovy functions**: `${__jexl3()}` or `${__groovy()}` are advised instead of JavaScript
3. **Handle empty variables**: To test if a variable is undefined, use `"${myVar}" == "\${myVar}"`; to test if defined and not null, use `"${myVar}" != "\${myVar}"`
4. **Avoid JavaScript mode**: Unchecking useExpression has a significant performance penalty
5. **Use descriptive names**: Use names like `如果Token有效则执行`, `检查状态码200`

## Notes

- When using `__groovy`, avoid variable replacement in the string to allow script caching; use `vars.get("myVar")` instead
- The condition is evaluated on entry by default; set `evaluateAll` to `true` to evaluate for every child element
- If there is an error interpreting JavaScript code, the condition is assumed to be `false`, and a message is logged in `jmeter.log`
- Parent mode controllers do not currently properly support nested transaction controllers of either type
