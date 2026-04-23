# Header Manager

## Description

The Header Manager lets you add or override HTTP request headers.

JMeter now supports multiple Header Managers. The header entries are merged to form the list for the sampler. If an entry to be merged matches an existing header name, it replaces the previous entry. This allows one to set up a default set of headers, and apply adjustments to particular samplers. Note that an empty value for a header does not remove an existing header, it just replaces its value.

## Parameters

| Property | Required | Default | Description | Example |
|----------|----------|---------|-------------|---------|
| `HeaderManager.headers` | No | — | HTTP headers as an array of header entries. Each entry contains a name and value. | See examples below |
| `HeaderManager.headers` → `Header.name` | Yes | — | Name of the request header. Two common request headers you may want to experiment with are `User-Agent` and `Referer`. | `"Content-Type"` |
| `HeaderManager.headers` → `Header.value` | Yes | — | Request header value. | `"application/json"` |

## Usage Examples

### Example 1: JSON API Headers

```
create_jmeter_element with:
- elementType: "headermanager"
- elementName: "JSON请求头"
- properties:
  - HeaderManager.headers:
    - Header.name: "Content-Type"
      Header.value: "application/json"
    - Header.name: "Accept"
      Header.value: "application/json"
    - Header.name: "User-Agent"
      Header.value: "JMeter Load Test"
```

### Example 2: Authentication Header with Token

```
create_jmeter_element with:
- elementType: "headermanager"
- elementName: "带Token的请求头"
- properties:
  - HeaderManager.headers:
    - Header.name: "Content-Type"
      Header.value: "application/json"
    - Header.name: "Authorization"
      Header.value: "Bearer ${token}"
    - Header.name: "User-Agent"
      Header.value: "JMeter Load Test"
```

### Example 3: Form Data Headers

```
create_jmeter_element with:
- elementType: "headermanager"
- elementName: "表单请求头"
- properties:
  - HeaderManager.headers:
    - Header.name: "Content-Type"
      Header.value: "application/x-www-form-urlencoded"
    - Header.name: "X-Requested-With"
      Header.value: "XMLHttpRequest"
```

## Best Practices

1. **Scope placement**: Place at Test Plan or Thread Group level for common headers
2. **Use variables**: `${token}`, `${api_key}` for dynamic values
3. **Descriptive name**: Use names like `JSON请求头`, `认证请求头`, `API通用请求头`
4. **Combine with HTTP Request Defaults**: Avoid repetition across requests
5. **Cookie handling**: Use Cookie Manager for cookies, not Header Manager

## Notes

- Headers are inherited by child samplers
- Multiple Header Managers in same scope will merge headers
- Sampler-level headers override parent-level headers of the same name
- Use `${variable}` syntax for dynamic header values
- For cookies, prefer using Cookie Manager over manual Cookie headers
- An empty value for a header does not remove an existing header, it replaces its value
