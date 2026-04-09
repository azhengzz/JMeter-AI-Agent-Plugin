# HTTP Request

## Description

This sampler sends an HTTP/HTTPS request to a web server. It supports various HTTP methods and allows control over redirect behavior, keep-alive connections, and request encoding.

## Parameters

| Property | Required | Description | Example |
|----------|----------|-------------|---------|
| `HTTPSampler.protocol` | No | Protocol - http, https, or file. Default: http | `https` |
| `HTTPSampler.domain` | Yes* | Domain name or IP address (without http:// prefix) | `www.httpbin.org` |
| `HTTPSampler.port` | No | Port number. Default: 80 for http, 443 for https | `8080` |
| `HTTPSampler.contentEncoding` | No | Content encoding for POST/PUT/PATCH | `utf-8` |
| `HTTPSampler.method` | No | HTTP method | `GET`, `POST`, `PUT`, `DELETE`, `HEAD`, `OPTIONS`, `PATCH` |
| `HTTPSampler.path` | Yes* | Resource path (including query string if needed) | `/api/users` |
| `HTTPSampler.follow_redirects` | No | Follow redirects (only if auto_redirects is false) | `true` |
| `HTTPSampler.auto_redirects` | No | Auto-follow redirects at protocol level | `false` |
| `HTTPSampler.use_keepalive` | No | Use keep-alive connections | `true` |
| `HTTPSampler.DO_MULTIPART_POST` | No | Use multipart/form-data for POST | `false` |
| `HTTPSampler.connect_timeout` | No | Connection timeout in milliseconds | `5000` |
| `HTTPSampler.response_timeout` | No | Response timeout in milliseconds | `30000` |

*Note: `domain` and `path` are required unless provided by HTTP Request Defaults or a full URL is specified in path.

## Usage Examples

### Example 1: GET Request with Query Parameters

```
create_jmeter_element with:
- elementType: "httpsampler"
- elementName: "GET_查询用户信息"
- properties:
  - HTTPSampler.domain: "api.example.com"
  - HTTPSampler.protocol: "https"
  - HTTPSampler.path: "/api/users"
  - HTTPSampler.method: "GET"
```

Add query parameters using HTTP Arguments:
- name: `userId`
- value: `${user_id}`

### Example 2: POST Request with JSON Body

```
create_jmeter_element with:
- elementType: "httpsampler"
- elementName: "POST_创建用户"
- properties:
  - HTTPSampler.domain: "api.example.com"
  - HTTPSampler.protocol: "https"
  - HTTPSampler.path: "/api/users"
  - HTTPSampler.method: "POST"
  - HTTPSampler.contentEncoding: "utf-8"
  - HTTPSampler.postBodyRaw: "true"
```

For JSON body, add a single argument with:
- name: (empty)
- value: `{"name":"张三","age":23,"address":"Beijing, China"}`

### Example 3: PUT Request with Parameters

```
create_jmeter_element with:
- elementType: "httpsampler"
- elementName: "PUT_更新用户信息"
- properties:
  - HTTPSampler.domain: "api.example.com"
  - HTTPSampler.protocol: "https"
  - HTTPSampler.path: "/api/users/${userId}"
  - HTTPSampler.method: "PUT"
  - HTTPSampler.contentEncoding: "utf-8"
```

### Example 4: GET Request with Query Parameters (Arguments Object)

```
create_jmeter_element with:
- elementType: "httpsampler"
- elementName: "GET_查询用户"
- properties:
  - HTTPSampler.domain: "www.httpbin.org"
  - HTTPSampler.protocol: "https"
  - HTTPSampler.path: "/get"
  - HTTPSampler.method: "GET"
  - HTTPsampler.Arguments:
    - {"Argument.name": "name", "Argument.value": "张三"}
    - {"Argument.name": "age", "Argument.value": "23"}
```

### Example 5: POST Request with JSON Body (Raw)

```
create_jmeter_element with:
- elementType: "httpsampler"
- elementName: "POST_用户登录"
- properties:
  - HTTPSampler.domain: "api.example.com"
  - HTTPSampler.protocol: "https"
  - HTTPSampler.path: "/api/login"
  - HTTPSampler.method: "POST"
  - HTTPSampler.postBodyRaw: true
  - HTTPSampler.contentEncoding: "utf-8"
  - HTTPsampler.Arguments:
    - {"Argument.name": "", "Argument.value": "{\"username\":\"admin\",\"password\":\"123456\"}", "HTTPArgument.always_encode": false}
```

## Request Body Types

### 1. Query Parameters (GET/DELETE)
Parameters are appended to URL: `/api/users?name=value`

### 2. Form Data (POST/PUT)
Use `multipart/form-data` or `application/x-www-form-urlencoded`

### 3. JSON Body (POST/PUT)
Set `HTTPSampler.postBodyRaw: true` and provide JSON in argument value

## Best Practices

1. **Use meaningful names**: `GET_查询用户`, `POST_创建订单`
2. **Set appropriate timeouts**: Prevent hanging requests
3. **Use variables for dynamic values**: `${base_url}`, `${user_id}`
4. **Add assertions**: Validate response status and data
5. **Use HTTP Request Defaults**: Avoid repeating common settings
