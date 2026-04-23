# BeanShell Sampler

## Description

This sampler allows you to write a sampler using the BeanShell scripting language. For full details on using BeanShell, please see the BeanShell website.

**Note:** Migration to JSR223 Sampler+Groovy is highly recommended for performance, support of new Java features and limited maintenance of the BeanShell library.

The test element supports the `ThreadListener` and `TestListener` interface methods. These must be defined in the initialisation file. See the file `BeanShellListeners.bshrc` for example definitions.

The BeanShell sampler also supports the `Interruptible` interface. The `interrupt()` method can be defined in the script or the init file.

## Parameters

| Property | Required | Default | Description | Example |
|----------|----------|---------|-------------|---------|
| `BeanShellSampler.parameters` | No | `""` | Parameters to pass to the BeanShell script. Stored in `Parameters` string and `bsh.args` array. | `"param1 param2"` |
| `BeanShellSampler.filename` | No | `""` | A file containing the BeanShell script to run. The file name is stored in the script variable `FileName`. | `"/scripts/test.bsh"` |
| `BeanShellSampler.query` | No | `""` | The BeanShell script to run. The return value (if not `null`) is stored as the sampler result. Required if filename is not set. | See examples below |
| `BeanShellSampler.resetInterpreter` | No | `false` | If selected, the BeanShell interpreter will be recreated for each sample. | `"true"` |

## Available Variables

| Variable | Description |
|----------|-------------|
| `SampleResult` | Current SampleResult object |
| `ResponseCode` | HTTP response code (default: "200") |
| `ResponseMessage` | Response message (default: "OK") |
| `IsSuccess` | Success status (default: true) |
| `vars` | JMeterVariables |
| `props` | JMeterProperties |
| `log` | Logger |
| `Parameters` | Parameters string (if parameters provided) |
| `bsh.args` | Parameters array (if parameters provided) |

## Usage Examples

### Example 1: Generate Custom Response

```
create_jmeter_element with:
- elementType: "beanshellsampler"
- elementName: "生成测试数据"
- properties:
  - BeanShellSampler.query: |
    String testData = "id=" + (int)(Math.random() * 1000);
    SampleResult.setResponseData(testData.getBytes());
    ResponseCode = "200";
    IsSuccess = true;
```

### Example 2: Using External Script File

```
create_jmeter_element with:
- elementType: "beanshellsampler"
- elementName: "执行外部脚本"
- properties:
  - BeanShellSampler.filename: "/scripts/my-script.bsh"
  - BeanShellSampler.resetInterpreter: "false"
```

### Example 3: Using Parameters

```
create_jmeter_element with:
- elementType: "beanshellsampler"
- elementName: "带参数的脚本"
- properties:
  - BeanShellSampler.parameters: "delay=1000 retry=3"
  - BeanShellSampler.query: |
    String params = Parameters;
    String[] args = bsh.args;
    print("Full parameters: " + params);
    print("First param: " + args[0]);
```

## Best Practices

1. **Prefer JSR223 + Groovy**: Better performance and thread safety
2. **Use try-catch**: Handle exceptions gracefully
3. **Set response values**: Always set ResponseCode, ResponseMessage, IsSuccess
4. **Return values**: Return String to set response data
5. **Reset interpreter**: Only when needed (has performance impact)

## Notes

- Each Sampler instance has its own BeanShell interpreter, and Samplers are only called from a single thread
- If the property `beanshell.sampler.init` is defined, it is passed to the Interpreter as the name of a sourced file
- Consider migrating to JSR223 for new scripts
