# DoWhile Controller
> **Source**: Gitee QA extension (third-party plugin `com.gitee.qa.jmeter`, requires the corresponding plugin)

## Description

The DoWhile Controller executes its child elements at least once, then repeats while the specified condition is true. Unlike the While Controller which evaluates the condition before the first iteration, the DoWhile Controller guarantees at least one execution of its children regardless of the condition.

## Parameters

| Parameter | Type | Required | Default | Description |
|-----------|------|----------|---------|-------------|
| DoWhileController.condition | String | No | - | Condition expression. Blank or `LAST` checks if the last sampler was OK. Otherwise, loop continues while the expression is not `false` |

## Condition Syntax

- **Blank** — Checks the last sampler status at the end of each loop iteration
- **`LAST`** — Checks if the last sampler succeeded; loop ends when it fails
- **Expression** — Any expression that evaluates to a non-`false` string continues the loop; `false` stops it

Supports JMeter functions and variables, e.g. `${__javaScript(${count} < 10,)}`, `${__groovy(vars.get("flag") != "stop",)}`

## Usage Examples

### Example 1: Repeat Until Last Sampler Fails

```
create_jmeter_element with:
- elementType: "dowhilecontroller"
- elementName: "DoWhile - Retry Until Success"

// Child samplers execute at least once, then repeat while last sampler is OK
create_jmeter_element with:
  elementType: "httpsampler"
  elementName: "Poll Status"
```

### Example 2: Loop with Counter Condition

```
create_jmeter_element with:
- elementType: "dowhilecontroller"
- elementName: "DoWhile - Paginated Requests"
- properties:
    DoWhileController.condition: "${__javaScript(${page} < ${totalPages},)}"

// Add a sampler and update page variable in each iteration
```

### Example 3: Infinite Loop with Break Logic

```
create_jmeter_element with:
- elementType: "dowhilecontroller"
- elementName: "DoWhile - Process Queue"
- properties:
    DoWhileController.condition: "${__groovy(vars.get("hasMore") != "false",)}"

// Use Flow Control Action or JSR223 element to break when needed
```

## Best Practices

1. **Avoid infinite loops**: Always ensure the condition can eventually evaluate to `false`
2. **Use Groovy over JavaScript**: `${__groovy(...)}` performs better than `${__javaScript(...)}`
3. **Add safety counter**: Include a JSR223 element to break after a maximum number of iterations
4. **Set think time**: Add a timer inside the loop to avoid overwhelming the server
5. **Monitor condition variables**: Debug Sampler can help trace why a loop does not terminate

## Notes

- Child elements execute at least once, even if the condition is initially `false`
- Condition evaluation errors may cause infinite loops; validate expressions carefully
- This is a Gitee QA extension component and requires the corresponding plugin to be installed
- Functionally similar to While Controller, but guarantees at least one execution (do-while vs while)
