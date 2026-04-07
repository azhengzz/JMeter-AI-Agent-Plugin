# User Defined Variables

## Description

User Defined Variables allows you to define static variables that can be referenced throughout the test plan. These variables are initialized when the test starts and can be used for configuration values, test data, or constants.

## Parameters

| Property | Required | Description | Example |
|----------|----------|-------------|---------|
| `Arguments.arguments` | No | List of variable name-value pairs | See examples below |

## Usage Examples

### Example 1: Basic Variables

```
create_jmeter_element with:
- elementType: "userdefinedvariables"
- elementName: "全局变量"
- properties:
  - Arguments.arguments: |
    base_url: www.example.com
    base_port: 443
    protocol: https
    timeout: 5000

// Use in HTTP Request
create_jmeter_element with:
- elementType: "httpsampler"
- elementName: "GET_用户信息"
- properties:
  - HTTPSampler.domain: "${base_url}"
  - HTTPSampler.port: "${base_port}"
  - HTTPSampler.protocol: "${protocol}"
```

### Example 2: Environment Configuration

```
create_jmeter_element with:
- elementType: "userdefinedvariables"
- elementName: "测试环境配置"
- properties:
  - Arguments.arguments: |
    env: test
    api_key: test_key_12345
    db_url: jdbc:mysql://test-db:3306/mydb
    max_users: 100
```

### Example 3: Test Data Constants

```
create_jmeter_element with:
- elementType: "userdefinedvariables"
- elementName: "测试数据常量"
- properties:
  - Arguments.arguments: |
    default_username: testuser
    default_password: Test@123
    test_email: test@example.com
    test_phone: +1234567890
```

## Variable Naming

### Good Names
```
base_url
api_endpoint
max_timeout
user_count
```

### Avoid
```
url (too generic)
a (not descriptive)
test123 (unclear purpose)
```

## Scope and Order

### Initialization Order
User Defined Variables are initialized in the following order:
1. Test Plan variables
2. Thread Group variables
3. Overlapping values: Child overrides parent

### Scope
- Test Plan level: Available to all thread groups
- Thread Group level: Available to samplers in that thread group

## Best Practices

1. **Descriptive names**: Use clear, meaningful variable names
2. **Group related variables**: Organize by purpose (config, data, etc.)
3. **Use for constants**: Store values that don't change during test
4. **Environment configs**: Separate configs for dev/test/prod
5. **Document values**: Add comments in variable names for clarity

## Common Patterns

### Pattern 1: Server Configuration
```
create_jmeter_element with:
- elementType: "userdefinedvariables"
- elementName: "服务器配置"
- properties:
  - Arguments.arguments: |
    server_host: api.example.com
    server_port: 8443
    server_protocol: https
    api_version: v1
    read_timeout: 30000
    connect_timeout: 5000
```

### Pattern 2: User Test Data
```
create_jmeter_element with:
- elementType: "userdefinedvariables"
- elementName: "测试用户数据"
- properties:
  - Arguments.arguments: |
    test_user_1: alice
    test_user_2: bob
    test_user_3: charlie
    default_password: Test@123
```

### Pattern 3: Feature Flags
```
create_jmeter_element with:
- elementType: "userdefinedvariables"
- elementName: "功能开关"
- properties:
  - Arguments.arguments: |
    feature_enabled: true
    use_cache: true
    debug_mode: false
```

## Comparison with Other Elements

| Element | Mutable | Thread-Safe | Scope |
|---------|---------|-------------|-------|
| User Defined Variables | No | Yes | Test Plan/Thread Group |
| CSV Data Set Config | Yes | Yes | Per thread/iteration |
| JSR223 Sampler (vars.put) | Yes | Yes | Per thread |
| Properties | No | Yes | Global |

## Debugging Variables

Use Debug Sampler to view variables:
```
create_jmeter_element with:
- elementType: "debugsampler"
- elementName: "查看所有变量"
- properties:
  - displayJMeterVariables: "true"
```

## Advanced: Using Properties

For global values that can be passed from command line:
```
// In User Defined Variables
base_url: ${__P(base_url,www.example.com)}
api_key: ${__P(api_key,default_key)}
```

Then run JMeter with:
```
jmeter -Jbase_url=prod.example.com -Japi_key=prod_key
```

## Notes

- Variables are initialized once at test start
- Values are static (cannot be changed during test)
- Use `${variableName}` syntax to reference
- Scope is determined by placement in test plan tree
- Good for configuration constants and test parameters
- For dynamic values, use CSV Data Set Config or JSR223 elements
