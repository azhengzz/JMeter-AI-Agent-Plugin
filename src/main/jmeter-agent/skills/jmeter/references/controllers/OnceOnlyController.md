# Once Only Controller

## Description

Once Only Controller executes its children only once per thread, on the first iteration through the test plan. Subsequent iterations skip all elements inside this controller.

This is useful for one-time setup operations such as login, initialization, or data preparation that should not be repeated on each loop iteration.

## Parameters

This controller has no configurable properties.

## Usage Examples

### Example 1: Login Once Per Thread

```
create_jmeter_element with:
- elementType: "onceonlycontroller"
- elementName: "仅执行一次-用户登录"
```

Then add a login HTTP Request as a child of this controller.

### Example 2: One-Time Data Setup

```
create_jmeter_element with:
- elementType: "onceonlycontroller"
- elementName: "初始化测试数据"
```

## Notes

- Executes children only on the first iteration of each thread
- When placed directly under a Thread Group, it runs once per thread
- When placed under a Loop Controller, it runs once per loop cycle (first iteration only)
- No configuration properties are needed — just add child elements
