# JSR223 Post-Processor
> **Source**: Apache JMeter built-in component (ships with JMeter, no extra install needed)

## Description
The JSR223 PostProcessor allows JSR223 script code to be applied after taking a sample.

## Parameters
| Property | Required | Default | Description | Example |
|----------|----------|---------|-------------|---------|
| `scriptLanguage` | Yes | `"groovy"` | The JSR223 language to be used. See Language options below. | `"groovy"` |
| `parameters` | No | `""` | Parameters to pass to the script. Available as `Parameters` string and `args` String array (split on whitespace). | `"value1 value2"` |
| `filename` | No | `""` | A file containing the script to run. If a relative file path is used, it will be relative to directory referenced by `user.dir` System property. | `"/scripts/process.groovy"` |
| `cacheKey` | No | `"true"` | Unique string for script compilation caching. If language supports `Compilable` interface (Groovy does), the compiled script will be cached. Set to `"true"` to enable. | `"true"` |
| `script` | No | `""` | The script to run. Required unless a script file is provided. | `"vars.put(\"key\", \"value\")"` |

### Language Options
| Value | Description |
|-------|-------------|
| `groovy` | Groovy (recommended - best performance, supports `Compilable`) |
| `beanshell` | BeanShell |
| `bsh` | BeanShell (alias) |
| `java` | Java |
| `jexl` | JEXL |
| `jexl2` | JEXL2 |

### Cache Key Options
| Value | Description |
|-------|-------------|
| `true` | Enable script compilation caching (recommended for Groovy) (default)|
| `false` | Disable caching |

## Usage Examples
### Example 1: Extract Data from JSON Response
```
create_jmeter_element with:
- elementType: "jsr223postprocessor"
- elementName: "解析JSON响应"
- properties:
  - scriptLanguage: "groovy"
  - cacheKey: "true"
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

### Example 3: Conditional Logic Based on Response Code
```
create_jmeter_element with:
- elementType: "jsr223postprocessor"
- elementName: "根据响应设置标志"
- properties:
  - scriptLanguage: "groovy"
  - cacheKey: "true"
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

### Example 4: Use External Script File
```
create_jmeter_element with:
- elementType: "jsr223postprocessor"
- elementName: "使用外部脚本"
- properties:
  - scriptLanguage: "groovy"
  - filename: "/scripts/extract-data.groovy"
  - parameters: "userId=${user_id}"
  - cacheKey: "true"
```

## Best Practices
1. **Use Groovy**: Best performance among JSR223 languages, supports `Compilable` interface
2. **Enable caching**: Set `cacheKey` to `"true"` for Groovy scripts to compile and cache for better performance
3. **Error handling**: Wrap code in try-catch blocks for robustness
4. **Use external files**: For complex scripts, use `filename` for easier maintenance
5. **Explicit type conversion**: Use `.toString()`, `as Integer`, etc. for type safety

## Notes
- Groovy is strongly recommended over other scripting languages for best performance
- When using Groovy without script compilation caching, see JSR223 Sampler Java System property notes
- The `Parameters` variable contains the full parameter string; `args` is the parameter string split on whitespace
- Scripts execute after the parent sampler completes
- Has access to response data, headers, variables (`vars`), properties (`props`), and the logger (`log`)
