# JSR223 Assertion

## Description

JSR223 Assertion executes custom code to validate sampler results. It provides maximum flexibility for complex validation logic.

## Parameters

| Property | Required | Description | Example |
|----------|----------|-------------|---------|
| `scriptLanguage` | Yes | Scripting language | `groovy` |
| `script` | Yes* | Script code to execute | See examples below |
| `scriptFile` | Yes* | Path to external script file | `/path/to/script.groovy` |

## Usage Examples

### Example 1: Check Response Code Range

```
create_jmeter_element with:
- elementType: "jsr223assertion"
- elementName: "断言_状态码2xx"
- properties:
  - scriptLanguage: "groovy"
  - script: |
    def code = prev.getResponseCode() as Integer
    if (code < 200 || code >= 300) {
        AssertionResult.setFailure(true)
        AssertionResult.setFailureMessage("Expected 2xx status, got: ${code}")
    }
```

### Example 2: Validate JSON Schema

```
create_jmeter_element with:
- elementType: "jsr223assertion"
- elementName: "断言_JSON格式正确"
- properties:
  - scriptLanguage: "groovy"
  - script: |
    import groovy.json.JsonSlurper

    try {
        def response = prev.getResponseDataAsString()
        def json = new JsonSlurper().parseText(response)

        // Check required fields
        if (!json.containsKey("status")) {
            AssertionResult.setFailure(true)
            AssertionResult.setFailureMessage("Missing 'status' field")
            return
        }

        if (!json.containsKey("data")) {
            AssertionResult.setFailure(true)
            AssertionResult.setFailureMessage("Missing 'data' field")
            return
        }
    } catch (Exception e) {
        AssertionResult.setFailure(true)
        AssertionResult.setFailureMessage("Invalid JSON: " + e.message)
    }
```

### Example 3: Custom Business Logic

```
create_jmeter_element with:
- elementType: "jsr223assertion"
- elementName: "断言_业务逻辑验证"
- properties:
  - scriptLanguage: "groovy"
  - script: |
    import groovy.json.JsonSlurper

    def json = new JsonSlurper().parseText(prev.getResponseDataAsString())

    // Check balance is positive
    def balance = json.data.balance as Double
    if (balance < 0) {
        AssertionResult.setFailure(true)
        AssertionResult.setFailureMessage("Balance cannot be negative: ${balance}")
    }

    // Check account is active
    if (json.data.status != "active") {
        AssertionResult.setFailure(true)
        AssertionResult.setFailureMessage("Account not active: ${json.data.status}")
    }
```

### Example 4: Compare with Expected Response

```
create_jmeter_element with:
- elementType: "jsr223assertion"
- elementName: "断言_响应匹配预期"
- properties:
  - scriptLanguage: "groovy"
  - script: |
    def response = prev.getResponseDataAsString()
    def expected = """
    {
        "status": "success",
        "data": {
            "userId": 123
        }
    }
    """.trim()

    // Normalize JSON for comparison
    import groovy.json.JsonSlurper
    def responseJson = new JsonSlurper().parseText(response)
    def expectedJson = new JsonSlurper().parseText(expected)

    if (responseJson != expectedJson) {
        AssertionResult.setFailure(true)
        AssertionResult.setFailureMessage("Response does not match expected")
    }
```

### Example 5: Database Validation

```
create_jmeter_element with:
- elementType: "jsr223assertion"
- elementName: "断言_数据库一致性"
- properties:
  - scriptLanguage: "groovy"
  - script: |
    import groovy.sql.Sql
    import groovy.json.JsonSlurper

    def json = new JsonSlurper().parseText(prev.getResponseDataAsString())
    def userId = json.data.userId

    def sql = Sql.newInstance("jdbc:mysql://localhost:3306/test", "user", "pass")
    def count = sql.firstRow("SELECT COUNT(*) as c FROM users WHERE id = ?", [userId]).c

    if (count == 0) {
        AssertionResult.setFailure(true)
        AssertionResult.setFailureMessage("User ${userId} not found in database")
    }

    sql.close()
```

### Example 6: Multi-Condition Validation

```
create_jmeter_element with:
- elementType: "jsr223assertion"
- elementName: "断言_多条件验证"
- properties:
  - scriptLanguage: "groovy"
  - script: |
    import groovy.json.JsonSlurper

    def failures = []

    // Check response code
    if (prev.getResponseCode() != "200") {
        failures.add("Status code is ${prev.getResponseCode()}")
    }

    // Check response time
    if (prev.getTime() > 3000) {
        failures.add("Response time ${prev.getTime()}ms exceeds 3000ms")
    }

    // Check response size
    if (prev.getBytesAsLong() == 0) {
        failures.add("Response is empty")
    }

    // Check JSON structure
    try {
        def json = new JsonSlurper().parseText(prev.getResponseDataAsString())
        if (!json.status) {
            failures.add("Missing 'status' field")
        }
    } catch (Exception e) {
        failures.add("Invalid JSON: ${e.message}")
    }

    // Fail if any issues
    if (!failures.isEmpty()) {
        AssertionResult.setFailure(true)
        AssertionResult.setFailureMessage("Validation failed: " + failures.join(", "))
    }
```

## AssertionResult Methods

| Method | Description |
|--------|-------------|
| `setFailure(true)` | Mark assertion as failed |
| `setFailureMessage(msg)` | Set failure message |
| `setFailure(false)` | Mark assertion as passed |
| `getFailureMessage()` | Get current failure message |

## Built-in Variables

- `prev`: SampleResult - access to sampler result
- `AssertionResult`: AssertionResult - control assertion outcome
- `vars`: JMeterVariables - get/set variables
- `log`: Logger - write to log file
- `ctx`: JMeterContext - test context

## Common Validations

### Response Code
```groovy
if (prev.getResponseCode() != "200") {
    AssertionResult.setFailure(true)
}
```

### Response Contains Text
```groovy
if (!prev.getResponseDataAsString().contains("success")) {
    AssertionResult.setFailure(true)
}
```

### Response Time
```groovy
if (prev.getTime() > 5000) {
    AssertionResult.setFailure(true)
    AssertionResult.setFailureMessage("Too slow: ${prev.getTime()}ms")
}
```

### JSON Validation
```groovy
import groovy.json.JsonSlurper
def json = new JsonSlurper().parseText(prev.getResponseDataAsString())
if (json.status != "success") {
    AssertionResult.setFailure(true)
}
```

## Best Practices

1. **Clear messages**: Provide descriptive failure messages
2. **Multiple checks**: Validate multiple aspects
3. **Error handling**: Use try-catch for robustness
4. **Logging**: Log details for debugging
5. **Keep simple**: Don't overcomplicate - use other assertions when possible

## Tips

1. **Use Groovy**: Best performance
2. **Test offline**: Test script logic separately
3. **Return early**: Fail fast on first error
4. **Log details**: Use log.info() for debugging
5. **Reuse code**: Use scriptFile for complex validation

## Notes

- Maximum flexibility for custom validation
- Can access all response data
- Can make external calls (database, API)
- Use when built-in assertions aren't enough
- Groovy recommended for performance
