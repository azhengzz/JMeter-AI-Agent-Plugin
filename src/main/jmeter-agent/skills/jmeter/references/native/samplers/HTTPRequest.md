# HTTP Request
> **Source**: Apache JMeter built-in component (ships with JMeter, no extra install needed)

## Description

This sampler lets you send an HTTP/HTTPS request to a web server. It also lets you control whether or not JMeter parses HTML files for images and other embedded resources and sends HTTP requests to retrieve them. The following types of embedded resource are retrieved: images, applets, stylesheets (CSS) and resources referenced from those files, external scripts, frames, iframes, background images (body, table, TD, TR), background sound.

The default parser is `org.apache.jmeter.protocol.http.parser.LagartoBasedHtmlParser`. This can be changed by using the property `htmlparser.className` - see `jmeter.properties` for details.

If you are going to send multiple requests to the same web server, consider using an HTTP Request Defaults Configuration Element so you do not have to enter the same information for each HTTP Request.

There are three different test elements used to define the samplers:

- **AJP/1.3 Sampler** - uses the Tomcat mod_jk protocol (allows testing of Tomcat in AJP mode without needing Apache httpd)
- **HTTP Request** - has an implementation drop-down box, which selects the HTTP protocol implementation: `Java` (uses the HTTP implementation provided by the JVM), `HTTPClient4` (uses Apache HttpComponents HttpClient 4.x), or Blank Value (relies on HTTP Request Defaults or `jmeter.httpsampler` property)
- **GraphQL HTTP Request** - a GUI variation of the HTTP Request for GraphQL queries

**Note:** The `FILE` protocol is intended for testing purposes only. It is handled by the same code regardless of which HTTP Sampler is used.

## Parameters

| Property | Required | Default | Description | Example |
|----------|----------|---------|-------------|---------|
| `HTTPSampler.protocol` | No | — | Protocol: `http`, `https`, or `file`. Default: `HTTP` | `"https"` |
| `HTTPSampler.domain` | No | — | Domain name or IP address of the web server. Do not include the `http://` prefix. Required unless provided by HTTP Request Defaults or a full URL is set in Path. | `"www.example.com"` |
| `HTTPSampler.port` | No | — | Port the web server is listening to. Default: 80 for http, 443 for https. Range: 1-65535. | `"8080"` |
| `HTTPSampler.path` | No | — | The path to resource (e.g. `/servlets/myServlet`). If the path starts with `http://` or `https://` then this is used as the full URL. | `"/api/users"` |
| `HTTPSampler.method` | No | `"GET"` | HTTP method | `"POST"` |
| `HTTPSampler.contentEncoding` | No | — | Content encoding to be used (for POST, PUT, PATCH and FILE). This is the character encoding, not related to the Content-Encoding HTTP header. | `"utf-8"` |
| `HTTPSampler.connect_timeout` | No | — | Connection timeout in milliseconds. Number of milliseconds to wait for a connection to open. | `"5000"` |
| `HTTPSampler.response_timeout` | No | — | Response timeout in milliseconds. Number of milliseconds to wait for a response. Applies to each wait for a response. | `"30000"` |
| `HTTPSampler.follow_redirects` | No | `true` | Follow redirects. Only has effect if auto_redirects is not enabled. If set, the JMeter sampler will check if the response is a redirect and follow it. | `"true"` |
| `HTTPSampler.auto_redirects` | No | — | Sets the underlying HTTP protocol handler to automatically follow redirects, so they are not seen by JMeter. Should only be used for GET and HEAD requests. | `"false"` |
| `HTTPSampler.use_keepalive` | No | `true` | JMeter sets the `Connection: keep-alive` header. Works with Apache HttpComponents HttpClient implementations. | `"true"` |
| `HTTPSampler.DO_MULTIPART_POST` | No | — | Use a `multipart/form-data` or `application/x-www-form-urlencoded` post request. | `"true"` |
| `HTTPSampler.postBodyRaw` | No | `false` | Send raw body content. When true, send the body as raw data instead of form parameters. | `"true"` |
| `HTTPsampler.Arguments` | No | — | HTTP request arguments/parameters as an array of HTTPArgument objects. See Nested Arguments below. | See examples |
| `HTTPsampler.Files` | No | — | File upload parameters for multipart/form-data requests. See File Upload below. | See examples |

