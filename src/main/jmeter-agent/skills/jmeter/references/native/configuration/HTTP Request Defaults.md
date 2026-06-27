# HTTP Request Defaults
> **Source**: Apache JMeter built-in component (ships with JMeter, no extra install needed)

## Description

This element lets you set default values that your HTTP Request controllers use. For example, if you are creating a Test Plan with 25 HTTP Request controllers and all of the requests are being sent to the same server, you could add a single HTTP Request Defaults element with the "Server Name or IP" field filled in. Then, when you add the 25 HTTP Request controllers, leave the "Server Name or IP" field empty. The controllers will inherit this field value from the HTTP Request Defaults element.

Note: All port values are treated equally; a sampler that does not specify a port will use the HTTP Request Defaults port, if one is provided.

## Parameters

| Property | Required | Default | Description | Example |
|----------|----------|---------|-------------|---------|
| `HTTPSampler.protocol` | No | — | Default protocol (`http`, `https`, or `file`). | `"https"` |
| `HTTPSampler.domain` | No | — | Domain name or IP address of the web server. E.g. `www.example.com`. Do not include the `http://` prefix. | `"api.example.com"` |
| `HTTPSampler.port` | No | — | Port the web server is listening to. Range: 1-65535. | `"443"` |
| `HTTPSampler.path` | No | — | The path to resource (for example, `/servlets/myServlet`). Note that the path is the default for the full path, not a prefix to be applied to paths specified on the HTTP Request screens. | `"/api/v1"` |
| `HTTPSampler.contentEncoding` | No | — | The encoding to be used for the request. | `"utf-8"` |
| `HTTPSampler.connect_timeout` | No | — | Connection Timeout. Number of milliseconds to wait for a connection to open. | `"5000"` |
| `HTTPSampler.response_timeout` | No | — | Response Timeout. Number of milliseconds to wait for a response. | `"30000"` |
| `HTTPSampler.postBodyRaw` | No | `false` | Send raw body content. | `"false"` |
| `HTTPsampler.Arguments` | No | — | HTTP request arguments/parameters as an array of HTTPArgument objects. The query string will be generated from the list of parameters you provide. Each parameter has a name and value. The query string will be generated in the correct fashion depending on the choice of Method. | — |
| `Argument.name` | Yes | — | Parameter name | `"username"` |
| `Argument.value` | Yes | — | Parameter value | `"admin"` |
| `HTTPArgument.use_equals` | No | `true` | Whether to use '=' between name and value | `"true"` |
| `HTTPArgument.always_encode` | No | `false` | Whether to always encode the parameter | `"false"` |
| `Argument.metadata` | No | `"="` | Metadata character (typically '=') | `"="` |
| `HTTPArgument.content_type` | No | `"text/plain"` | Content type for this parameter | `"text/plain"` |

## Usage Examples

### Example 1: API Server Defaults

```
create_jmeter_element with:
- elementType: "httpdefaults"
- elementName: "API服务器默认配置"
- properties:
  - HTTPSampler.protocol: "https"
  - HTTPSampler.domain: "api.example.com"
  - HTTPSampler.port: "443"
  - HTTPSampler.contentEncoding: "utf-8"
  - HTTPSampler.connect_timeout: "5000"
  - HTTPSampler.response_timeout: "30000"
```

Then individual HTTP requests only need to specify path and method:
```
GET_用户列表 → path: /users
POST_创建用户 → path: /users, method: POST
GET_用户详情 → path: /users/${userId}
```

### Example 2: Testing Environment Configuration

```
create_jmeter_element with:
- elementType: "httpdefaults"
- elementName: "测试环境默认配置"
- properties:
  - HTTPSampler.protocol: "http"
  - HTTPSampler.domain: "${__P(base_url,localhost)}"
  - HTTPSampler.port: "${__P(base_port,8080)}"
  - HTTPSampler.contentEncoding: "utf-8"
```

### Example 3: With Default Parameters

```
create_jmeter_element with:
- elementType: "httpdefaults"
- elementName: "带默认参数的配置"
- properties:
  - HTTPSampler.protocol: "https"
  - HTTPSampler.domain: "api.example.com"
  - HTTPSampler.path: "/api/v1"
  - HTTPsampler.Arguments:
    - Argument.name: "format"
    - Argument.value: "json"
    - HTTPArgument.use_equals: "true"
    - HTTPArgument.always_encode: "true"
```

## Best Practices

1. **Scope placement**: Place at Test Plan or Thread Group level as needed
2. **Descriptive name**: Use clear names like `生产环境默认配置`, `测试环境默认配置`
3. **Use variables**: `${base_url}`, `${base_port}` for environment flexibility
4. **Timeout settings**: Set appropriate timeouts to prevent hanging requests
5. **Override**: Individual requests can override defaults when needed
6. **Path is full default**: The path is the default for the full path, not a prefix

## Notes

- All port values are treated equally; a sampler that does not specify a port will use the HTTP Request Defaults port if provided
- The path is the default for the full path, not a prefix to be applied to paths specified on the HTTP Request screens
- Individual HTTP Request controllers can override any default value
- Useful for avoiding repetition when multiple requests share common settings
