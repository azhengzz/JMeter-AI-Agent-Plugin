# Test Fragment Controller

## Description

The Test Fragment is a special controller that acts as a non-executable container in the test plan. It is not executed directly by thread groups — instead, its contents are referenced and executed by Module Controllers or Include Controllers.

Test Fragments are used to organize reusable test modules (login flows, search scenarios, checkout processes, etc.) in a dedicated section of the test plan. This promotes modularity and avoids duplication.

Key characteristics:
- Test Fragments are placed at the top level of the test plan, outside of Thread Groups
- They are not executed during a test run unless referenced by a Module Controller or Include Controller
- Multiple Module Controllers can reference the same Test Fragment
- Config elements attached to the referencing Module Controller can alter the fragment's behavior

## Parameters

This component has no configurable properties. It serves purely as an organizational container.

## Usage Examples

### Example 1: Define a Reusable Login Module

```
// 1. Create a Test Fragment to hold the login flow
create_jmeter_element with:
- elementType: "testfragmentcontroller"
- elementName: "登录模块"

// 2. Add a Simple Controller under the Test Fragment
create_jmeter_element with:
- elementType: "simplecontroller"
- elementName: "登录流程"
// Add HTTP samplers: GET login page, POST credentials

// 3. Reference from a Module Controller in a Thread Group
create_jmeter_element with:
- elementType: "modulecontroller"
- elementName: "调用登录模块"
```

### Example 2: Multiple Fragments for Different Business Flows

```
// Test Fragment for search functionality
create_jmeter_element with:
- elementType: "testfragmentcontroller"
- elementName: "搜索模块"

// Test Fragment for order placement
create_jmeter_element with:
- elementType: "testfragmentcontroller"
- elementName: "下单模块"

// Test Fragment for payment
create_jmeter_element with:
- elementType: "testfragmentcontroller"
- elementName: "支付模块"
```

### Example 3: Use with Include Controller

```
// In an external JMX file (e.g., fragments/auth.jmx):
create_jmeter_element with:
- elementType: "testfragmentcontroller"
- elementName: "认证模块"
// Add authentication samplers as children...

// In the main test plan:
create_jmeter_element with:
- elementType: "includecontroller"
- elementName: "包含认证模块"
- properties:
  - IncludeController.includepath: "fragments/auth.jmx"
```

## Best Practices

1. **Name fragments by business function**: Use names like `登录模块`, `搜索流程`, `下单模块` for clarity
2. **One fragment per use case**: Keep each Test Fragment focused on a single business scenario
3. **Use Simple Controllers inside**: Wrap related samplers in a Simple Controller within the Test Fragment for better organization
4. **Parameterize with config elements**: Attach User Defined Variables or CSV Data Set Config at the Module Controller level to customize fragment behavior per usage
5. **Keep fragments independent**: Avoid dependencies between fragments so they can be reused independently

## Notes

- Test Fragments are not executed during a test run unless explicitly referenced by a Module Controller or Include Controller
- Test Fragments are placed at the test plan level, not inside Thread Groups
- The Test Fragment is disabled by default when created through the GUI (`setEnabled(false)`)
- When used with Include Controller, the external JMX file must contain a Test Fragment as the root container
- Module Controllers can reference any controller, but using Test Fragments as the container is the recommended pattern
