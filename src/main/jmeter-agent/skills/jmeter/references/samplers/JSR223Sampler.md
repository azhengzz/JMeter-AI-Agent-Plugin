# JSR223 Sampler

## Description

The JSR223 Sampler allows JSR223 script code to be used to perform a sample or some computation required to create/update variables.

If you don't want to generate a SampleResult when this sampler is run, call the following method:

```java
SampleResult.setIgnore();
```

This call will have the following impact:
- SampleResult will not be delivered to SampleListeners like View Results Tree, Summariser
- SampleResult will not be evaluated in Assertions nor PostProcessors
- SampleResult will be evaluated to computing last sample status (`${JMeterThread.last_sample_ok}`), and ThreadGroup "Action to be taken after a Sampler error" (since JMeter 5.4)

The JSR223 test elements have a feature (compilation) that can significantly increase performance. To benefit from this feature:
- Use Script files instead of inlining them. This will make JMeter compile them if this feature is available on ScriptEngine and cache them.
- Or Use Script Text and check `Cache compiled script if available` property.

**Note:** When using this feature, ensure your script code does not use JMeter variables or JMeter function calls directly in script code as caching would only cache first replacement. Instead use script parameters.

**Note:** To benefit from caching and compilation, the language engine used for scripting must implement JSR223 `Compilable` interface (Groovy is one of these, java, beanshell and javascript are not).

**Note:** When using Groovy as scripting language and not checking `Cache compiled script if available` (while caching is recommended), you should set this JVM Property `-Dgroovy.use.classvalue=true` due to a Groovy Memory leak.

Cache size is controlled by the JMeter property: `jsr223.compiled_scripts_cache_size=100`

**Note:** Unlike the BeanShell Sampler, the interpreter is not saved between invocations.

**Note:** JSR223 Test Elements using Script file or Script text + checked `Cache compiled script if available` are now compiled if ScriptEngine supports this feature, enabling great performance enhancements.

## Parameters

| Property | Required | Default | Description | Example |
|----------|----------|---------|-------------|---------|
| `scriptLanguage` | Yes | `"groovy"` | Name of the JSR223 scripting language to be used. See Language Values below. There are other languages supported than those in the drop-down list if the appropriate jar is installed in the JMeter lib directory. | `"groovy"` |
| `script` | No | — | Script to be passed to JSR223 language. Required unless script file is provided. | See examples |
| `parameters` | No | — | List of parameters to be passed to the script file or the script. | `"param1,param2"` |
| `cacheKey` | No | `"true"` | If checked (advised) and the language supports `Compilable` interface (Groovy supports this, java/beanshell/javascript do not), JMeter will compile the Script and cache it using its MD5 hash as unique cache key. `"true"`=enabled, `"false"`=disabled. | `"true"` |
| `filename` | No | `""` | Name of a file to be used as a JSR223 script. If a relative file path is used, it will be relative to directory referenced by `user.dir` System property. | `"/scripts/test.groovy"` |

### Language Values

| Value | Description |
|-------|-------------|
| `groovy` | Groovy (recommended - supports compilation) |
| `beanshell` | BeanShell |
| `bsh` | BeanShell (alias) |
| `java` | Java |
| `jexl` | JEXL expression language |
| `jexl2` | JEXL2 expression language |

## Usage Examples

### Example 1: Simple Groovy Script

```
create_jmeter_element with:
- elementType: "jsr223sampler"
- elementName: "生成随机用户ID"
- properties:
  - scriptLanguage: "groovy"
  - cacheKey: "true"
  - script: |
    import java.util.UUID
    String userId = UUID.randomUUID().toString()
    vars.put("user_id", userId)
    SampleResult.setResponseData(userId.getBytes())
```

### Example 2: Using External Script File

```
create_jmeter_element with:
- elementType: "jsr223sampler"
- elementName: "执行脚本文件"
- properties:
  - scriptLanguage: "groovy"
  - filename: "/path/to/script.groovy"
  - cacheKey: "true"
```

### Example 3: Passing Parameters

```
create_jmeter_element with:
- elementType: "jsr223sampler"
- elementName: "带参数的脚本"
- properties:
  - scriptLanguage: "groovy"
  - parameters: "userId=12345,action=query"
  - cacheKey: "true"
  - script: |
    String params = Parameters
    String[] args = args
    log.info("Full parameters: " + params)
```

### Example 4: Make HTTP Request

```
create_jmeter_element with:
- elementType: "jsr223sampler"
- elementName: "调用外部API"
- properties:
  - scriptLanguage: "groovy"
  - cacheKey: "true"
  - script: |
    import groovy.json.JsonSlurper
    def url = "https://api.example.com/users"
    def connection = new URL(url).openConnection()
    connection.setRequestMethod("GET")
    def response = connection.inputStream.text
    vars.put("api_response", response)
    SampleResult.setResponseData(response.getBytes())
```

## Built-in Variables

| Variable | Description |
|----------|-------------|
| `log` | The Logger for logging |
| `Label` | The Sampler label |
| `FileName` | The script file name (if used) |
| `Parameters` | Parameters as a single string |
| `args` | Parameters as a String array |
| `SampleResult` | Current SampleResult object |
| `sampler` | Current Sampler object |
| `ctx` | JMeterContext |
| `vars` | JMeterVariables |
| `props` | JMeterProperties |
| `OUT` | System.out |

## Best Practices

1. **Use Groovy**: Best performance with JSR223 + Groovy due to compilation support
2. **Enable caching**: Set `cacheKey` to `"true"` for better performance
3. **Use script parameters**: Pass dynamic values via `parameters` instead of embedding JMeter variables directly in script code
4. **Reuse code**: Use `filename` for complex scripts
5. **Error handling**: Add try-catch blocks for robustness
6. **Use external script files**: For complex or reusable scripts

## Notes

- If a script file is supplied, that will be used, otherwise the script will be used
- JMeter processes function and variable references before passing the script field to the interpreter, so the references will only be resolved once
- Variable and function references in script files will be passed verbatim to the interpreter, which is likely to cause a syntax error. Use `props` methods instead: `props.get("START.HMS"); props.put("PROP1","1234");`
- Scripts run with the same permissions as JMeter
- Groovy is recommended over other languages for performance and thread safety
