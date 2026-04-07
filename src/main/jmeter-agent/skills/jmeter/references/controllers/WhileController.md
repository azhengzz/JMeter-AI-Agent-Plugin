# While Controller

## Description

While Controller executes its child elements repeatedly while a condition is true. It's useful for looping until a specific condition is met or a maximum count is reached.

## Source Code

Based on Apache JMeter source: `org.apache.jmeter.control.WhileController`

## Parameters

| Property | Required | Default | Description | Example |
|----------|----------|---------|-------------|---------|
| `WhileController.condition` | No | `""` (empty string) | Condition to evaluate before each iteration | `${counter} < 10` |

## Parameter Details

### WhileController.condition
- **Required**: No (but functionally required for proper operation)
- **Default**: Empty string `""`
- **Description**: The condition expression that determines if looping should continue

## Condition Evaluation

The condition is evaluated in two modes:

### 1. Empty or LAST Condition
When the condition is empty (`""`) or `"LAST"`:
```
condition = ""       → Checks if last sampler was OK
condition = "LAST"   → Checks if last sampler was OK
```

The controller uses: `JMeterThread.LAST_SAMPLE_OK` variable

### 2. Explicit Condition
Any non-empty condition is evaluated:
```
condition = "false"  → Always stops (loop ends immediately)
condition = "true"   → Always loops (infinite loop)
```

**Important**: Unlike IfController, WhileController does **NOT** support JavaScript evaluation. It only checks:
- Empty string → checks last sample
- "LAST" → checks last sample  
- "false" → stops looping
- Any other value → continues looping

## Usage Examples

### Example 1: Loop Until Last Sample Fails

```
create_jmeter_element with:
- elementType: "whilecontroller"
- elementName: "重试直到失败"
- properties:
  - WhileController.condition: ""
```

### Example 2: Loop Based on Variable

```
create_jmeter_element with:
- elementType: "whilecontroller"
- elementName: "循环直到停止标志"
- properties:
  - WhileController.condition: "${continue_loop}"
```

### Example 3: Infinite Loop

```
create_jmeter_element with:
- elementType: "whilecontroller"
- elementName: "永久循环"
- properties:
  - WhileController.condition: "true"
```

### Example 4: Loop with Break Condition

```
// While Controller with condition
create_jmeter_element with:
- elementType: "whilecontroller"
- elementName: "条件循环"
- properties:
  - WhileController.condition: ""

// Inside loop, use IfController to break
create_jmeter_element with:
- elementType: "ifcontroller"
- elementName: "检查停止条件"
- properties:
  - IfController.condition: "${stop_flag} == 'true'"
  - IfController.useExpression: "true"
  
// When condition met, use JSR223 Sampler to break loop
create_jmeter_element with:
- elementType: "jsr223sampler"
- elementName: "停止循环"
- properties:
  - scriptLanguage: "groovy"
  - script: |
    org.apache.jmeter.control.WhileController controller = ctx.getCurrentSampler()
    // Find parent WhileController and break it
```

## Source Code Behavior

From the source code, the condition evaluation logic:

```java
private boolean endOfLoop(boolean loopEnd) {
    String cnd = getCondition().trim();
    
    // If blank, only check previous sample when at end of loop
    if ((loopEnd && cnd.isEmpty()) || "LAST".equalsIgnoreCase(cnd)) {
        JMeterVariables threadVars = JMeterContextService.getContext().getVariables();
        res = "false".equalsIgnoreCase(threadVars.get(JMeterThread.LAST_SAMPLE_OK));
    } else {
        // Any non-empty, non-LAST condition continues looping
        res = "false".equalsIgnoreCase(cnd);
    }
    
    return res; // true means end of loop (stop)
}
```

This means:
- `condition = "false"` → Stops looping
- `condition = "true"` or any other value → Continues looping
- `condition = ""` or `"LAST"` → Depends on last sample success

## Limitations

**Important**: WhileController does NOT support:
- JavaScript expressions
- JEXL3 functions  
- Numeric comparisons
- Variable comparisons

For complex conditions, use **LoopController** with **IfController** combination, or use **JSR223 Sampler** with break logic.

## Workarounds for Complex Conditions

### Pattern 1: WhileController + IfController
```
WhileController:
  condition: ""  (loops based on last sample)
  
  → IfController: ${counter} < 10
    → Your samplers
```

### Pattern 2: Use LoopController with JSR223
```
LoopController:
  loops: 100
  
  → JSR223 Sampler: Check condition and break if needed
    → Your samplers
```

### Pattern 3: Variable-Based Loop
```
// Set initial variable
vars.put("continue_loop", "true")

WhileController:
  condition: ${continue_loop}

  → Your samplers
  
  → JSR223 PostProcessor: Check and update condition
    → script: if (${count} >= 10) { vars.put("continue_loop", "false") }
```

## Best Practices

1. **Use empty condition**: For retry-until-success scenarios
2. **Add exit strategy**: Always have a way to break the loop
3. **Use JSR223 for complex logic**: Combine with JSR223 for break conditions
4. **Monitor loop count**: Add counter to prevent infinite loops
5. **Test with small counts**: Verify loop logic before large tests

## Common Patterns

### Retry Until Success
```
condition: ""
→ Loops while last sampler was successful
→ Stops on first failure
```

### Retry Until Failure
```
condition: "${JMeterThread.last_sample_ok}"
→ Loops while last sampler was OK
→ Stops on first failure
```

### Controlled Loop
```
condition: "${continue_loop}"

// Inside loop, use JSR223 to set continue_loop to "false" when done
```

## Notes

- Condition evaluation is simpler than IfController
- Does NOT support JavaScript or JEXL3 evaluation
- Use empty condition for retry scenarios
- Use JSR223 for complex break conditions
- Can be nested inside other controllers
- Each iteration resets the controller's state
- Consider using LoopController + IfController for more control
