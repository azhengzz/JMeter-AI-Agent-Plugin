# Include Controller

## Description

The include controller is designed to use an external JMX file. To use it, create a Test Fragment underneath the Test Plan and add any desired samplers, controllers etc. below it. Then save the Test Plan. The file is now ready to be included as part of other Test Plans.

For convenience, a Thread Group can also be added in the external JMX file for debugging purposes. A Module Controller can be used to reference the Test Fragment. The Thread Group will be ignored during the include process.

If the test uses a Cookie Manager or User Defined Variables, these should be placed in the top-level test plan, not the included file, otherwise they are not guaranteed to work.

If the file cannot be found at the location given by `prefix` + `Filename`, then the controller attempts to open the `Filename` relative to the JMX launch directory.

## Parameters

| Property | Required | Default | Description | Example |
|----------|----------|---------|-------------|---------|
| `IncludeController.includepath` | No | `""` | Path to the external JMX file to include. The included file must contain a TestFragment element, which will be inserted at this controller's location. The path can be absolute or relative to the test plan location. | `fragments/login.jmx` |

## Usage Examples

### Example 1: Include External Test Fragment

```
create_jmeter_element with:
- elementType: "includecontroller"
- elementName: "包含登录模块"
- properties:
  - IncludeController.includepath: "fragments/login.jmx"
```

### Example 2: Include with Absolute Path

```
create_jmeter_element with:
- elementType: "includecontroller"
- elementName: "包含公共认证模块"
- properties:
  - IncludeController.includepath: "C:/jmeter/fragments/auth-module.jmx"
```

### Example 3: Include Multiple Fragments

```
create_jmeter_element with:
- elementType: "includecontroller"
- elementName: "包含注册流程"
- properties:
  - IncludeController.includepath: "fragments/register.jmx"

create_jmeter_element with:
- elementType: "includecontroller"
- elementName: "包含支付流程"
- properties:
  - IncludeController.includepath: "fragments/payment.jmx"
```

## Best Practices

1. **Use Test Fragments**: Always create a Test Fragment in the external JMX file as the root container for included elements
2. **Use relative paths**: Prefer relative paths for portability across environments
3. **Unique controller names**: When including the same JMX file multiple times, ensure each Include Controller has a unique name to avoid known issue (Bug 50898)
4. **Place shared config in main plan**: Cookie Manager and User Defined Variables should be in the top-level test plan, not the included file
5. **No variables in filename**: The filename field does not support JMeter variables or functions; however, the property `includecontroller.prefix` can be used to prefix the pathname

## Notes

- This element does not support variables/functions in the filename field
- If the property `includecontroller.prefix` is defined, the contents are used to prefix the pathname
- When using Include Controller and including the same JMX file, ensure you name the Include Controller differently to avoid facing known issue Bug 50898
- A Thread Group in the external JMX file is ignored during the include process (only Test Fragments are loaded)
- A Module Controller can be used to reference Test Fragments within included files
