# Module Controller
> **Source**: Apache JMeter built-in component (ships with JMeter, no extra install needed)

## Description

The Module Controller adds modularity to JMeter test plans by referencing a controller defined elsewhere in the test plan (typically under a Test Fragment). At runtime, the Module Controller is replaced by the referenced controller and all its child elements.

This enables reuse of common test scenarios (login, search, checkout, etc.) stored as Simple Controllers or Test Fragments. Multiple Module Controllers can reference the same module, and each can have its own config elements to alter behavior (e.g., different user credentials).

The target controller is identified by its node path in the test plan tree. The path is stored as the `ModuleController.node_path` property.

## Parameters

| Property | Required | Default | Description | Example |
|----------|----------|---------|-------------|---------|
| `ModuleController.node_path` | No | `[]` | The node path to the target controller in the test plan tree. This is a collection of tree node names from root to the target controller. In most cases, this is set automatically by the GUI when selecting a module. | `["Test Plan", "Test Fragment", "Login Module"]` |

## Usage Examples

### Example 1: Reference a Login Module in Test Fragment

```
// 1. Create a Test Fragment with a Simple Controller containing login samplers
create_jmeter_element with:
- elementType: "simplecontroller"
- elementName: "登录模块"
// Add login HTTP samplers as children...

// 2. Create a Module Controller to reference it
create_jmeter_element with:
- elementType: "modulecontroller"
- elementName: "调用登录模块"
// The node_path will be resolved at runtime to point to the "登录模块" controller
```

### Example 2: Multiple References with Different Config

```
// Reference the same search module from different thread groups
// Thread Group 1
create_jmeter_element with:
- elementType: "modulecontroller"
- elementName: "用户A-搜索模块"
// Attach CSV Data Set Config with user A's credentials

// Thread Group 2
create_jmeter_element with:
- elementType: "modulecontroller"
- elementName: "用户B-搜索模块"
// Attach CSV Data Set Config with user B's credentials
```

### Example 3: Module Controller in a Loop

```
create_jmeter_element with:
- elementType: "loopcontroller"
- elementName: "重复执行下单流程"
- properties:
  - LoopController.loops: "5"

  // Add Module Controller as child
  create_jmeter_element with:
  - elementType: "modulecontroller"
  - elementName: "调用下单模块"
```

## Best Practices

1. **Use Test Fragments as module containers**: Place reusable controllers under Test Fragment controllers for clean organization
2. **Descriptive naming**: Give target controllers meaningful names like `登录模块`, `搜索流程` since the node path is resolved by name
3. **Avoid renaming target controllers**: Changing the name of a referenced controller breaks the Module Controller's node path and will cause test failure
4. **No recursive references**: A Module Controller cannot reference another Module Controller (JMeter prevents this to avoid infinite loops)
5. **Attach config elements**: Add config elements (CSV Data Set, User Defined Variables) as children of the Module Controller to parameterize the module's behavior
6. **Verify in GUI**: After creating Module Controllers programmatically, verify the target selection in the JMeter GUI

## Notes

- The Module Controller implements `ReplaceableController` — at runtime it is replaced by the referenced controller and its entire subtree
- If the target controller is renamed or deleted, the Module Controller will fail at test start with a `JMeterStopTestException`
- Module Controller cannot reference Test Plan or Thread Group nodes, only Controller and Test Fragment elements
- The `node_path` property is a collection of tree node names (not element types or IDs), so renaming the target or any ancestor in the path will break the reference
- Multiple Module Controllers can reference the same target controller
