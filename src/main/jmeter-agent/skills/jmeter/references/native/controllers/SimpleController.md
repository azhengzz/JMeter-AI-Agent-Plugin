# Simple Controller
> **Source**: Apache JMeter built-in component (ships with JMeter, no extra install needed)

## Description

The Simple Logic Controller lets you organize your Samplers and other Logic Controllers. Unlike other Logic Controllers, this controller provides no functionality beyond that of a storage device.

## Parameters

This controller has no specific parameters. It only has a descriptive name that is shown in the tree.

## Usage Examples

### Example 1: Group Related Requests

```
create_jmeter_element with:
- elementType: "simplecontroller"
- elementName: "用户管理接口组"

// Add child samplers
create_jmeter_element with:
  elementType: "httpsampler"
  elementName: "获取用户列表"

create_jmeter_element with:
  elementType: "httpsampler"
  elementName: "创建用户"

create_jmeter_element with:
  elementType: "httpsampler"
  elementName: "删除用户"
```

### Example 2: Organize Test Plan Sections

```
create_jmeter_element with:
- elementType: "simplecontroller"
- elementName: "登录模块"

create_jmeter_element with:
- elementType: "simplecontroller"
- elementName: "商品浏览模块"

create_jmeter_element with:
- elementType: "simplecontroller"
- elementName: "结算模块"
```

### Example 3: Scope Assertions

```
create_jmeter_element with:
- elementType: "simplecontroller"
- elementName: "需要断言的接口组"

// Add samplers and a Response Assertion scoped to this group
```

## Best Practices

1. **Organize test plans**: Use Simple Controllers to group logically related samplers and controllers
2. **Scope management**: Use Simple Controllers to limit the scope of Assertions, Timers, and Config Elements
3. **Descriptive names**: Use meaningful names that describe the group's purpose
4. **No side effects**: Simple Controller does not affect execution order or logic - all children execute sequentially
5. **Combine with other controllers**: Nest other controllers inside Simple Controllers for organization

## Notes

- The Simple Controller has no effect on how JMeter processes the controllers you add to it
- It is purely an organizational tool for grouping elements in the test plan tree
- All child elements execute in their normal sequential order
- Can be used to limit the scope of Assertions and other test elements when using Transaction Controller in parent mode
