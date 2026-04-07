# JSR223 Pre-Processor

## Description

JSR223 Pre-Processor executes code before a sampler runs. It's commonly used to modify request parameters, prepare test data, or perform setup operations before sampling.

## Parameters

| Property | Required | Description | Example |
|----------|----------|-------------|---------|
| `scriptLanguage` | Yes | Scripting language | `groovy` |
| `script` | Yes* | Script code to execute | See examples below |
| `scriptFile` | Yes* | Path to external script file | `/path/to/script.groovy` |

## Usage Examples

### Example 1: Generate Timestamp

```
create_jmeter_element with:
- elementType: "jsr223preprocessor"
- elementName: "生成时间戳"
- properties:
  - scriptLanguage: "groovy"
  - script: |
    import java.time.Instant
    String timestamp = Instant.now().toString()
    vars.put("request_timestamp", timestamp)
```

### Example 2: Calculate Checksum

```
create_jmeter_element with:
- elementType: "jsr223preprocessor"
- elementName: "计算请求签名"
- properties:
  - scriptLanguage: "groovy"
  - script: |
    import java.security.MessageDigest

    def data = vars.get("user_id") + vars.get("timestamp") + "SECRET_KEY"
    def digest = MessageDigest.getInstance("SHA-256").digest(data.bytes)
    def checksum = digest.encodeHex().toString()
    vars.put("request_checksum", checksum)
```

### Example 3: Modify Request Data

```
create_jmeter_element with:
- elementType: "jsr223preprocessor"
- elementName: "准备请求数据"
- properties:
  - scriptLanguage: "groovy"
  - script: |
    // Generate random order ID
    String orderId = "ORD-" + System.currentTimeMillis()
    vars.put("order_id", orderId)

    // Calculate quantity * price
    def quantity = vars.get("quantity") as Integer
    def price = vars.get("price") as Double
    def total = quantity * price
    vars.put("total_amount", total.toString())
```

### Example 4: Prepare JSON Body

```
create_jmeter_element with:
- elementType: "jsr223preprocessor"
- elementName: "构建JSON请求体"
- properties:
  - scriptLanguage: "groovy"
  - script: |
    import groovy.json.JsonBuilder

    def json = new JsonBuilder()
    json {
      userId vars.get("user_id")
      productId vars.get("product_id")
      quantity vars.get("quantity")
      timestamp System.currentTimeMillis()
    }

    vars.put("json_body", json.toString())
```

## Common Use Cases

### 1. Parameter Generation
```groovy
// Generate random values
String randomId = UUID.randomUUID().toString()
vars.put("request_id", randomId)
```

### 2. Data Transformation
```groovy
// Convert date format
def inputDate = vars.get("date")
def outputDate = Date.parse("yyyy-MM-dd", inputDate).format("dd/MM/yyyy")
vars.put("formatted_date", outputDate)
```

### 3. Signature Calculation
```groovy
// Calculate API signature
def payload = vars.get("user_id") + vars.get("timestamp")
def signature = payload.md5()
vars.put("signature", signature)
```

### 4. Conditional Logic
```groovy
// Set parameters based on conditions
def userType = vars.get("user_type")
if (userType == "premium") {
    vars.put("api_version", "v2")
    vars.put("feature_set", "full")
} else {
    vars.put("api_version", "v1")
    vars.put("feature_set", "basic")
}
```

## Built-in Variables

- `vars`: JMeterVariables - access/set JMeter variables
- `props`: Properties - access JMeter properties
- `ctx`: JMeterContext - access current context
- `sampler`: Sampler - access current sampler
- `log`: Logger - write to JMeter log file

## Best Practices

1. **Use Groovy**: Best performance and thread safety
2. **Keep it simple**: Pre-processors run before every sample
3. **Error handling**: Add try-catch for robustness
4. **Cache scripts**: Enable script caching for better performance
5. **Logging**: Use log.info() for debugging

## Error Handling Example

```groovy
try {
    def userId = vars.get("user_id")
    if (userId == null || userId.isEmpty()) {
        log.warn("user_id is null or empty")
        vars.put("user_id", "default_user")
    }
    // Your code here
} catch (Exception e) {
    log.error("Error in pre-processor", e)
    vars.put("error", e.message)
}
```

## Notes

- Executes before the parent sampler
- Good for dynamic parameter generation
- Can modify sampler properties
- Use sparingly as they add overhead
- Script caching improves performance
