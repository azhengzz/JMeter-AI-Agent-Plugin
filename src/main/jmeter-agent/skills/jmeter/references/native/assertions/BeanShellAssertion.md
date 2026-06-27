# BeanShell Assertion
> **Source**: Apache JMeter built-in component (ships with JMeter, no extra install needed)

## Description

The BeanShell Assertion allows the user to perform assertion checking using a BeanShell script.

A different Interpreter is used for each independent occurrence of the assertion in each thread in a test script, but the same Interpreter is used for subsequent invocations. This means that variables persist across calls to the assertion.

All Assertions are called from the same thread as the sampler.

If the property `beanshell.assertion.init` is defined, it is passed to the Interpreter as the name of a sourced file. This can be used to define common methods and variables. There is a sample init file in the `bin` directory: `BeanShellAssertion.bshrc`.

The test element supports the `ThreadListener` and `TestListener` methods. These should be defined in the initialisation file. See the file `BeanShellListeners.bshrc` for example definitions.

> **Note:** Migration to JSR223 Assertion + Groovy is highly recommended for performance, support of new Java features and limited maintenance of the BeanShell library.

### Available Script Variables

Before invoking the script, the following variables are set up in the BeanShell interpreter:

| Variable | Type | Description |
|----------|------|-------------|
| `log` | Logger | The Logger Object. e.g. `log.warn("Message"[,Throwable])` |
| `SampleResult` / `prev` | SampleResult | The SampleResult Object; read-write |
| `Response` | Object | The response Object; read-write |
| `Failure` | boolean | Read-write; used to set the Assertion status |
| `FailureMessage` | String | Read-write; used to set the Assertion message |
| `ResponseData` | byte[] | The response body |
| `ResponseCode` | String | e.g. `200` |
| `ResponseMessage` | String | e.g. `OK` |
| `ResponseHeaders` | String | Contains the HTTP response headers |
| `RequestHeaders` | String | Contains the HTTP headers sent to the server |
| `SampleLabel` | String | The sample label |
| `SamplerData` | String | Data that was sent to the server |
| `ctx` | JMeterContext | The JMeter context |
| `vars` | JMeterVariables | JMeter variables. e.g. `vars.get("VAR1")`, `vars.put("VAR2","value")` |
| `props` | Properties | JMeter properties. e.g. `props.get("START.HMS")`, `props.put("PROP1","1234")` |

## Parameters

| Property | Required | Default | Description | Example |
|----------|----------|---------|-------------|---------|
| `BeanShellAssertion.query` | Yes | — | The BeanShell script to run. The return value is ignored. Set `Failure=true` to mark assertion as failed, and `FailureMessage` to specify the reason. | `if (!ResponseCode.equals("200")){ Failure=true; FailureMessage="Status was not 200"; }` |
| `BeanShellAssertion.filename` | No | `""` | Path to external script file. This overrides the script field. The filename is stored in the script variable `FileName`. | `scripts/check_response.bsh` |
| `BeanShellAssertion.parameters` | No | `""` | Parameters to pass to the BeanShell script. The parameters are stored in: `Parameters` (string containing all parameters) and `bsh.args` (String array split on white-space). | `200 OK` |
| `BeanShellAssertion.resetInterpreter` | No | `false` | If true, the interpreter will be recreated for each sample. This may be necessary for some long-running scripts to avoid memory leaks. | `false` |

## Usage Examples

### Example 1: Check Response Code

```
create_jmeter_element with:
- elementType: "beanshellassertion"
- elementName: "BeanShell断言_检查状态码200"
- properties:
  - BeanShellAssertion.query: "if (!ResponseCode.equals(\"200\")) {\n  Failure = true;\n  FailureMessage = \"Expected 200 but got \" + ResponseCode;\n}"
```

### Example 2: Check Response Body Contains Text

```
create_jmeter_element with:
- elementType: "beanshellassertion"
- elementName: "BeanShell断言_响应包含success"
- properties:
  - BeanShellAssertion.query: "if (!ResponseData.toString().contains(\"success\")) {\n  Failure = true;\n  FailureMessage = \"Response does not contain success\";\n}"
```

### Example 3: Use External Script File with Parameters

```
create_jmeter_element with:
- elementType: "beanshellassertion"
- elementName: "BeanShell断言_外部脚本"
- properties:
  - BeanShellAssertion.filename: "scripts/assert_response.bsh"
  - BeanShellAssertion.parameters: "200 OK"
  - BeanShellAssertion.resetInterpreter: "false"
```

### Example 4: Check JSON Response Field

```
create_jmeter_element with:
- elementType: "beanshellassertion"
- elementName: "BeanShell断言_JSON状态检查"
- properties:
  - BeanShellAssertion.query: "import org.json.JSONObject;\nString response = ResponseData.toString();\nJSONObject json = new JSONObject(response);\nif (!json.getString(\"status\").equals(\"OK\")) {\n  Failure = true;\n  FailureMessage = \"Status was not OK\";\n}"
```

## Best Practices

1. **Migrate to JSR223 Assertion + Groovy** - Better performance and active maintenance compared to BeanShell.
2. **Use external script files** for complex logic to keep test plans clean and enable script reuse.
3. **Set Failure and FailureMessage** explicitly in your script to provide clear assertion results.
4. **Avoid resetting interpreter** unless necessary, as it adds overhead to each sample.
5. **Keep scripts short and simple** - complex logic is harder to debug in BeanShell.

## Notes

- BeanShell Assertion is slower than JSR223 Assertion with Groovy compilation caching.
- Variables persist across calls to the assertion within the same thread (unless resetInterpreter is true).
- The script return value is ignored; use `Failure` and `FailureMessage` to control assertion outcome.
- If the property `beanshell.assertion.init` is defined, it is used as an init file for all BeanShell Assertions.
