# HTTP Request Defaults

## Description

This configuration element sets default values for HTTP Request samplers. It's useful for avoiding repetition when multiple requests share common settings like server address, port, or protocol.

## Parameters

| Property | Required | Description | Example |
|----------|----------|-------------|---------|
| `HTTPSampler.protocol` | No | Default protocol - http or https | `https` |
| `HTTPSampler.domain` | No | Default domain name or IP address | `api.example.com` |
| `HTTPSampler.port` | No | Default port number | `8080` |
| `HTTPSampler.contentEncoding` | No | Default content encoding | `utf-8` |
| `HTTPSampler.path` | No | Default path prefix | `/api/v1` |
| `HTTPSampler.connect_timeout` | No | Default connection timeout (ms) | `5000` |
| `HTTPSampler.response_timeout` | No | Default response timeout (ms) | `30000` |
| `HTTPSampler.follow_redirects` | No | Default redirect behavior | `true` |
| `HTTPSampler.use_keepalive` | No | Default keep-alive setting | `true` |

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

## Best Practices

1. **Scope placement**: Place at Test Plan or Thread Group level as needed
2. **Descriptive name**: `生产环境默认配置`, `测试环境默认配置`
3. **Use variables**: `${base_url}`, `${base_port}` for environment flexibility
4. **Timeout settings**: Set appropriate timeouts to prevent hanging requests
5. **Override**: Individual requests can override defaults when needed

## Advantages

- **Reduced duplication**: Avoid repeating common settings
- **Easy maintenance**: Change server address in one place
- **Environment switching**: Use variables for different environments
- **Consistent behavior**: All requests inherit same defaults
