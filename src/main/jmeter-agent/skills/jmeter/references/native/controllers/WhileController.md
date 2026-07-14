# While Controller
> **Source**: Apache JMeter built-in component (ships with JMeter, no extra install needed)

## Description

The While Controller runs its children until the condition is "false".

JMeter will expose the looping index as a variable named `__jm__<Name of your element>__idx`. So for example, if your While Controller is named WC, then you can access the looping index through `${__jm__WC__idx}`. Index starts at 0.

Possible condition values:

- **blank** - exit loop when last sample in loop fails
- **`LAST`** - exit loop when last sample in loop fails. If the last sample just before the loop failed, don't enter loop.
- **Otherwise** - exit (or don't enter) the loop when the condition is equal to the string "false"

The condition can be any variable or function that eventually evaluates to the string "false". This allows the use of `__jexl3`, `__groovy` function, properties or variables as needed.

Note that the condition is evaluated twice, once before starting sampling children and once at end of children sampling, so putting non-idempotent functions in Condition (like `__counter`) can introduce issues.

## Parameters

| Property | Required | Default | Description | Example |
|----------|----------|---------|-------------|---------|
| `WhileController.condition` | No | `""` | Condition to evaluate before each iteration. blank = exit when last sample fails; `LAST` = exit when last sample fails (and don't enter if previous failed); Otherwise = exit when condition equals "false". | `${__jexl3(${C}==10)}` |

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

### Example 3: Loop with JEXL3 Condition

```
create_jmeter_element with:
- elementType: "whilecontroller"
- elementName: "循环计数到10"
- properties:
  - WhileController.condition: "${__jexl3(${C} < 10)}"
```

### Example 4: LAST Condition

```
create_jmeter_element with:
- elementType: "whilecontroller"
- elementName: "上一个成功则继续"
- properties:
  - WhileController.condition: "LAST"
```

## Best Practices

1. **Always provide an exit condition**: Ensure the loop will eventually terminate to prevent infinite loops
2. **Use empty condition for retry**: Use blank condition to loop while last sample succeeds and exit on failure
3. **Use JEXL3 or Groovy for complex conditions**: `${__jexl3()}` or `${__groovy()}` functions work well for evaluating expressions
4. **Avoid non-idempotent functions**: Do not use `__counter` or similar functions in the condition since it is evaluated twice per iteration
5. **Combine with counters**: Use a JSR223 element inside the loop to increment a counter variable for controlled iteration

## Notes

- The condition is evaluated twice per iteration: once before starting sampling children and once at the end
- Avoid using non-idempotent functions like `__counter` in the condition
- The condition can be any variable or function that eventually evaluates to the string "false"
- Common condition examples: `${VAR}`, `${__jexl3(${C}==10)}`, `${__jexl3("${VAR2}"=="abcd")}`, `${__P(property)}`
- When using `LAST`, if the last sample just before the loop failed, the loop will not be entered
- JMeter exposes the looping index as `${__jm__<Name of your element>__idx}` starting at 0
