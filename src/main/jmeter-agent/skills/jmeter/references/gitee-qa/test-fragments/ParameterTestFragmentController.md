# Test Fragment (with Parameters)
> **Source**: Gitee QA extension (third-party plugin `com.gitee.qa.jmeter`, requires the corresponding plugin)

## Description

An enhanced Test Fragment that defines input parameters and return values, enabling reusable parameterized test modules. It works in conjunction with the **Include Controller (with Parameters)** which references this fragment and provides actual parameter values.

This component defines the "contract" for a reusable module:
- **Input parameters**: Define what data the fragment expects, including default values, descriptions, and whether each parameter is required or must be non-empty
- **Return values**: Define what output variables the fragment produces, which can be mapped back to the calling scope

## Parameters

| Parameter | Type | Required | Default | Description |
|-----------|------|----------|---------|-------------|
| ParameterTestFragmentController.arguments | Object (Array) | No | - | Input parameter definitions. Each item defines name, default value, description, required flag, and notNull flag |
| ParameterTestFragmentController.ReturnValueArguments | Object (Array) | No | - | Return value definitions. Each item defines the variable name and description of what the fragment outputs |

## Input Parameter Definition Structure

Each item in `ParameterTestFragmentController.arguments`:

| Field | Type | Description |
|-------|------|-------------|
| Argument.name | String | Parameter name |
| Argument.value | String | Default value |
| Argument.desc | String | Parameter description |
| ParameterIncludeControllerArgument.required | Boolean | Whether the Include Controller must provide this parameter |
| ParameterIncludeControllerArgument.notNull | Boolean | Whether the provided value must be non-empty |

## Return Value Definition Structure

Each item in `ParameterTestFragmentController.ReturnValueArguments`:

| Field | Type | Description |
|-------|------|-------------|
| Argument.name | String | Output variable name |
| Argument.desc | String | Description of the return value |

## Usage Examples

### Example 1: Login Module Fragment

```
// In a dedicated JMX file (e.g., modules/login.jmx)
create_jmeter_element with:
- elementType: "parametertestfragmentcontroller"
- elementName: "Login Module"
- properties:
    ParameterTestFragmentController.arguments:
      - Argument.name: "username"
        Argument.value: ""
        Argument.desc: "Login username"
        ParameterIncludeControllerArgument.required: true
        ParameterIncludeControllerArgument.notNull: true
      - Argument.name: "password"
        Argument.value: ""
        Argument.desc: "Login password"
        ParameterIncludeControllerArgument.required: true
        ParameterIncludeControllerArgument.notNull: true
    ParameterTestFragmentController.ReturnValueArguments:
      - Argument.name: "auth_token"
        Argument.desc: "Authentication token from login response"
```

### Example 2: Data Preparation Fragment

```
create_jmeter_element with:
- elementType: "parametertestfragmentcontroller"
- elementName: "Create Order Module"
- properties:
    ParameterTestFragmentController.arguments:
      - Argument.name: "product_id"
        Argument.value: ""
        Argument.desc: "Product ID to order"
        ParameterIncludeControllerArgument.required: true
        ParameterIncludeControllerArgument.notNull: true
      - Argument.name: "quantity"
        Argument.value: "1"
        Argument.desc: "Order quantity (default: 1)"
        ParameterIncludeControllerArgument.required: false
        ParameterIncludeControllerArgument.notNull: false
    ParameterTestFragmentController.ReturnValueArguments:
      - Argument.name: "order_id"
        Argument.desc: "Created order ID"
      - Argument.name: "order_status"
        Argument.desc: "Initial order status"
```

## Best Practices

1. **Save as separate JMX files**: Each parameterized fragment should be in its own JMX file under a `modules/` directory
2. **Define all parameters explicitly**: Include name, default value, description, required, and notNull for each input parameter
3. **Mark required parameters**: Use `ParameterIncludeControllerArgument.required: true` for parameters that must be provided by the Include Controller
4. **Provide meaningful descriptions**: Descriptions help users understand what each parameter and return value means
5. **Use default values wisely**: Set reasonable defaults for optional parameters so the module works out of the box
6. **Document return values**: List all variables that the fragment sets and intends to return

## Notes

- This component extends `TestFragmentController` and is disabled by default in the GUI (fragments are not executed directly)
- Input parameters with `required: true` will cause a runtime error if the Include Controller does not provide them
- Parameters with `ParameterIncludeControllerArgument.notNull: true` will log an error if the Include Controller passes an empty value
- The fragment is referenced by Include Controller (with Parameters) via the JMX file path
- This is a Gitee QA extension component and requires the corresponding plugin to be installed
