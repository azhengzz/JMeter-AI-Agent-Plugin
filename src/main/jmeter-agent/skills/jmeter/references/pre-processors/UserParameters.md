# User Parameters

## Description

User Parameters allows you to define specific values for different users. Each user (thread) can have different parameter values, enabling per-user customization of test data.

## Parameters

| Property | Required | Description | Example |
|----------|----------|-------------|---------|
| `UserParameters.names` | Yes | List of parameter names | `["username", "password"]` |
| `UserParameters.thread_values` | Yes | List of user value lists | See examples below |
| `UserParameters.per_iteration` | No | Update each iteration instead of once at thread start | `false` |

## Usage Examples

### Example 1: Per-User Credentials

```
create_jmeter_element with:
- elementType: "userparameters"
- elementName: "用户参数"
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

### Example 3: Per-User Test Data

```
create_jmeter_element with:
- elementType: "userparameters"
- elementName: "测试数据参数"
- properties:
  - UserParameters.names: ["account_id", "product_id", "region"]
  - UserParameters.thread_values: [
      ["ACC001", "PROD100", "US-East"],
      ["ACC002", "PROD200", "EU-West"],
      ["ACC003", "PROD300", "AP-South"]
    ]
```

## How It Works

1. **User Assignment**: Each thread is assigned to a user (1, 2, 3, ...)
2. **Parameter Selection**: Thread uses parameters from its assigned user
3. **Thread 1**: Uses User 1 parameters
4. **Thread 2**: Uses User 2 parameters
5. **Thread N**: Uses parameters modulo N (wraps around)

## Comparison with CSV Data Set

| Feature | User Parameters | CSV Data Set |
|---------|----------------|--------------|
| Data source | UI configuration | External file |
| Ease of update | Harder | Easier (edit file) |
| Large datasets | Not ideal | Ideal |
| Per-user config | Designed for this | Can do but less direct |
| Thread assignment | Direct | Sequential file reading |

## Best Practices

1. **Small datasets**: Best for limited user configurations
2. **Few users**: Use when you have 5-10 user configurations
3. **Fixed config**: When user parameters don't change often
4. **Clear naming**: Use descriptive parameter names
5. **Documentation**: Comment what each user represents

## When to Use

### Use User Parameters When:
- You have a small set of user configurations
- Each user needs different fixed values
- You want per-user customization
- Test data is simple and stable

### Use CSV Data Set When:
- You have large datasets
- Data changes frequently
- You need data-driven testing
- You want easy data file maintenance

## Tips

1. **Test with fewer threads**: Verify with thread count <= user count
2. **Add debug sampler**: Verify parameters are set correctly
3. **Unique per thread**: Each thread gets different values
4. **Order matters**: User 1 goes to Thread 1, User 2 to Thread 2, etc.

## Notes

- Parameters are set when the thread starts (unless per_iteration is true)
- Each thread uses its assigned user's parameters
- If threads > users, assignment wraps around
- More users than threads means some users won't be used
- Variables can be referenced like `${variable_name}`
