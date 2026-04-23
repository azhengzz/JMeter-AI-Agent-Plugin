# User Defined Variables

## Description

The User Defined Variables element lets you define an initial set of variables, just as in the Test Plan.

Note that all the UDV elements in a test plan - no matter where they are - are processed at the start. So you cannot reference variables which are defined as part of a test run, e.g. in a Post-Processor.

UDVs should not be used with functions that generate different results each time they are called. Only the result of the first function call will be saved in the variable. However, UDVs can be used with functions such as `__P()`, for example: `HOST ${__P(host,localhost)}` which would define the variable "HOST" to have the value of the JMeter property "host", defaulting to "localhost" if not defined.

For defining variables during a test run, see User Parameters. UDVs are processed in the order they appear in the Plan, from top to bottom. For simplicity, it is suggested that UDVs are placed only at the start of a Thread Group (or perhaps under the Test Plan itself).

Once the Test Plan and all UDVs have been processed, the resulting set of variables is copied to each thread to provide the initial set of variables. If a runtime element such as a User Parameters Pre-Processor or Regular Expression Extractor defines a variable with the same name as one of the UDV variables, then this will replace the initial value, and all other test elements in the thread will see the updated value.

If you have more than one Thread Group, make sure you use different names for different values, as UDVs are shared between Thread Groups. Also, the variables are not available for use until after the element has been processed, so you cannot reference variables that are defined in the same element. You can reference variables defined in earlier UDVs or on the Test Plan.

## Parameters

| Property | Required | Default | Description | Example |
|----------|----------|---------|-------------|---------|
| `Arguments.arguments` | No | — | List of variable name-value pairs. The string under the "Name" column is what you'll need to place inside the brackets in `${...}` constructs to use the variables later on. The whole `${...}` will then be replaced by the string in the "Value" column. | — |
| `Argument.name` | Yes | `""` | Variable name | `"base_url"` |
| `Argument.value` | Yes | `""` | Variable value | `"www.example.com"` |
| `Argument.desc` | Yes | `""` | Description for this variable | `"服务器地址"` |
| `Argument.metadata` | Yes | `"="` | Metadata character (typically '=') | `"="` |

## Usage Examples

### Example 1: Basic Variables

```
create_jmeter_element with:
- elementType: "userdefinedvariables"
- elementName: "全局变量"
- properties:
  - Arguments.arguments:
    - Argument.name: "base_url"
      Argument.value: "www.example.com"
    - Argument.name: "base_port"
      Argument.value: "443"
    - Argument.name: "protocol"
      Argument.value: "https"
    - Argument.name: "timeout"
      Argument.value: "5000"
```

### Example 2: Environment Configuration

```
create_jmeter_element with:
- elementType: "userdefinedvariables"
- elementName: "测试环境配置"
- properties:
  - Arguments.arguments:
    - Argument.name: "env"
      Argument.value: "test"
    - Argument.name: "api_key"
      Argument.value: "test_key_12345"
    - Argument.name: "db_url"
      Argument.value: "jdbc:mysql://test-db:3306/mydb"
    - Argument.name: "max_users"
      Argument.value: "100"
```

### Example 3: With Properties for Command-Line Override

```
create_jmeter_element with:
- elementType: "userdefinedvariables"
- elementName: "可覆盖的服务器配置"
- properties:
  - Arguments.arguments:
    - Argument.name: "base_url"
      Argument.value: "${__P(base_url,www.example.com)}"
    - Argument.name: "api_key"
      Argument.value: "${__P(api_key,default_key)}"
```

Then run JMeter with:
```
jmeter -Jbase_url=prod.example.com -Japi_key=prod_key
```

## Best Practices

1. **Descriptive names**: Use clear, meaningful variable names (e.g., `base_url`, `api_endpoint`)
2. **Group related variables**: Organize by purpose (config, data, etc.)
3. **Use for constants**: Store values that don't change during test
4. **Environment configs**: Separate configs for dev/test/prod using `__P()` function
5. **Placement**: Place at Test Plan level or start of Thread Group for simplicity
6. **Avoid duplicate names**: If you have more than one Thread Group, use different names for different values as UDVs are shared

## Notes

- All UDV elements in a test plan are processed at the start, regardless of where they are placed
- Variables are initialized once at test start and are static (cannot be changed during test)
- Use `${variableName}` syntax to reference variables
- Scope is determined by placement in test plan tree, but values are shared across Thread Groups
- UDVs should not be used with functions that generate different results each time they are called (only the first result is saved)
- For dynamic values during a test run, use CSV Data Set Config or JSR223 elements
- You cannot reference variables defined in the same UDV element; use separate UDV elements or Test Plan variables
