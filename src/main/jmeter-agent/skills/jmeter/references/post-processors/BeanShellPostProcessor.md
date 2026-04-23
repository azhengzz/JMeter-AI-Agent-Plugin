# BeanShell Post-Processor

## Description
The BeanShell PostProcessor allows arbitrary code to be applied after taking a sample.

BeanShell Post-Processor no longer ignores samples with zero-length result data.

**Note:** Migration to JSR223 PostProcessor+Groovy is highly recommended for performance, support of new Java features and limited maintenance of the BeanShell library.

The test element supports the `ThreadListener` and `TestListener` methods. These should be defined in the initialisation file. See the file `BeanShellListeners.bshrc` for example definitions.

## Parameters
| Property | Required | Default | Description | Example |
|----------|----------|---------|-------------|---------|
| `parameters` | No | `""` | Parameters to pass to the BeanShell script. Available as `Parameters` string and `bsh.args` array. | `"value1 value2"` |
| `filename` | No | `""` | A file containing the BeanShell script to run. The file name is stored in the script variable `FileName`. | `"/scripts/extract.bsh"` |
| `resetInterpreter` | No | `false` | If selected, the BeanShell interpreter will be recreated for each sample. This may be necessary for some long running scripts. | `"false"` |
| `script` | No | `""` | The BeanShell script to run. The return value is ignored. Required if filename is not set. | `"vars.put(\"key\", \"value\")"` |

## Usage Examples
### Example 1: Extract Data from Response
```
create_jmeter_element with:
- elementType: "beanshellpostprocessor"
- elementName: "提取响应数据"
- properties:
  - script: "String response = new String(data); vars.put(\"response_body\", response);"
```

### Example 2: Use External Script File
```
create_jmeter_element with:
- elementType: "beanshellpostprocessor"
- elementName: "使用外部脚本处理"
- properties:
  - filename: "/scripts/post-process.bsh"
  - parameters: "userId=${user_id}"
  - resetInterpreter: "false"
```

### Example 3: Conditional Logic Based on Response
```
create_jmeter_element with:
- elementType: "beanshellpostprocessor"
- elementName: "根据响应码设置变量"
- properties:
  - script: |
      String code = prev.getResponseCode();
      if (code.equals("200")) {
          vars.put("request_success", "true");
      } else {
          vars.put("request_success", "false");
          vars.put("error_reason", prev.getResponseMessage());
      }
```

## Best Practices
1. **Use JSR223 + Groovy instead**: Better performance, thread safety, and active maintenance
2. **Reset interpreter when needed**: Only enable `resetInterpreter` if scripts have state issues
3. **Use external files for complex scripts**: Easier to maintain and debug
4. **Handle exceptions**: Wrap code in try-catch blocks for robustness
5. **Keep scripts simple**: BeanShell has limited Java syntax support

## Notes
- BeanShell interpreter may have thread safety issues; use `resetInterpreter` to isolate
- Scripts execute after the sampler but before assertions
- The `data` variable contains raw response bytes
- The `prev` variable provides full access to the previous SampleResult
- The `log` variable can be used for logging output
- For new scripts, strongly consider using JSR223 PostProcessor with Groovy instead
