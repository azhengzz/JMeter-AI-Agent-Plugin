# HTTP User Defined Element Configuration
> **Source**: Gitee QA extension (third-party plugin `com.gitee.qa.jmeter`, requires the corresponding plugin)

## Description

This configuration element defines a reusable HTTP request template with custom parameters. HTTP User Defined Samplers reference this configuration by its unique variable name to inherit all request settings.

The configuration supports **parameter substitution** using the `@{parameter_name}` syntax. You can declare custom parameters with default values, and samplers can override them per request. This enables creating data-driven, reusable API templates.

**Parameter substitution is supported in:**
- Basic tab: Protocol, Domain, Port, Method, Path, Content Encoding, Parameters, Body, File Upload
- **Not supported in:** Advanced tab and Headers tab

## Parameters

| Property | Required | Default | Description | Example |
|----------|----------|---------|-------------|---------|
| `HTTPUDConfigElement.variable_name` | Yes | — | Unique identifier for this configuration. Samplers reference this name. Must be globally unique. | `"user_api"` |
| `HTTPSampler.protocol` | No | — | HTTP protocol. Supports `@{param}` substitution. | `"https"` |
| `HTTPSampler.domain` | No | — | Server domain or IP. Supports `@{param}` substitution. | `"api.example.com"` |
| `HTTPSampler.port` | No | — | Port number (1-65535). Supports `@{param}` substitution. | `"8080"` |
| `HTTPSampler.method` | No | — | HTTP method. Supports `@{param}` substitution. | `"GET"` |
| `HTTPSampler.path` | No | — | Request path. Supports `@{param}` substitution. | `"/api/users/@{userId}"` |
| `HTTPSampler.contentEncoding` | No | — | Content encoding. Supports `@{param}` substitution. | `"utf-8"` |
| `HTTPSampler.connect_timeout` | No | — | Connection timeout in milliseconds | `"5000"` |
| `HTTPSampler.response_timeout` | No | — | Response timeout in milliseconds | `"30000"` |
| `HTTPSampler.follow_redirects` | No | `true` | Follow HTTP redirects | `"true"` |
| `HTTPSampler.auto_redirects` | No | `false` | Auto-follow redirects at protocol level | `"false"` |
| `HTTPSampler.use_keepalive` | No | `true` | Use keep-alive connections | `"true"` |
| `HTTPSampler.DO_MULTIPART_POST` | No | `false` | Use multipart/form-data for POST | `"true"` |
| `HTTPSampler.BROWSER_COMPATIBLE_MULTIPART` | No | `false` | Use browser-compatible multipart mode | `"true"` |
| `HTTPSampler.postBodyRaw` | No | `false` | Send raw body content | `"true"` |
| `HTTPsampler.Arguments` | No | — | Query parameters. Supports `@{param}` substitution. See HTTP Arguments below. | See examples |
| `HTTPsampler.Files` | No | — | File upload parameters. Supports `@{param}` substitution. See File Upload below. | See examples |
| `HTTPUDConfigElement.http_header_parameters_name` | No | — | HTTP headers (does NOT support `@{param}` substitution). See HTTP Headers below. | See examples |
| `HTTPUDArgumentsGui.HTTPUDArguments` | No | — | Custom parameter definitions. See Custom Parameters below. | See examples |

### HTTP Arguments (`HTTPsampler.Arguments`)

When `HTTPSampler.postBodyRaw` is `false`:

| Property | Required | Default | Description | Example |
|----------|----------|---------|-------------|---------|
| `Argument.name` | Yes | — | Parameter name | `"page"` |
| `Argument.value` | Yes | — | Parameter value (supports `@{param}`) | `"@{pageNo}"` |
| `HTTPArgument.use_equals` | No | `true` | Use '=' between name and value | `"true"` |
| `HTTPArgument.always_encode` | No | `false` | Always URL-encode | `"true"` |
| `Argument.metadata` | No | `"="` | Metadata character | `"="` |
| `HTTPArgument.content_type` | No | `"text/plain"` | Content type | `"application/json"` |

### File Upload (`HTTPsampler.Files`)

When `HTTPSampler.DO_MULTIPART_POST` is `true`:

| Property | Required | Default | Description | Example |
|----------|----------|---------|-------------|---------|
| `File.path` | Yes | — | File path (supports `@{param}`) | `"@{filePath}"` |
| `File.paramname` | Yes | — | Form field name | `"file"` |
| `File.mimetype` | No | `"application/octet-stream"` | MIME type | `"text/plain"` |

