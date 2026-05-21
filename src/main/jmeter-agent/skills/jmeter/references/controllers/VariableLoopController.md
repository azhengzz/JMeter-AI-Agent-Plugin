# Variable Loop Controller

## Description

An enhanced Loop Controller that supports a configurable loop counter variable. In addition to the standard loop count control, it exposes the current iteration number as a JMeter variable, which can be referenced by child elements. This is useful for parameterized testing where each iteration needs to know its index.

## Parameters

| Parameter | Type | Required | Default | Description |
|-----------|------|----------|---------|-------------|
| LoopController.loops | Integer | Yes | 1 | Number of loop iterations. Use -1 or empty for infinite loops |
| LoopController.loop_counter_name | String | No | - | Variable name for the loop counter. The counter value is accessible in child elements |
| LoopController.loop_counter_start_num | String | No | 1 | Starting number for the loop counter. The counter value equals `loopCount + startNum` |

## Usage Examples

### Example 1: Loop with Counter Variable

```
create_jmeter_element with:
- elementType: "variableloopcontroller"
- elementName: "Loop with Counter"
- properties:
    LoopController.loops: 10
    LoopController.loop_counter_name: "loop_idx"
    LoopController.loop_counter_start_num: "1"

// Child samplers can reference ${loop_idx} (1, 2, 3, ... 10)
create_jmeter_element with:
  elementType: "httpsampler"
  elementName: "Request ${loop_idx}"
```

### Example 2: Infinite Loop with Counter

```
create_jmeter_element with:
- elementType: "variableloopcontroller"
- elementName: "Infinite Loop with Index"
- properties:
    LoopController.loops: -1
    LoopController.loop_counter_name: "iteration"
    LoopController.loop_counter_start_num: "0"
```

### Example 3: Parameterized Data Access

```
create_jmeter_element with:
- elementType: "variableloopcontroller"
- elementName: "Read Row Data"
- properties:
    LoopController.loops: 5
    LoopController.loop_counter_name: "row_num"
    LoopController.loop_counter_start_num: "1"

// Use ${row_num} to access CSV data or construct dynamic variable names
// e.g., ${itemId_${row_num}}
```

## Best Practices

1. **Use meaningful variable names**: Name the counter variable descriptively, e.g., `row_idx`, `page_num`
2. **Start number consistency**: Set `LoopController.loop_counter_start_num` to `1` for 1-based indexing or `0` for 0-based
3. **Avoid variable name conflicts**: Ensure the counter variable name does not collide with other JMeter variables
4. **Scope awareness**: The counter variable is only valid within the loop controller scope; it is removed after the loop ends
5. **Prefer over __counter() function**: This controller is more reliable than `${__counter()}` for nested loops since each controller has its own named counter

## Notes

- The counter variable is set at the start of each iteration and removed after the loop completes
- If `LoopController.loop_counter_name` is empty, no counter variable is created (behaves like a standard Loop Controller)
- This is a Gitee QA extension component and requires the corresponding plugin to be installed
- The counter value equals `currentIterationIndex + LoopController.loop_counter_start_num`
