# Case Controller

## Description

The Case Controller is used to label and manage test cases. It extends from GenericController (like Simple Controller) and provides an additional `case_name` property to identify the current test case. This is especially useful in API automation testing scenarios where each API test case can be wrapped in a Case Controller and identified by its `case_name`.

## Parameters

| Parameter | Type | Required | Default | Description |
|-----------|------|----------|---------|-------------|
| CaseController.case_name | String | No | - | Case name used to identify the current test case |

## Usage Examples

### Example 1: Label a Single Test Case

```
create_jmeter_element with:
- elementType: "casecontroller"
- elementName: "Login API Test"
- properties:
    CaseController.case_name: "test_login_api"

// Add child samplers
create_jmeter_element with:
  elementType: "httpsampler"
  elementName: "User Login Request"
```

### Example 2: Organize Multiple Test Cases

```
// Case 1: Create user
create_jmeter_element with:
- elementType: "casecontroller"
- elementName: "Create User Test"
- properties:
    CaseController.case_name: "test_create_user"

create_jmeter_element with:
  elementType: "httpsampler"
  elementName: "Create User Request"

// Case 2: Query user
create_jmeter_element with:
- elementType: "casecontroller"
- elementName: "Query User Test"
- properties:
    CaseController.case_name: "test_query_user"

create_jmeter_element with:
  elementType: "httpsampler"
  elementName: "Query User Request"
```

### Example 3: Use Under Transaction Controller

```
create_jmeter_element with:
- elementType: "transactioncontroller"
- elementName: "User Management Module"

create_jmeter_element with:
- elementType: "casecontroller"
- elementName: "User Login - Normal Scenario"
- properties:
    CaseController.case_name: "login_normal"

create_jmeter_element with:
  elementType: "httpsampler"
  elementName: "Login Request"
```

## Best Practices

1. **Naming convention**: Use a consistent naming pattern for `CaseController.case_name`, such as `test_<feature>_<scenario>`
2. **Scenario isolation**: Wrap each independent test scenario in its own Case Controller
3. **Combine with assertions**: Add corresponding assertions inside the Case Controller to ensure test results are verifiable
4. **Combine with Transaction Controller**: Nest multiple Case Controllers under a Transaction Controller for module-level test organization
5. **Descriptive names**: Use `elementName` to describe the test scenario, and `CaseController.case_name` for programmatic identification

## Notes

- Case Controller extends GenericController, so child elements execute sequentially
- The `CaseController.case_name` property does not affect execution logic; it is used for identification and reporting only
- Case Controller can be nested inside other controllers (e.g., If Controller, Loop Controller)
- This is a Gitee QA extension component and requires the corresponding plugin to be installed