### HTTP Headers (`HTTPUD.HTTP_HEADER_PARAMETERS_NAME`)

| Property | Required | Default | Description | Example |
|----------|----------|---------|-------------|---------|
| `name` | Yes | — | Header name | `"Authorization"` |
| `value` | Yes | — | Header value (does NOT support `@{param}`) | `"Bearer ${token}"` |

### Custom Parameters (`HTTPUD.HTTP_USER_DEFINED_ARGUMENTS`)

| Property | Required | Default | Description | Example |
|----------|----------|---------|-------------|---------|
| `Argument.name` | Yes | — | Parameter name (referenced as `@{name}`) | `"userId"` |
| `Argument.value` | No | — | Default value used when sampler doesn't override | `"default_user"` |
| `Argument.desc` | No | — | Parameter description | `"Target user ID"` |
| `HTTPUDArgument.REQUIRED` | No | `false` | Whether sampler must provide this value | `"true"` |

## Usage Examples

### Example 1: Basic API Template with Path Parameter

```
create_jmeter_element with:
- elementType: "httpudconfigelement"
- elementName: "用户管理API模板"
- properties:
  - HTTPUDConfigElement.variable_name: "user_api"
  - HTTPSampler.protocol: "https"
  - HTTPSampler.domain: "api.example.com"
  - HTTPSampler.path: "/api/users/@{userId}"
  - HTTPSampler.method: "GET"
  - HTTPUDArgumentsGui.HTTPUDArguments:
    - {"Argument.name": "userId", "Argument.value": "", "Argument.desc": "User ID", "HTTPUDArgument.REQUIRED": true}
```

### Example 2: POST API with JSON Body and Custom Parameters

```
create_jmeter_element with:
- elementType: "httpudconfigelement"
- elementName: "订单API模板"
- properties:
  - HTTPUDConfigElement.variable_name: "order_api"
  - HTTPSampler.protocol: "https"
  - HTTPSampler.domain: "api.example.com"
  - HTTPSampler.path: "/api/orders"
  - HTTPSampler.method: "POST"
  - HTTPSampler.contentEncoding: "utf-8"
  - HTTPSampler.postBodyRaw: true
  - HTTPsampler.Arguments:
    - {"Argument.name": "", "Argument.value": "{\"product\":\"@{product}\",\"quantity\":@{qty}}", "HTTPArgument.always_encode": false}
  - HTTPUDConfigElement.http_header_parameters_name:
    - {"name": "Content-Type", "value": "application/json"}
    - {"name": "Authorization", "value": "Bearer ${token}"}
  - HTTPUDArgumentsGui.HTTPUDArguments:
    - {"Argument.name": "product", "Argument.value": "default_item", "Argument.desc": "Product name", "HTTPUDArgument.REQUIRED": false}
    - {"Argument.name": "qty", "Argument.value": "1", "Argument.desc": "Quantity", "HTTPUDArgument.REQUIRED": true}
```

### Example 3: Reusable API Template with Headers

```
create_jmeter_element with:
- elementType: "httpudconfigelement"
- elementName: "健康检查API模板"
- properties:
  - HTTPUDConfigElement.variable_name: "health_api"
  - HTTPSampler.protocol: "https"
  - HTTPSampler.domain: "api.example.com"
  - HTTPSampler.path: "/health"
  - HTTPSampler.method: "GET"
  - HTTPUDConfigElement.http_header_parameters_name:
    - {"name": "Accept", "value": "application/json"}
```

## Best Practices

1. **Use unique variable names**: Each config element must have a globally unique `HTTPUD.VARIABLE_NAME` to avoid conflicts
2. **Define required parameters**: Mark parameters as required when samplers must always provide a value
3. **Set meaningful defaults**: Provide sensible default values for optional parameters
4. **Document parameters**: Use the `Argument.desc` field to describe each parameter's purpose
5. **Use JMeter variables in headers**: Headers don't support `@{param}` syntax, so use `${variable}` for dynamic header values
6. **Group related APIs**: Create one config element per API endpoint or service

## Notes

- `@{parameter_name}` substitution only works in the Basic tab fields (URL, path, body, parameters, file upload)
- Advanced tab (timeouts, proxy, embedded resources) and Headers tab do NOT support parameter substitution
- If a parameter referenced with `@{name}` is not defined in the config element, it remains as literal text
- Required parameters without a sampler value will cause a test failure at runtime
- Unspecified HTTP settings fall back to HTTP Request Defaults if configured in the test plan
