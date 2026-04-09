# Cookie Manager

## Description

Cookie Manager automatically stores and sends cookies with HTTP requests. It simulates browser cookie behavior, maintaining session state across requests.

## Parameters

| Property | Required | Description | Example |
|----------|----------|-------------|---------|
| `CookieManager.clearEachIteration` | No | Clear cookies at each iteration | `true` or `false` (default: `false`) |
| `CookieManager.cookiePolicy` | No | Cookie policy specification | `default`, `compatibility`, `rfc2109`, `rfc2965` |
| `CookieManager.saveCookies` | No | Save received cookies | `true` or `false` (default: `true`) |

## Usage Examples

### Example 1: Basic Cookie Management

```
create_jmeter_element with:
- elementType: "cookiemanager"
- elementName: "Cookie管理器"
- properties:
  - CookieManager.clearEachIteration: "false"
  - CookieManager.saveCookies: "true"
  - CookieManager.cookiePolicy: "default"
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
  - CookieManager.cookies: |
    Name: session_id
    Value: abc123xyz
    Domain: example.com
    Path: /

    Name: user_pref
    Value: dark_mode
    Domain: example.com
    Path: /
```

## Cookie Policies

| Policy | Description |
|--------|-------------|
| `default` | Default browser compatibility |
| `compatibility` | Legacy compatibility mode |
| `rfc2109` | RFC 2109 specification |
| `rfc2965` | RFC 2965 specification |

## Automatic Cookie Handling

When a server sends a `Set-Cookie` header:
```
Set-Cookie: session_id=abc123; Path=/; Domain=.example.com
```

Cookie Manager automatically:
1. Stores the cookie
2. Includes it in subsequent requests to matching domains/paths
3. Updates cookie if server sends new value

## Use Cases

### 1. Session Management
```
Login Request → Server sets session cookie
              ↓
Cookie Manager stores session cookie
              ↓
Subsequent requests include session cookie automatically
```

### 2. Shopping Cart
```
Add to Cart → Server sets cart cookie
           ↓
Cookie Manager stores cart cookie
           ↓
View Cart request includes cart cookie
```

### 3. User Preferences
```
Set Preference → Server sets preference cookie
               ↓
Cookie Manager stores preference
               ↓
Future requests include preference cookie
```

## Scope and Placement

### Thread Group Level
Cookies are shared by all samplers in the thread group.

### Test Plan Level
Cookies are shared by all thread groups.

### Recommendation
Place Cookie Manager at Thread Group level for most scenarios.

## Best Practices

1. **Place strategically**: Put at Thread Group or Test Plan level
2. **Clear for new sessions**: Use `clearEachIteration: true` to simulate new user each iteration
3. **Debug with View Results Tree**: Check Cookie headers in request/response
4. **Manual for testing**: Add manual cookies for specific test scenarios
5. **Avoid conflicts**: Only one Cookie Manager per scope

## Common Issues

### Issue 1: Cookies Not Being Sent
**Cause**: Cookie domain/path doesn't match request
**Solution**: Check cookie domain and path settings

### Issue 2: Expired Cookies
**Cause**: Server cookie has expired
**Solution**: Clear cookies or use shorter session timeouts

### Issue 3: Cross-Domain Cookies
**Cause**: Cookies are domain-specific
**Solution**: Ensure cookie domain matches request domain

## Debugging Cookies

Use Debug Sampler to view cookies:
```
create_jmeter_element with:
- elementType: "debugsampler"
- elementName: "查看Cookies"
- properties:
  - displayJMeterVariables: "true"
  - displayCookieVariables: "true"
```

## Notes

- Only one Cookie Manager should be active per scope
- Cookies are stored per thread (not shared between threads)
- ClearEachIteration affects all threads independently
- Manual cookies can be added for specific test scenarios
- Cookie Manager handles both session and persistent cookies
