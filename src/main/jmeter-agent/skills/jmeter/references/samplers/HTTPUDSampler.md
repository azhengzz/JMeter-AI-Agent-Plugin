# HTTP User Defined Sampler

## Description

This sampler sends HTTP requests using a reusable configuration template defined by an **HTTP User Defined Element Configuration**. Instead of configuring each HTTP request individually, you define the request template once in a config element and reference it by a unique variable name.

The sampler supports **parameter substitution** using the `@{parameter_name}` syntax. Parameters defined in the config element can be overridden at the sampler level, enabling data-driven testing with minimal duplication.

**Key workflow:**
1. Define an HTTP User Defined Element Configuration with a unique identifier name and request template
2. Declare custom parameters with default values in the config element
3. Reference the config element from the sampler using the variable name
4. Override parameter values as needed for each specific request

## Parameters

| Property | Required | Default | Description | Example |
|----------|----------|---------|-------------|---------|
| `HTTPUDSampler.variable_name` | Yes | — | Variable name referencing an HTTP User Defined Element Configuration. Must match the identifier declared in the config element. | `"user_api"` |
| `HTTPUDSampler.variable_name_desc` | No | — | Description for the variable name reference (informational) | `"User management API"` |
| `HTTPUDArgumentsGui.HTTPUDArguments` | No | — | User-defined parameters to override defaults from the config element. See Custom Parameters below. | See examples |

### Custom Parameters (`HTTPUDArgumentsGui.HTTPUDArguments`)

Each parameter item supports:

| Property | Required | Default | Description | Example |
|----------|----------|---------|-------------|---------|
| `Argument.name` | Yes | — | Parameter name (must match a parameter declared in the referenced HTTPUDConfigElement) | `"userId"` |
| `Argument.value` | Yes | — | Parameter value to use for this request | `"${user_id}"` |
| `Argument.desc` | Yes | — | Parameter description (read-only, inherited from the referenced HTTP User Defined Element Configuration) | `"User ID"` |
| `HTTPUDArgument.required` | Yes | — | Whether the parameter is required (read-only, inherited from the referenced HTTP User Defined Element Configuration) | `"true"` |

## Usage Examples

### Example 1: Basic Sampler with Parameter Override

```
create_jmeter_element with:
- elementType: "httpudsampler"
- elementName: "GET_查询用户信息"
- properties:
  - HTTPUDSampler.variable_name: "user_api"
  - HTTPUDArgumentsGui.HTTPUDArguments:
    - {"Argument.name": "userId", "Argument.value": "${user_id}"}
```

### Example 2: Sampler with Multiple Parameters

```
create_jmeter_element with:
- elementType: "httpudsampler"
- elementName: "POST_创建订单"
- properties:
  - HTTPUDSampler.variable_name: "order_api"
  - HTTPUDArgumentsGui.HTTPUDArguments:
    - {"Argument.name": "productId", "Argument.value": "${product_id}"}
    - {"Argument.name": "quantity", "Argument.value": "1"}
    - {"Argument.name": "address", "Argument.value": "${shipping_address}"}
```

### Example 3: Sampler Using Defaults (No Override)

```
create_jmeter_element with:
- elementType: "httpudsampler"
- elementName: "GET_健康检查"
- properties:
  - HTTPUDSampler.variable_name: "health_api"
```

## Best Practices

1. **Always reference an existing config element**: The `HTTPUDSampler.variable_name` must match an HTTP User Defined Element Configuration that exists in the test plan
2. **Override required parameters**: For parameters marked as required in the config element, always provide a value in the sampler
3. **Use meaningful variable names**: Name config elements descriptively, e.g. `user_api`, `order_api`, `auth_api`
4. **Combine with data sources**: Use CSV Data Set Config to feed parameter values into samplers
5. **Add assertions**: Add Response Assertion or JSON Path Assertion after each sampler to validate responses

## Notes

- Parameter substitution uses `@{parameter_name}` syntax in the config element's URL, path, body, etc.
- If a required parameter is not provided in the sampler and has no default value, the test will fail
- The sampler preview is read-only and shows the resolved request based on the referenced config element
- Headers and advanced tab properties in the config element do NOT support parameter substitution
