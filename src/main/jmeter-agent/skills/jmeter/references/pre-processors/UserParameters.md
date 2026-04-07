# User Parameters

## Description

User Parameters allows you to define specific values for different users. Each user (thread) can have different parameter values, enabling per-user customization of test data.

## Parameters

| Property | Required | Description | Example |
|----------|----------|-------------|---------|
| `UserParameterNames` | No | Comma-separated parameter names | `username,password` |
| `UserParameterValues` | No | Parameter values per user | See examples below |

## Usage Examples

### Example 1: Per-User Credentials

```
create_jmeter_element with:
- elementType: "userparameters"
- elementName: "用户参数"
- properties:
  - UserParameterNames: "username,password"
  - UserParameterValues: |
    User 1: alice,alice123
    User 2: bob,bob456
    User 3: charlie,charlie789

// Use in HTTP Request
create_jmeter_element with:
- elementType: "httpsampler"
- elementName: "POST_登录"
- properties:
  - HTTPSampler.path: "/api/login"
  - HTTPSampler.method: "POST"
  - HTTPsampler.Arguments:
    - "": '{"username":"${username}","password":"${password}"}'
  - HTTPSampler.postBodyRaw: "true"
```

### Example 2: Different User Profiles

```
create_jmeter_element with:
- elementType: "userparameters"
- elementName: "用户配置参数"
- properties:
  - UserParameterNames: "user_type,max_items,timeout"
  - UserParameterValues: |
    User 1: premium,100,5000
    User 2: standard,50,3000
    User 3: basic,25,1000
```

### Example 3: Per-User Test Data

```
create_jmeter_element with:
- elementType: "userparameters"
- elementName: "测试数据参数"
- properties:
  - UserParameterNames: "account_id,product_id,region"
  - UserParameterValues: |
    User 1: ACC001,PROD100,US-East
    User 2: ACC002,PROD200,EU-West
    User 3: ACC003,PROD300,AP-South
```

## How It Works

1. **User Assignment**: Each thread is assigned to a user (1, 2, 3, ...)
2. **Parameter Selection**: Thread uses parameters from its assigned user
3. **Thread 1**: Uses User 1 parameters
4. **Thread 2**: Uses User 2 parameters
5. **Thread N**: Uses parameters modulo N (wraps around)

## Parameter Format

```
parameter_name_1,parameter_name_2,parameter_name_3
User 1: value1_1,value1_2,value1_3
User 2: value2_1,value2_2,value2_3
User 3: value3_1,value3_2,value3_3
```

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

- Parameters are set when the thread starts
- Each thread uses its assigned user's parameters
- If threads > users, assignment wraps around
- More users than threads means some users won't be used
- Variables can be referenced like `${variable_name}`
