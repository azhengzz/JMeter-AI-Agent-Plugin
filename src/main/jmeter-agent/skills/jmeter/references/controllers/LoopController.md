# Loop Controller

## Description

If you add Generative or Logic Controllers to a Loop Controller, JMeter will loop through them a certain number of times, in addition to the loop value you specified for the Thread Group. For example, if you add one HTTP Request to a Loop Controller with a loop count of two, and configure the Thread Group loop count to three, JMeter will send a total of 2 * 3 = 6 HTTP Requests.

JMeter will expose the looping index as a variable named `__jm__<Name of your element>__idx`. So for example, if your Loop Controller is named LC, then you can access the looping index through `${__jm__LC__idx}`. Index starts at 0.

## Parameters

| Property | Required | Default | Description | Example |
|----------|----------|---------|-------------|---------|
| `LoopController.loops` | Yes | `1` | The number of times the subelements of this controller will be iterated each time through a test run. The value -1 is equivalent to checking the Forever toggle. | `10` |
| `LoopController.continue_forever` | No | `false` | Loop infinitely | `false` |

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

### Example 3: Loop with Variable

```
create_jmeter_element with:
- elementType: "loopcontroller"
- elementName: "动态循环次数"
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

## Best Practices

1. **Meaningful names**: Use descriptive names like `循环10次`, `重试3次`
2. **Avoid infinite loops without limits**: Use Thread Group scheduler duration for safety when using `-1`
3. **Variable support**: Use `${variable}` for dynamic loop counts
4. **Nesting support**: Loop Controllers can be nested inside other controllers; total iterations multiply across nesting levels
5. **Be careful with functions**: When using a function in the loops field, be aware it may be evaluated multiple times. Using `__Random` will evaluate to a different value for each child sampler and result in unwanted behavior

## Notes

- The value `-1` is equivalent to checking the "Forever" toggle
- Special Case: The Loop Controller embedded in the Thread Group element behaves slightly differently. Unless set to forever, it stops the test after the given number of iterations have been done
- JMeter exposes the looping index as `${__jm__<Name of your element>__idx}` starting at 0
- When using functions in the loops field, be aware they may be evaluated multiple times during the loop
