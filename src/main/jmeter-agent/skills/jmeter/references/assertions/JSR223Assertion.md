# JSR223 Assertion

## Description

The JSR223 Assertion allows the user to perform assertion checking using a JSR223 scripting language (Groovy, BeanShell, etc.).

Groovy is the recommended scripting language due to its performance advantage through compilation caching. The script can access the sample result and set the assertion outcome via the `AssertionResult` object.

The JSR223 test elements have a feature (compilation) that can significantly increase performance. To benefit from this feature:
- Use Script files instead of inlining them. This will make JMeter compile them if this feature is available on ScriptEngine and cache them.
- Or Use Script Text and check `Cache compiled script if available` property.

**Note:** When using this feature, ensure your script code does not use JMeter variables or JMeter function calls directly in script code as caching would only cache first replacement. Instead use script parameters.

**Note:** To benefit from caching and compilation, the language engine used for scripting must implement JSR223 `Compilable` interface (Groovy is one of these, java, beanshell and javascript are not).

Cache size is controlled by the JMeter property: `jsr223.compiled_scripts_cache_size=100`

## Parameters

| Property | Required | Default | Description | Example |
|----------|----------|---------|-------------|---------|
| `scriptLanguage` | Yes | `"groovy"` | Name of the JSR223 scripting language to be used. See Language Values below. | `"groovy"` |
| `script` | No | `""` | Script to be passed to JSR223 language. Required unless script file is provided. Use `AssertionResult.setFailure(true)` and `AssertionResult.setFailureMessage("msg")` to report failures. | See examples |
| `parameters` | No | `""` | Parameters to be passed to the script file or the script. Available as `Parameters` (string) and `args` (String array). | `"200 OK"` |
| `cacheKey` | No | `"true"` | If checked and the language supports `Compilable` interface (Groovy supports this), JMeter will compile the Script and cache it. `"true"`=enabled, `"false"`=disabled. | `"true"` |
| `filename` | No | `""` | Path to an external script file. If set, overrides the inline script. If relative, it is relative to `user.dir` system property. | `"/scripts/assert_response.groovy"` |

### Language Values

| Value | Description |
|-------|-------------|
| `groovy` | Groovy (recommended - supports compilation) |
| `beanshell` | BeanShell |
| `bsh` | BeanShell (alias) |
| `java` | Java |
| `jexl` | JEXL expression language |
| `jexl2` | JEXL2 expression language |

## Built-in Variables

| Variable | Type | Description |
|----------|------|-------------|
| `log` | Logger | The Logger for logging |
| `Label` | String | The Sampler label |
| `FileName` | String | The script file name (if used) |
| `Parameters` | String | Parameters as a single string |
| `args` | String[] | Parameters as a String array |
| `ctx` | JMeterContext | Current JMeter context |
| `vars` | JMeterVariables | JMeter variables |
| `props` | JMeterProperties | JMeter properties |
| `OUT` | PrintStream | System.out for debugging |
| `sampler` | Sampler | Current Sampler object |
| `prev` / `SampleResult` | SampleResult | Current sample result (read-write) |
| `AssertionResult` | AssertionResult | Assertion result object — use `setFailure(true)` and `setFailureMessage("msg")` to report failures |

## Usage Examples

### Example 1: Check Response Code

```
create_jmeter_element with:
- elementType: "jsr223assertion"
- elementName: "JSR223断言_检查状态码200"
- properties:
  - scriptLanguage: "groovy"
  - cacheKey: "true"
  - script: |
    if (!prev.getResponseCode().equals("200")) {
      AssertionResult.setFailure(true)
      AssertionResult.setFailureMessage("Expected 200 but got " + prev.getResponseCode())
    }
```

### Example 2: Validate JSON Response Field

```
create_jmeter_element with:
- elementType: "jsr223assertion"
- elementName: "JSR223断言_验证JSON字段"
- properties:
  - scriptLanguage: "groovy"
  - cacheKey: "true"
  - script: |
    import groovy.json.JsonSlurper
    def response = new JsonSlurper().parseText(prev.getResponseDataAsString())
    if (response.code != 0) {
      AssertionResult.setFailure(true)
      AssertionResult.setFailureMessage("Expected code=0 but got code=" + response.code)
    }
```

### Example 3: Check Response Contains Text

```
create_jmeter_element with:
- elementType: "jsr223assertion"
- elementName: "JSR223断言_响应包含success"
- properties:
  - scriptLanguage: "groovy"
  - cacheKey: "true"
  - script: |
    if (!prev.getResponseDataAsString().contains("success")) {
      AssertionResult.setFailure(true)
      AssertionResult.setFailureMessage("Response does not contain 'success'")
    }
```

### Example 4: Use External Script File with Parameters

```
create_jmeter_element with:
- elementType: "jsr223assertion"
- elementName: "JSR223断言_外部脚本"
- properties:
  - scriptLanguage: "groovy"
  - filename: "scripts/assert_response.groovy"
  - parameters: "200 OK"
  - cacheKey: "true"
```

## Best Practices

1. **Use Groovy**: Best performance with JSR223 + Groovy due to compilation support
2. **Enable caching**: Set `cacheKey` to `"true"` for better performance
3. **Use script parameters**: Pass dynamic values via `parameters` instead of embedding JMeter variables directly in script code
4. **Use AssertionResult**: Always use `AssertionResult.setFailure(true)` and `setFailureMessage()` to report assertion failures clearly
5. **Use external script files**: For complex or reusable assertion logic

## Notes

- If a script file is supplied, that will be used, otherwise the inline script will be used
- JMeter processes function and variable references before passing the script field to the interpreter, so the references will only be resolved once
- Variable and function references in script files will be passed verbatim, which is likely to cause a syntax error. Use `props` methods instead
- BeanShell (bsh.engine.BshScriptEngine) is explicitly excluded from compilation caching due to bugs
- All cached scripts are invalidated at test end
