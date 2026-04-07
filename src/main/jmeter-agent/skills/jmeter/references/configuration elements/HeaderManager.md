# Header Manager

## Description

Header Manager allows you to add or override HTTP request headers. It's commonly used to set Content-Type, Authorization tokens, User-Agent, and other custom headers for HTTP requests.

## Parameters

| Property | Required | Description | Example |
|----------|----------|-------------|---------|
| `HeaderManager.headers` | No | HTTP headers as key-value pairs | See examples below |

## Usage Examples

### Example 1: JSON API Headers

```
create_jmeter_element with:
- elementType: "headermanager"
- elementName: "JSON请求头"
- properties:
  - HeaderManager.headers:
    - Content-Type: "application/json"
    - Accept: "application/json"
    - User-Agent: "JMeter Load Test"
```

### Example 2: Authentication Header with Token

```
create_jmeter_element with:
- elementType: "headermanager"
- elementName: "带Token的请求头"
- properties:
  - HeaderManager.headers:
    - Content-Type: "application/json"
    - Authorization: "Bearer ${token}"
    - User-Agent: "JMeter Load Test"
```

### Example 3: Form Data Headers

```
create_jmeter_element with:
- elementType: "headermanager"
- elementName: "表单请求头"
- properties:
  - HeaderManager.headers:
    - Content-Type: "application/x-www-form-urlencoded"
    - X-Requested-With: "XMLHttpRequest"
```

### Example 4: Using Headers Object Syntax

```
create_jmeter_element with:
- elementType: "headermanager"
- elementName: "JSON请求头"
- properties:
  - HeaderManager.headers:
    - Content-Type: "application/json"
    - Authorization: "Bearer ${token}"
```

## Best Practices

1. **Scope placement**: Place at Test Plan or Thread Group level for common headers
2. **Use variables**: `${token}`, `${api_key}` for dynamic values
3. **Descriptive name**: `JSON请求头`, `认证请求头`, `API通用请求头`
4. **Combine with HTTP Request Defaults**: Avoid repetition across requests
5. **Cookie handling**: Use Cookie Manager for cookies, not Header Manager

## Common HTTP Headers

| Header | Purpose | Example Values |
|--------|---------|----------------|
| `Content-Type` | Request body media type | `application/json`, `application/x-www-form-urlencoded` |
| `Accept` | Expected response type | `application/json`, `text/html` |
| `Authorization` | Authentication credentials | `Bearer ${token}`, `Basic xxx` |
| `User-Agent` | Client identifier | `Mozilla/5.0...`, custom identifier |
| `Accept-Language` | Preferred language | `zh-CN`, `en-US` |
| `Accept-Encoding` | Acceptable encodings | `gzip`, `deflate` |
| `X-Requested-With` | AJAX request marker | `XMLHttpRequest` |
| `Origin` | Request origin | `https://example.com` |
| `Referer` | Source of request | `https://example.com/page` |

## Notes

- Headers are inherited by child samplers
- Multiple Header Managers in same scope will merge headers
- Sampler-level headers override parent-level headers of same name
- Use `${variable}` syntax for dynamic header values
- For cookies, prefer using Cookie Manager over manual Cookie headers
