# Loop Controller

## Description

Loop Controller controls the execution count of its child elements. It can loop a specific number of times or loop indefinitely until the test stops.

## Source Code

Based on Apache JMeter source: `org.apache.jmeter.control.LoopController`

## Parameters

| Property | Required | Default | Description | Example |
|----------|----------|---------|-------------|---------|
| `LoopController.loops` | Yes* | `1` | Number of iterations (-1 for infinite) | `10` or `-1` |

## Parameter Details

### LoopController.loops
- **Java Constant**: `LoopController.LOOPS = "LoopController.loops"` 
- **Required**: Functionally required (but has default value of 1)
- **Default**: `1`
- **Description**: Number of times to execute the child elements
- **Special Value**: `-1` (or `LoopController.INFINITE_LOOP_COUNT`) for infinite loop

## Loop Behavior

### Fixed Count Loop
When `loops` is a positive number:
```
loops = 5  → Executes children 5 times
loops = 1  → Executes children 1 time
loops = 10 → Executes children 10 times
```

### Infinite Loop
Set `loops: -1` for infinite looping.

Infinite looping continues until:
- Test is manually stopped
- Parent element's duration ends (e.g., Thread Group scheduler)
- Loop is broken by `breakLoop()` call

## Usage Examples

### Example 1: Loop 10 Times

```
create_jmeter_element with:
- elementType: "loopcontroller"
- elementName: "循环10次"
- properties:
  - LoopController.loops: "10"
```

### Example 2: Infinite Loop

```
create_jmeter_element with:
- elementType: "loopcontroller"
- elementName: "永久循环"
- properties:
  - LoopController.loops: "-1"
```

### Example 3: Loop With Variable

```
create_jmeter_element with:
- elementType: "loopcontroller"
- elementName: "循环${loop_count}次"
- properties:
  - LoopController.loops: "${loop_count}"
```

### Example 4: Single Execution (Default)

```
create_jmeter_element with:
- elementType: "loopcontroller"
- elementName: "执行一次"
- properties:
  - LoopController.loops: "1"
```

## Source Code Constants

```java
// From LoopController.java
public static final int INFINITE_LOOP_COUNT = -1;
public static final String LOOPS = "LoopController.loops";
```

## Best Practices

1. **Meaningful names**: Use descriptive names like `循环10次`, `重试3次`
2. **Avoid infinite loops without limits**: Use scheduler duration for safety
3. **Variable support**: Use `${variable}` for dynamic loop counts
4. **Nesting support**: Can nest multiple loop controllers
5. **Thread Group context**: When used in Thread Group, controls all samplers


## Common Patterns

### Fixed Number of Iterations
```
loops: 5
→ Executes 5 times then moves to next element
```

### Infinite Loop with Scheduler
```
loops: -1
→ Loops indefinitely
```

When combined with Thread Group scheduler:
```
ThreadGroup.scheduler: true
ThreadGroup.duration: 300
→ Loops indefinitely for 5 minutes (300 seconds)
```

### Dynamic Loop Count
```
loops: "${user_count}"
→ Loops based on variable value
```

### Conditional Loop (Combine with IfController)
```
Loop Controller:
  loops: 100
  → IfController inside can break execution based on condition
```

## Notes

- The `loops` parameter is accessed as `LoopController.loops` in JMeter properties
- Infinite loop is represented by `-1` (constant `INFINITE_LOOP_COUNT`)
- Each iteration resets the controller's state
- Can be nested inside other controllers
- When used in Thread Group, `ThreadGroup.main_controller` references this controller
