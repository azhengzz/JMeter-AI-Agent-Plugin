# Include Controller (with Parameters)

## Description

An enhanced Include Controller that supports passing input parameters to the included Test Fragment and receiving return values back. It works in conjunction with the **Test Fragment (with Parameters)** component to enable reusable, parameterized test modules.

When the controller executes, it:
1. Validates required parameters are provided
2. Backs up current thread variables
3. Injects input parameters as JMeter variables
4. Executes the included Test Fragment
5. Extracts return value variables and restores the original variable context

## Parameters

| Parameter | Type | Required | Default | Description |
|-----------|------|----------|---------|-------------|
| ParameterIncludeController.includepath | String | Yes | - | Path to the JMX file containing a Test Fragment (with Parameters) |

**Note:** Input parameters (`Arguments`) and return value mappings (`ReturnValueArguments`) are automatically populated via the "Update Parameters" button in the GUI after setting the include path. They are synced from the referenced Test Fragment's definitions.

## Usage Examples

### Example 1: Include a Login Module

```
create_jmeter_element with:
- elementType: "parameterincludecontroller"
- elementName: "Call Login Module"
- properties:
    ParameterIncludeController.includepath: "${currentJmxDir}${fileSep}modules${fileSep}login.jmx"
```

### Example 2: Reusable Data Preparation Module

```
// Module file: modules/prepare_data.jmx
// Contains a "Test Fragment (with Parameters)" with:
//   Input params: env, data_type
//   Return values: prepared_data_id

create_jmeter_element with:
- elementType: "parameterincludecontroller"
- elementName: "Prepare Test Data"
- properties:
    ParameterIncludeController.includepath: "${currentJmxDir}${fileSep}modules${fileSep}prepare_data.jmx"
```

## Best Practices

1. **Pair with Test Fragment (with Parameters)**: Always reference a JMX file that contains a `ParameterTestFragmentController`, not a standard `TestFragmentController`
2. **Use ${currentJmxDir}**: Use `${currentJmxDir}${fileSep}` for relative paths to keep scripts portable
3. **Click "Update Parameters" after setting path**: The GUI will sync input parameters and return values from the referenced Test Fragment
4. **Fill in parameter values**: After syncing, edit the auto-populated parameter table to provide actual values
5. **Keep modules in a dedicated folder**: Organize reusable fragments in a `modules/` directory

## Notes

- Input parameters and return value mappings are auto-populated from the referenced Test Fragment via the GUI "Update Parameters" button
- Variable scope is isolated: after the controller finishes, the original variable context is restored
- Only variables explicitly mapped in return values are preserved in the calling scope
- Required parameter validation occurs at the start of execution; missing required parameters throw an error
- Non-empty validation logs a warning but does not stop execution
- This is a Gitee QA extension component and requires the corresponding plugin to be installed
