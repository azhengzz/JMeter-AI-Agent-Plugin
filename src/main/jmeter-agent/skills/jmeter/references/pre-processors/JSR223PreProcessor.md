# JSR223 Pre-Processor

## Description

The JSR223 PreProcessor allows JSR223 script code to be applied before taking a sample. Groovy is the recommended scripting language for best performance, support of new Java features, and script compilation caching.

## Parameters

| Property | Required | Default | Description | Example |
|----------|----------|---------|-------------|---------|
| `scriptLanguage` | Yes | `"groovy"` | The JSR223 language to be used. Groovy is strongly recommended for best performance. | `"groovy"` |
| `parameters` | No | `""` | Parameters to pass to the script. The parameters are stored in the following variables: `Parameters` (string containing the parameters as a single variable) and `args` (String array containing parameters, split on white-space). | `"param1 param2"` |
| `filename` | No | `""` | A file containing the script to run. If a relative file path is used, then it will be relative to directory referenced by `user.dir` System property. Required if `script` is not set. | `"/scripts/setup.groovy"` |
| `cacheKey` | No | `"true"` | Unique string across Test Plan that JMeter will use to cache result of Script compilation if language used supports Compilable interface (Groovy supports this; java, beanshell and javascript do not). `"true"` = enabled, `"false"` = disabled. | `"true"` |
| `script` | No | `""` | The script to run. Required if `filename` is not set. | See examples |

### scriptLanguage Enum Values

| Value | Description |
|-------|-------------|
| `groovy` | Groovy scripting language (recommended). Supports script compilation caching for best performance. |
| `beanshell` | BeanShell scripting language. Limited performance; use only for legacy compatibility. |
| `bsh` | Alias for BeanShell. |
| `java` | Java scripting language. Does not support compilation caching. |
| `jexl` | JEXL expression language (version 1). |
| `jexl2` | JEXL expression language (version 2). |

### cacheKey Enum Values

| Value | Description |
|-------|-------------|
| `true` | Enable script compilation caching (recommended for Groovy) |
| `false` | Disable script compilation caching |

## Usage Examples

### Example 1: Generate Timestamp

```
create_jmeter_element with:
- elementType: "jsr223preprocessor"
- elementName: "生成时间戳"
- properties:
  - scriptLanguage: "groovy"
  - cacheKey: "true"
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
  - cacheKey: "true"
  - script: |
    import java.security.MessageDigest
    def data = vars.get("user_id") + vars.get("timestamp") + "SECRET_KEY"
    def digest = MessageDigest.getInstance("SHA-256").digest(data.bytes)
    def checksum = digest.encodeHex().toString()
    vars.put("request_checksum", checksum)
```

### Example 3: Prepare JSON Body

```
create_jmeter_element with:
- elementType: "jsr223preprocessor"
- elementName: "构建JSON请求体"
- properties:
  - scriptLanguage: "groovy"
  - cacheKey: "true"
  - parameters: "v2 premium"
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

### Example 4: Use External Script File

```
create_jmeter_element with:
- elementType: "jsr223preprocessor"
- elementName: "外部脚本初始化"
- properties:
  - scriptLanguage: "groovy"
  - filename: "scripts/init_data.groovy"
  - cacheKey: "true"
```

## Best Practices

1. **Use Groovy**: Best performance and thread safety. Groovy supports the Compilable interface for script caching.
2. **Enable script caching**: Set `cacheKey` to `"true"` to cache compiled scripts for significantly better performance
3. **Keep scripts simple**: Pre-processors run before every sample; avoid heavy computation
4. **Use error handling**: Add try-catch blocks for robustness in production tests
5. **Use logging**: Use `log.info()` for debugging and `log.error()` for error tracking

## Notes

- Executes before the parent sampler
- Groovy supports script compilation caching (via `cacheKey`), which greatly improves performance for repeated executions
- java, beanshell, and javascript languages do NOT support compilation caching
- The following JSR223 variables are set up for use by the script: `log` (Logger), `Label` (String), `FileName` (String), `Parameters` (String), `args` (String[]), `ctx` (JMeterContext), `vars` (JMeterVariables), `props` (JMeterProperties), `sampler` (Sampler), `OUT` (System.out)
- Can modify sampler properties and set JMeter variables before the sample executes
- Use sparingly as they add overhead to each sample execution
