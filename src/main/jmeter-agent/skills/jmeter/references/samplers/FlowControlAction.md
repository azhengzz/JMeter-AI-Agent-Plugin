# Flow Control Action

## Description

Flow Control Action allows you to control the execution flow of your test by pausing, stopping threads, or managing loop iterations. It's commonly used in conditional scenarios or to add think time.

## Parameters

| Property | Required | Description | Example |
|----------|----------|-------------|---------|
| `ActionProcessor.action` | Yes | Action to perform | `1` (Pause) |
| `ActionProcessor.target` | No | Target for stop actions | `0` (Thread) or `2` (Test) |
| `ActionProcessor.duration` | No | Pause duration in milliseconds | `5000` |

## Action Values

| Value | Name | Description |
|-------|------|-------------|
| `0` | Stop | Gracefully stop thread or test |
| `1` | Pause | Pause for specified duration (milliseconds) |
| `2` | Stop Now | Immediately stop thread or test |
| `3` | Start Next Thread Loop | Start next iteration of thread loop |
| `4` | Go To Next Iteration Of Current Loop | Start next iteration of current loop |
| `5` | Break Current Loop | Exit current loop |

## Target Values

| Value | Name | Description |
|-------|------|-------------|
| `0` | Thread | Affect only current thread |
| `2` | Test | Affect all threads in test |

## Usage Examples

### Example 1: Pause (Think Time)

```
create_jmeter_element with:
- elementType: "flowcontrolaction"
- elementName: "暂停5秒"
- properties:
  - ActionProcessor.action: 1
  - ActionProcessor.duration: "5000"
```

### Example 2: Stop Thread on Error

```
create_jmeter_element with:
- elementType: "flowcontrolaction"
- elementName: "错误时停止线程"
- properties:
  - ActionProcessor.action: 0
  - ActionProcessor.target: 0
// Use in If Controller when error condition detected
```

### Example 3: Stop Entire Test

```
create_jmeter_element with:
- elementType: "flowcontrolaction"
- elementName: "停止整个测试"
- properties:
  - ActionProcessor.action: 2
  - ActionProcessor.target: 2
```

### Example 4: Stop Immediately on Critical Error

```
create_jmeter_element with:
- elementType: "flowcontrolaction"
- elementName: "紧急停止"
- properties:
  - ActionProcessor.action: 2
  - ActionProcessor.target: 2
// Use when critical failure detected
```

### Example 5: Restart Next Loop

```
create_jmeter_element with:
- elementType: "flowcontrolaction"
- elementName: "跳过本次迭代，进入下一次"
- properties:
  - ActionProcessor.action: 3
// Skips rest of current thread group iteration
```

### Example 6: Break Current Loop

```
create_jmeter_element with:
- elementType: "flowcontrolaction"
- elementName: "退出当前循环"
- properties:
  - ActionProcessor.action: 5
// Exits the containing Loop Controller
```

### Example 7: Conditional Pause with JSR223

```
// First, determine pause time with JSR223:
create_jmeter_element with:
- elementType: "jsr223postprocessor"
- elementName: "计算暂停时间"
- properties:
  - scriptLanguage: "groovy"
  - cacheKey: "true"
  - script: |
    // Random think time between 2-5 seconds
    int pauseMs = 2000 + (int)(Math.random() * 3000)
    vars.put("think_time", String.valueOf(pauseMs))

// Then use Flow Control Action:
create_jmeter_element with:
- elementType: "flowcontrolaction"
- elementName: "动态暂停"
- properties:
  - ActionProcessor.action: 1
  - ActionProcessor.duration: "${think_time}"
```

### Example 8: Stop on Specific Condition

```
create_jmeter_element with:
- elementType: "ifcontroller"
- elementName: "检查错误状态"
- properties:
  - condition: "${error_count} > 10"

// Child: Flow Control Action
create_jmeter_element with:
- elementType: "flowcontrolaction"
- elementName: "错误过多停止测试"
- properties:
  - ActionProcessor.action: 2
  - ActionProcessor.target: 2
```

## Common Patterns

### Think Time Pattern

Add random pauses between requests to simulate real user behavior:

```
// Pause for random duration (2-8 seconds)
ActionProcessor.action = 1
ActionProcessor.duration = String.valueOf(2000 + (long)(Math.random() * 6000))
```

### Exit Test Pattern

Stop test immediately when critical error occurs:

```
// In If Controller: ${failure} == "true"
ActionProcessor.action = 2  // Stop Now
ActionProcessor.target = 2  // All Threads
```

### Skip Iteration Pattern

Skip to next loop iteration when data is invalid:

```
// In If Controller: ${user_id} == ""
ActionProcessor.action = 3  // Restart Next Loop
```

### Loop Exit Pattern

Exit loop when condition is met:

```
// In If Controller: ${found} == "true"
ActionProcessor.action = 5  // Break Current Loop
```

## Best Practices

1. **Use Pause for think time**: Simulate real user behavior
2. **Stop Now for critical errors**: Immediate termination needed
3. **Stop for graceful shutdown**: Allow threads to finish
4. **Combine with If Controller**: Conditional flow control
5. **Use meaningful names**: Describe the flow control purpose

## Action Comparison

| Action | Use Case | Effect |
|--------|----------|--------|
| Pause | Think time between requests | Delays execution |
| Stop | Normal test completion | Stops thread/test cleanly |
| Stop Now | Critical error/failure | Immediate termination |
| Restart Next Loop | Skip invalid iteration | Starts new thread iteration |
| Start Next Iteration of Current Loop | Continue to next loop iteration | Skips to next loop cycle |
| Break Current Loop | Exit loop when done | Exits containing loop |

## Target Selection

| Target | When to Use | Effect |
|--------|-------------|--------|
| Thread (0) | Single thread issue | Only affects current thread |
| Test (2) | Global test failure | Affects all threads |

## Tips

1. **Dynamic duration**: Use variables for pause time
2. **Conditional use**: Combine with If Controller
3. **Think time**: Add realistic pauses between requests
4. **Error handling**: Stop on critical failures
5. **Loop control**: Use Restart/Break for complex logic

## Notes

- Produces no sample result (returns null)
- Actions take effect immediately
- Duration is in milliseconds for Pause action
- Stop is graceful (allows cleanup)
- Stop Now is immediate (no cleanup)
- Loop actions work with Loop Controller and While Controller
