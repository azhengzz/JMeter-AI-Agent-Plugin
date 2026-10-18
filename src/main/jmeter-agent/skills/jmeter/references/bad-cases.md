# JMeter Common Pitfalls

Record actual issues encountered in development to help avoid repeating mistakes.

## Index

| Problem Type | Case |
|--------------|------|
| Variable Reference Issues | Case 1.1 |
| Assertion Configuration Issues | Case 2.1 |

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

## 2. Assertion Configuration Issues

### Case 2.1: ResponseAssertion Multiple Patterns Never Match (AND vs Or Bit)

**Problem Description**

An assertion meant to accept status code 200 OR 201 is configured with
`Assertion.test_type: 2` (Contains) and `Asserion.test_strings: ["200", "201"]`. It fails on
every response: a 200 response reports `code expected to contain /201/` (and vice versa), so
the check can never pass. A follow-up attempt with `test_type: 16` and the regex `200|201`
also fails, which is easily misdiagnosed as "the tool escapes regex metacharacters".

**Root Cause**

1. Multiple patterns in a ResponseAssertion are **AND-ed by default** — every pattern must match
2. The Or flag is bit `32`, combined into `test_type` by bitwise OR; it is easy to miss because
   `test_type` is a plain Integer rather than an enum
3. Bit values are easy to confuse from memory: `1` = Matches and `2` = Contains are the regex
   modes; `8` = Equals and `16` = Substring match **literally**, so regex metacharacters are
   searched as plain text under `16` — nothing escapes them

**Incorrect Approach**

```
# Patterns AND-ed without the Or bit — never all match, fails on both 200 and 201
Assertion.test_type: "2"
Asserion.test_strings: ["200", "201"]

# Substring(16) is literal — "200|201" is searched as plain text and never found
Assertion.test_type: "16"
Asserion.test_strings: ["200|201"]
```

**Correct Solution**

```
# Option A: Contains + Or bit (34 = 2 + 32) — any pattern matching passes
Assertion.test_type: "34"
Asserion.test_strings: ["200", "201"]

# Option B: Equals + Or bit (40 = 8 + 32) — code equals any pattern
Assertion.test_type: "40"
Asserion.test_strings: ["200", "201"]

# Option C: single regex — Contains(2) is itself a regex find
Assertion.test_type: "2"
Asserion.test_strings: ["200|201"]
```

**Key Points**

- Multiple patterns default to AND; add bit `32` (Or) for any-match semantics
- Regex modes: `1` Matches (whole text), `2` Contains (find); literal modes: `8` Equals,
  `16` Substring — regex metacharacters under `8`/`16` are matched as plain text
- Do not jump to a JSR223 assertion for simple multi-value checks — the Or bit covers it