### Method Values

| Value | Description |
|-------|-------------|
| `GET` | Retrieve resource |
| `POST` | Submit data |
| `PUT` | Update resource |
| `DELETE` | Delete resource |
| `HEAD` | Same as GET but only returns headers |
| `OPTIONS` | Describe communication options |
| `PATCH` | Partial modification |
| `TRACE` | Loop-back test |

### Protocol Values

| Value | Description |
|-------|-------------|
| `http` | HTTP protocol |
| `https` | HTTPS (SSL/TLS) protocol |
| `file` | Local file protocol (testing only) |

### Nested Arguments (`HTTPsampler.Arguments`)

When `HTTPSampler.postBodyRaw` is `false`, each argument item supports:

| Property | Required | Default | Description | Example |
|----------|----------|---------|-------------|---------|
| `Argument.name` | Yes | — | Parameter name | `"username"` |
| `Argument.value` | Yes | — | Parameter value | `"admin"` |
| `HTTPArgument.use_equals` | No | `true` | Whether to use '=' between name and value | `"true"` |
| `HTTPArgument.always_encode` | No | `false` | Whether to always URL-encode the parameter | `"true"` |
| `Argument.metadata` | No | `"="` | Metadata character (typically '=') | `"="` |
| `HTTPArgument.content_type` | No | `"text/plain"` | Content type for the argument | `"application/json"` |

### File Upload (`HTTPsampler.Files`)

When `HTTPSampler.DO_MULTIPART_POST` is `true`, each file item supports:

| Property | Required | Default | Description | Example |
|----------|----------|---------|-------------|---------|
| `File.path` | Yes | — | File path to upload. Supports JMeter variables. | `"${currentJmxDir}${fileSep}data${fileSep}test.txt"` |
| `File.paramname` | Yes | — | HTTP parameter name for the file upload | `"file"` |
| `File.mimetype` | No | `"application/octet-stream"` | MIME type of the file | `"text/plain"` |

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
  - HTTPsampler.Arguments:
    - {"Argument.name": "userId", "Argument.value": "${user_id}"}
```

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
  - HTTPsampler.Arguments:
    - {"Argument.name": "", "Argument.value": "{\"name\":\"张三\",\"age\":23}", "HTTPArgument.always_encode": "false"}
```

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

### Example 4: POST with Multipart Form Data

```
create_jmeter_element with:
- elementType: "httpsampler"
- elementName: "POST_文件上传"
- properties:
  - HTTPSampler.domain: "api.example.com"
  - HTTPSampler.protocol: "https"
  - HTTPSampler.path: "/api/upload"
  - HTTPSampler.method: "POST"
  - HTTPSampler.DO_MULTIPART_POST: "true"
  - HTTPsampler.Files:
    - {"File.path": "${currentJmxDir}${fileSep}data${fileSep}test.txt", "File.paramname": "file", "File.mimetype": "text/plain"}
  - HTTPsampler.Arguments:
    - {"Argument.name": "tenant", "Argument.value": "${tenant}"}
```

## Best Practices

1. **Use meaningful names**: e.g. `GET_查询用户`, `POST_创建订单`
2. **Set appropriate timeouts**: Prevent hanging requests with connect and response timeouts
3. **Use variables for dynamic values**: `${base_url}`, `${user_id}`
4. **Add assertions**: Validate response status and data
5. **Use HTTP Request Defaults**: Avoid repeating common settings across multiple requests
6. **Use Follow Redirects**: Prefer `follow_redirects` over `auto_redirects` to see redirect samples

## Notes

- A separate SSL context is used for each thread
- If the request requires server or proxy login authorization, add an HTTP Authorization Manager
- If the request uses cookies, add an HTTP Cookie Manager
- The Java HTTP implementation has limitations: no connection reuse control, no Kerberos, no client certificate testing
- The HttpClient4 implementation is recommended for most use cases
