# JSR223 Post-Processor

## Description

JSR223 Post-Processor executes code after a sampler completes. It's commonly used to extract data, modify variables, or perform conditional logic based on response data.

## Parameters

| Property | Required | Description | Example |
|----------|----------|-------------|---------|
| `scriptLanguage` | Yes | Scripting language | `groovy` |
| `script` | Yes* | Script code to execute | See examples below |
| `scriptFile` | Yes* | Path to external script file | `/path/to/script.groovy` |

## Usage Examples

### Example 1: Extract Data from JSON Response

```
create_jmeter_element with:
- elementType: "jsr223postprocessor"
- elementName: "解析JSON响应"
- properties:
  - scriptLanguage: "groovy"
  - script: |
    import groovy.json.JsonSlurper

    def response = prev.getResponseDataAsString()
    def json = new JsonSlurper().parseText(response)

    vars.put("user_id", json.data.userId.toString())
    vars.put("user_name", json.data.userName)
```

### Example 2: Cross-Thread Variable Sharing

```
create_jmeter_element with:
- elementType: "jsr223postprocessor"
- elementName: "保存Token到属性"
- properties:
  - scriptLanguage: "groovy"
  - script: |
    def token = vars.get("access_token")
    props.put("shared_token", token)

// In another thread group, use:
// ${__P(shared_token)}
```

### Example 3: Conditional Logic

```
create_jmeter_element with:
- elementType: "jsr223postprocessor"
- elementName: "根据响应设置标志"
- properties:
  - scriptLanguage: "groovy"
  - script: |
    def code = prev.getResponseCode()

    if (code == "200") {
        vars.put("request_success", "true")
        vars.put("retry_count", "0")
    } else {
        def retryCount = vars.get("retry_count") as Integer ?: 0
        vars.put("retry_count", (retryCount + 1).toString())
        vars.put("request_success", "false")
    }
```

### Example 4: Calculate Response Metrics

```
create_jmeter_element with:
- elementType: "jsr223postprocessor"
- elementName: "计算响应大小"
- properties:
  - scriptLanguage: "groovy"
  - script: |
    def bytes = prev.getBytesAsLong()
    def sizeKB = bytes / 1024

    vars.put("response_size_kb", String.format("%.2f", sizeKB))

    if (sizeKB > 100) {
        log.warn("Large response detected: " + sizeKB + " KB")
    }
```

### Example 5: Extract Multiple Values with Regex

```
create_jmeter_element with:
- elementType: "jsr223postprocessor"
- elementName: "正则提取多个值"
- properties:
  - scriptLanguage: "groovy"
  - script: |
    def response = prev.getResponseDataAsString()

    def idMatcher = (response =~ /"id"\s*:\s*(\d+)/)
    def nameMatcher = (response =~ /"name"\s*:\s*"([^"]+)"/)

    if (idMatcher.find()) {
        vars.put("extracted_id", idMatcher.group(1))
    }

    if (nameMatcher.find()) {
        vars.put("extracted_name", nameMatcher.group(1))
    }
```

### Example 6: Parse HTML Response

```
create_jmeter_element with:
- elementType: "jsr223postprocessor"
- elementName: "解析HTML"
- properties:
  - scriptLanguage: "groovy"
  - script: |
    import org.jsoup.Jsoup

    def response = prev.getResponseDataAsString()
    def doc = Jsoup.parse(response)

    def title = doc.title()
    def firstLink = doc.select("a").first()?.attr("href")

    vars.put("page_title", title)
    vars.put("first_link", firstLink ?: "")
```

## Built-in Variables

### Response Access
- `prev`: SampleResult - access previous sample result
- `prev.getResponseCode()`: HTTP status code
- `prev.getResponseDataAsString()`: Response body
- `prev.getResponseHeaders()`: Response headers
- `prev.isSuccessful()`: Success status
- `prev.getTime()`: Response time in ms

### Variable Access
- `vars`: JMeterVariables - get/set JMeter variables
- `vars.get(name)`: Get variable value
- `vars.put(name, value)`: Set variable value
- `props`: Properties - access JMeter properties

### Logging
- `log.info(message)`: Info level log
- `log.warn(message)`: Warning level log
- `log.error(message)`: Error level log

## Common Patterns

### Parse JSON
```groovy
import groovy.json.JsonSlurper
def json = new JsonSlurper().parseText(prev.getResponseDataAsString())
vars.put("field", json.field.toString())
```

### Save Token for Other Threads
```groovy
props.put("token", vars.get("access_token"))
// Use in other thread: ${__P(token)}
```

### Conditional Based on Response Code
```groovy
if (prev.getResponseCode() == "200") {
    vars.put("success", "true")
} else {
    vars.put("success", "false")
}
```

### Extract Header Value
```groovy
def contentType = prev.getResponseHeaders().find { it =~ /Content-Type/i }
vars.put("content_type", contentType?.split(":")[1]?.trim())
```

### Calculate Checksum
```groovy
def data = prev.getResponseDataAsString()
def checksum = data.md5()
vars.put("response_checksum", checksum)
```

## Best Practices

1. **Use Groovy**: Best performance with JSR223
2. **Cache scripts**: Enable script caching
3. **Error handling**: Add try-catch blocks
4. **Logging**: Use log.warn/error for important events
5. **Type conversion**: Convert types explicitly (toString(), toInteger())

## Error Handling Example

```groovy
try {
    def response = prev.getResponseDataAsString()
    def json = new JsonSlurper().parseText(response)
    vars.put("user_id", json.id.toString())
} catch (Exception e) {
    log.error("Failed to parse response", e)
    vars.put("user_id", "error")
}
```

## Notes

- Executes after parent sampler completes
- Has access to response data and headers
- Can modify JMeter variables
- Use for complex extraction logic
- Groovy is recommended for performance
