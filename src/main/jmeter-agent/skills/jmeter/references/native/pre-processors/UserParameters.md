# User Parameters
> **Source**: Apache JMeter built-in component (ships with JMeter, no extra install needed)

## Description

Allows the user to specify values for User Variables specific to individual threads. User Variables can also be specified in the Test Plan but not specific to individual threads. This panel allows you to specify a series of values for any User Variable. For each thread, the variable will be assigned one of the values from the series in sequence. If there are more threads than values, the values get re-used. For example, this can be used to assign a distinct user id to be used by each thread. User variables can be referenced in any field of any JMeter Component.

The variable is specified by clicking the `Add Variable` button in the bottom of the panel and filling in the Variable name in the `Name:` column. To add a new value to the series, click the `Add User` button and fill in the desired value in the newly added column.

Values can be accessed in any test component in the same thread group, using the function syntax: `${variable}`.

See also the CSV Data Set Config element, which is more suitable for large numbers of parameters.

## Parameters

| Property | Required | Default | Description | Example |
|----------|----------|---------|-------------|---------|
| `UserParameters.names` | Yes | — | List of parameter names. Each name becomes a variable that can be referenced as `${name}`. | `["username", "password"]` |
| `UserParameters.thread_values` | Yes | — | List of user value lists. Each inner list contains values for one user, in the same order as names. If there are more threads than values, the values get re-used. | `[["alice", "alice123"], ["bob", "bob456"]]` |
| `UserParameters.per_iteration` | No | `false` | A flag to indicate whether the User Parameters element should update its variables only once per iteration. If checked, the values are updated each time through the UP's parent controller. If unchecked, the UP will update the parameters for every sample request made within its scope. | `"false"` |

## Usage Examples

### Example 1: Per-User Credentials

```
create_jmeter_element with:
- elementType: "userparameters"
- elementName: "用户登录参数"
- properties:
  - UserParameters.names: ["username", "password"]
  - UserParameters.thread_values: [
      ["alice", "alice123"],
      ["bob", "bob456"],
      ["charlie", "charlie789"]
    ]
```

### Example 2: Different User Profiles

```
create_jmeter_element with:
- elementType: "userparameters"
- elementName: "用户配置参数"
- properties:
  - UserParameters.names: ["user_type", "max_items", "timeout"]
  - UserParameters.thread_values: [
      ["premium", "100", "5000"],
      ["standard", "50", "3000"],
      ["basic", "25", "1000"]
    ]
```

### Example 3: Per-User Test Data with Per-Iteration Update

```
create_jmeter_element with:
- elementType: "userparameters"
- elementName: "测试数据参数-每次迭代更新"
- properties:
  - UserParameters.names: ["account_id", "product_id", "region"]
  - UserParameters.thread_values: [
      ["ACC001", "PROD100", "US-East"],
      ["ACC002", "PROD200", "EU-West"],
      ["ACC003", "PROD300", "AP-South"]
    ]
  - UserParameters.per_iteration: "true"
```

## Best Practices

1. **Use for small datasets**: Best suited for limited user configurations (5-10 users); use CSV Data Set Config for large datasets
2. **Match thread count to user count**: Each thread is assigned to a user in sequence; if threads > users, values wrap around
3. **Use descriptive parameter names**: Clear names make test plans easier to understand and maintain
4. **Use per_iteration wisely**: Enable `UserParameters.per_iteration` only if you need variables updated on each iteration rather than once at thread start
5. **Consider CSV Data Set Config for large data**: CSV Data Set Config is more suitable when you have many users or frequently changing data

## Notes

- Parameters are set when the thread starts unless `UserParameters.per_iteration` is set to `"true"`
- Each thread uses its assigned user's parameters in sequence (Thread 1 = User 1, Thread 2 = User 2, etc.)
- If threads > users, assignment wraps around (User 1 values are re-used for the next thread)
- More users than threads means some user configurations will not be used
- Variables can be referenced using the function syntax `${variable_name}` in any JMeter component
- The scope rules of the parent controller apply when `per_iteration` is unchecked
- For large numbers of parameters, CSV Data Set Config is the recommended alternative
