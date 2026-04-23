# Cookie Manager

## Description

The Cookie Manager element has two functions:

First, it stores and sends cookies just like a web browser. If you have an HTTP Request and the response contains a cookie, the Cookie Manager automatically stores that cookie and will use it for all future requests to that particular web site. Each JMeter thread has its own "cookie storage area". So, if you are testing a web site that uses a cookie for storing session information, each JMeter thread will have its own session. Note that such cookies do not appear on the Cookie Manager display, but they can be seen using the View Results Tree Listener.

JMeter checks that received cookies are valid for the URL. This means that cross-domain cookies are not stored. If you have bugged behaviour or want Cross-Domain cookies to be used, define the JMeter property `CookieManager.check.cookies=false`.

Received Cookies can be stored as JMeter thread variables. To save cookies as variables, define the property `CookieManager.save.cookies=true`. Also, cookies names are prefixed with `COOKIE_` before they are stored (this avoids accidental corruption of local variables). To revert to the original behaviour, define the property `CookieManager.name.prefix= ` (one or more spaces). If enabled, the value of a cookie with the name `TEST` can be referred to as `${COOKIE_TEST}`.

Second, you can manually add a cookie to the Cookie Manager. However, if you do this, the cookie will be shared by all JMeter threads. Note that such Cookies are created with an Expiration time far in the future.

Cookies with `null` values are ignored by default. This can be changed by setting the JMeter property: `CookieManager.delete_null_cookies=false`. Note that this also applies to manually defined cookies. Note also that the cookie name must be unique - if a second cookie is defined with the same name, it will replace the first.

## Parameters

| Property | Required | Default | Description | Example |
|----------|----------|---------|-------------|---------|
| `CookieManager.clearEachIteration` | No | `false` | If selected, all server-defined cookies are cleared each time the main Thread Group loop is executed. Any cookie defined in the GUI are not cleared. | `"true"` |
| `CookieManager.policy` | No | `"standard"` | The cookie policy that will be used to manage the cookies. "standard" is the default since 3.0, and should work in most cases. | `"standard"` |
| `CookieManager.implementation` | No | `"org.apache.jmeter.protocol.http.control.HC4CookieHandler"` | HC4CookieHandler (HttpClient 4.5.X API). Default is HC4CookieHandler since 3.0. | `"org.apache.jmeter.protocol.http.control.HC4CookieHandler"` |
| `CookieManager.controlledByThreadGroup` | No | `false` | Control by thread group | `"false"` |
| `CookieManager.cookies` | No | — | This gives you the opportunity to use hardcoded cookies that will be used by all threads during the test execution. The "domain" is the hostname of the server (without http://); the port is currently ignored. Contains nested cookie entries. | See examples below |
| `CookieManager.cookies` → `Cookie.name` | Yes | — | Cookie name | `"session_id"` |
| `CookieManager.cookies` → `Cookie.value` | Yes | — | Cookie value | `"abc123xyz"` |
| `CookieManager.cookies` → `Cookie.domain` | No | — | Cookie domain (hostname of the server without http://) | `"example.com"` |
| `CookieManager.cookies` → `Cookie.path` | No | `"/"` | Cookie path | `"/"` |
| `CookieManager.cookies` → `Cookie.secure` | No | `false` | Whether cookie is secure (HTTPS only) | `"false"` |
| `CookieManager.cookies` → `Cookie.expires` | No | `0` | Cookie expiration time (timestamp, 0 = session cookie) | `"0"` |
| `CookieManager.cookies` → `Cookie.path_specified` | No | `true` | Whether path was explicitly specified | `"true"` |
| `CookieManager.cookies` → `Cookie.domain_specified` | No | `true` | Whether domain was explicitly specified | `"true"` |

### Cookie Policies

| Policy | Description |
|--------|-------------|
| `standard` | Default since JMeter 3.0. Standard cookie policy, should work in most cases |
| `standard-strict` | Strict standard compliance |
| `ignoreCookies` | Equivalent to omitting the Cookie Manager |
| `netscape` | Netscape draft cookie specification |
| `default` | Default browser compatibility |
| `compatibility` | Legacy compatibility mode |
| `rfc2109` | RFC 2109 specification |
| `rfc2965` | RFC 2965 specification |
| `best-match` | Best matching cookie specification |

## Usage Examples

### Example 1: Basic Cookie Management

```
create_jmeter_element with:
- elementType: "cookiemanager"
- elementName: "Cookie管理器"
- properties:
  - CookieManager.clearEachIteration: "false"
  - CookieManager.policy: "standard"
  - CookieManager.implementation: "org.apache.jmeter.protocol.http.control.HC4CookieHandler"
```

### Example 2: Clear Cookies Each Iteration

```
create_jmeter_element with:
- elementType: "cookiemanager"
- elementName: "每次迭代清空Cookie"
- properties:
  - CookieManager.clearEachIteration: "true"
```

### Example 3: Add Manual Cookies

```
create_jmeter_element with:
- elementType: "cookiemanager"
- elementName: "带初始Cookie的管理器"
- properties:
  - CookieManager.cookies:
    - Cookie.name: "session_id"
      Cookie.value: "abc123xyz"
      Cookie.domain: "example.com"
      Cookie.path: "/"
    - Cookie.name: "user_pref"
      Cookie.value: "dark_mode"
      Cookie.domain: "example.com"
      Cookie.path: "/"
```

## Best Practices

1. **Place strategically**: Put at Thread Group or Test Plan level
2. **Clear for new sessions**: Use `clearEachIteration: "true"` to simulate new user each iteration
3. **Debug with View Results Tree**: Check Cookie headers in request/response
4. **Manual for testing**: Add manual cookies for specific test scenarios
5. **Avoid conflicts**: Only one Cookie Manager per scope - if there is more than one Cookie Manager in the scope of a Sampler, there is no way to specify which one is to be used

## Notes

- Only one Cookie Manager should be active per scope
- Cookies are stored per thread (not shared between threads)
- ClearEachIteration affects all threads independently
- Manual cookies can be added for specific test scenarios and are shared by all threads
- Cookie Manager handles both session and persistent cookies
- Received cookies can be stored as JMeter variables by setting `CookieManager.save.cookies=true`
- Cookie variable names are prefixed with `COOKIE_` (e.g. `${COOKIE_TEST}`)
- The cookie name must be unique - a second cookie with the same name replaces the first
