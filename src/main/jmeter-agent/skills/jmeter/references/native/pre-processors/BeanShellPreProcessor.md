# BeanShell Pre-Processor
> **Source**: Apache JMeter built-in component (ships with JMeter, no extra install needed)

## Description

The BeanShell PreProcessor allows arbitrary code to be applied before taking a sample. For full details on using BeanShell, please see the BeanShell website.

> Migration to JSR223 PreProcessor+Groovy is highly recommended for performance, support of new Java features and limited maintenance of the BeanShell library.

The test element supports the ThreadListener and TestListener methods. These should be defined in the initialisation file. See the file `BeanShellListeners.bshrc` for example definitions.

## Parameters

| Property | Required | Default | Description | Example |
|----------|----------|---------|-------------|---------|
| `resetInterpreter` | No | `false` | If selected, the BeanShell interpreter will be recreated for each sample. This may be necessary for some long running scripts. For further information, see Best Practices - BeanShell scripting. | `"false"` |
| `parameters` | No | `""` | Parameters to pass to the BeanShell script. The parameters are stored in the following variables: `Parameters` (string containing the parameters as a single variable) and `bsh.args` (String array containing parameters, split on white-space). | `"param1 param2"` |
| `filename` | No | `""` | A file containing the BeanShell script to run. The file name is stored in the script variable `FileName`. Required if `script` is not set. | `"/scripts/setup.bsh"` |
| `script` | No | `""` | The BeanShell script. The return value is ignored. Required if `filename` is not set. | See examples |

## Usage Examples

### Example 1: Generate Timestamp

```
create_jmeter_element with:
- elementType: "beanshellpreprocessor"
- elementName: "生成时间戳"
- properties:
  - script: |
    import java.util.Date;
    String timestamp = new Date().toString();
    vars.put("request_timestamp", timestamp);
```

### Example 2: Prepare Request Data with Parameters

```
create_jmeter_element with:
- elementType: "beanshellpreprocessor"
- elementName: "准备请求数据"
- properties:
  - parameters: "prefix base_url"
  - script: |
    String prefix = bsh.args[0];
    String orderId = prefix + "-" + System.currentTimeMillis();
    vars.put("order_id", orderId);
```

### Example 3: Use External Script File

```
create_jmeter_element with:
- elementType: "beanshellpreprocessor"
- elementName: "外部脚本初始化"
- properties:
  - filename: "scripts/init_data.bsh"
  - resetInterpreter: "false"
```

### Example 4: Calculate Checksum

```
create_jmeter_element with:
- elementType: "beanshellpreprocessor"
- elementName: "计算请求签名"
- properties:
  - script: |
    import java.security.MessageDigest;
    String data = vars.get("user_id") + vars.get("timestamp") + "SECRET_KEY";
    MessageDigest md = MessageDigest.getInstance("MD5");
    byte[] digest = md.digest(data.getBytes());
    StringBuilder sb = new StringBuilder();
    for (byte b : digest) {
        sb.append(String.format("%02x", b));
    }
    vars.put("request_checksum", sb.toString());
```

## Best Practices

1. **Prefer JSR223 PreProcessor with Groovy**: Better performance, thread safety, and ongoing maintenance. Migration from BeanShell is highly recommended.
2. **Keep scripts simple**: BeanShell has performance limitations compared to Groovy
3. **Avoid resetInterpreter**: Causes significant performance degradation; only enable for long-running scripts that require it
4. **Use semicolons**: BeanShell requires proper Java syntax with semicolons
5. **Cast explicitly**: Variables from JMeter context may need explicit type casting

## Notes

- BeanShell is being phased out in favor of JSR223 with Groovy
- Executes before the parent sampler
- Not thread-safe by default
- Script caching is not available (unlike JSR223)
- If the property `beanshell.preprocessor.init` is defined, it is used to load an initialisation file for defining methods etc.
- The following variables are set up before the script runs: `log` (Logger), `ctx` (JMeterContext), `vars` (JMeterVariables), `props` (JMeterProperties), `prev` (SampleResult), `sampler` (Sampler), `Label` (String), `FileName` (String), `Parameters` (String), `bsh.args` (String[])
