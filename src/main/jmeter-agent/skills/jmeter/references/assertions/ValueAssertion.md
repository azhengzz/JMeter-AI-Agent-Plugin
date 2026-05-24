# Value Assertion

## Description

Value Assertion compares actual values against expected values in a table format. Each row defines a check with an actual value, expected value, description, and enable flag. This is useful for straightforward value comparisons where you know both the actual and expected values at design time.

## Parameters

| Property | Required | Default | Description | Example |
|----------|----------|---------|-------------|---------|
| `valuesCheckTable` | Yes | — | Table of value comparison rules | (see examples) |

### valuesCheckTable Row Properties

| Property | Required | Default | Description | Example |
|----------|----------|---------|-------------|---------|
| `actual` | Yes | — | Actual value. Supports JMeter variables like `${var}`. | `"${status_code}"` |
| `expect` | Yes | — | Expected value to compare against. | `"200"` |
| `desc` | No | `""` | Description for this check (shown in failure message). | `"HTTP状态码"` |
| `enable` | No | `true` | Enable or disable this check row without deleting it. | `true` |

## Usage Examples

### Example 1: Single Value Check

```
create_jmeter_element with:
- elementType: "valueassertion"
- elementName: "断言_状态码"
- properties:
  - valuesCheckTable:
    - actual: "${status_code}"
      expect: "200"
      desc: "HTTP状态码"
```

### Example 2: Multiple Value Checks

```
create_jmeter_element with:
- elementType: "valueassertion"
- elementName: "断言_订单创建结果"
- properties:
  - valuesCheckTable:
    - actual: "${order_status}"
      expect: "created"
      desc: "订单状态"
    - actual: "${payment_status}"
      expect: "pending"
      desc: "支付状态"
    - actual: "${item_count}"
      expect: "3"
      desc: "商品数量"
```

### Example 3: With Disabled Row

```
create_jmeter_element with:
- elementType: "valueassertion"
- elementName: "断言_核心字段"
- properties:
  - valuesCheckTable:
    - actual: "${code}"
      expect: "0"
      desc: "返回码"
    - actual: "${debug_info}"
      expect: "none"
      desc: "调试信息"
      enable: false
```

## Best Practices

1. **Use descriptions**: Add `desc` to each row for clear failure messages
2. **Disable instead of delete**: Use `enable: false` to temporarily skip a check
3. **Reference variables**: Use `${variable}` syntax to compare extracted values
4. **Keep checks focused**: One logical check per row for easier debugging

## Notes

- Comparison is strict string equality (case-sensitive, no trimming)
- Disabled rows (`enable: false`) are completely skipped during assertion
- If the table is empty or null, the assertion passes (no-op)
- Failure messages include the description, actual value, and expected value
