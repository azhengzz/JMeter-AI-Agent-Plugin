# JMeter Common Pitfalls

Record actual issues encountered in development to help avoid repeating mistakes.

## Index

| Problem Type | Case |
|--------------|------|
| Variable Reference Issues | Case 1.1 |

## 1. Variable Reference Issues

### Case 1.1: Cannot Read Variables Across Thread Groups

**Problem Description**

When extracting data in one thread group and trying to use it in another, the variable appears as the literal string `${variableName}` instead of the actual value.

**Root Cause**

1. In JMeter, **variables** have scope limited to their current thread group
2. Variables extracted in Thread Group 1 cannot be read by Thread Group 2
3. The value remains as the literal string `${token}` instead of the actual token value

**Incorrect Approach**

```
Thread Group 1 - Login
├── HTTP Request: POST Login
└── JSON Extractor: Extract token → variable name "token"

Thread Group 2 - Query Orders
├── HTTP Header Manager: Authorization: Bearer ${token}
└── HTTP Request: GET Orders
```

Result: The header becomes `Bearer ${token}` instead of `Bearer eyJhbGc...`, causing authentication failure.

**Correct Solution**

Use **properties** for cross-thread group communication:

```
Thread Group 1 - Login
├── HTTP Request: POST Login
├── JSON Extractor: Extract token → variable name "token"
└── JSR223 PostProcessor: Store Token to Property
    Script: ${__setProperty(token, ${token},)}

Thread Group 2 - Query Orders
├── HTTP Header Manager: Authorization: Bearer ${__P(token,)}
└── HTTP Request: GET Orders
```

**Key Points**

- **Variables** (`vars`): Local to thread group, use `${variableName}`
- **Properties** (`props`): Global scope, use `${__P(propertyName, defaultValue)}`
- Use `__setProperty` function to convert variable to property
- Use `__P` function to read property value

**Implementation Example**

```groovy
// JSR223 PostProcessor to store variable as property
props.put("token", vars.get("token"));

// Or using JMeter function
${__setProperty(token, ${token},)}

// In another thread group, read the property
${__P(token,)}
```
