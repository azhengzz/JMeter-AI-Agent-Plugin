# Transaction Controller
> **Source**: Apache JMeter built-in component (ships with JMeter, no extra install needed)

## Description

The Transaction Controller generates an additional sample which measures the overall time taken to perform the nested test elements. There are two modes of operation:

- Additional sample is added after the nested samples
- Additional sample is added as a parent of the nested samples

The generated sample time includes all the times for the nested samplers excluding by default (since 2.11) timers and processing time of pre/post processors unless the "Include duration of timer and pre-post processors in generated sample" checkbox is checked. Depending on the clock resolution, it may be slightly longer than the sum of the individual samplers plus timers.

The generated sample is only regarded as successful if all its sub-samples are successful.

In parent mode, the individual samples can still be seen in the Tree View Listener, but no longer appear as separate entries in other Listeners. Also, the sub-samples do not appear in CSV log files, but they can be saved to XML files.

## Parameters

| Property | Required | Default | Description | Example |
|----------|----------|---------|-------------|---------|
| `TransactionController.parent` | No | `false` | Generate parent sample. When true, the sample is generated as a parent of the other samples. When false, the sample is generated as an independent sample. | `true` |
| `TransactionController.includeTimers` | No | `false` | Include duration of timer and pre-post processors in generated sample. When true, includes timer, pre- and post-processing delays in the generated sample. | `false` |

## Usage Examples

### Example 1: Parent Sample Mode (Recommended)

```
create_jmeter_element with:
- elementType: "transactioncontroller"
- elementName: "用户登录流程"
- properties:
  - TransactionController.parent: "true"
  - TransactionController.includeTimers: "false"

// Add child samplers
// 1. GET login page
// 2. POST login credentials
// 3. GET user profile
```

### Example 2: Transaction with Timers Included

```
create_jmeter_element with:
- elementType: "transactioncontroller"
- elementName: "下单流程"
- properties:
  - TransactionController.parent: "true"
  - TransactionController.includeTimers: "true"

// Add child samplers
// 1. GET product details
// 2. POST add to cart
// 3. GET cart
// 4. POST checkout
```

### Example 3: Additional Sample Mode (Legacy)

```
create_jmeter_element with:
- elementType: "transactioncontroller"
- elementName: "登录流程"
- properties:
  - TransactionController.parent: "false"
  - TransactionController.includeTimers: "false"
```

## Best Practices

1. **Use parent sample mode**: Set `TransactionController.parent: "true"` for better reporting and hierarchical results
2. **Meaningful names**: Use business process names like `用户登录流程`, `下单流程` - the name is used as the transaction sample label
3. **Set includeTimers appropriately**:
   - Use `"true"` for end-to-end timing (includes think time and pre/post processing)
   - Use `"false"` for server-performance-only timing (excludes timers and processors)
4. **Add assertions**: Add Duration Assertions on the Transaction Controller for SLA validation
5. **Scope assertions carefully**: In parent mode, assertions apply to both individual samples and the overall transaction; use a Simple Controller to contain samples and limit assertion scope

## Notes

- The generated sample is only regarded as successful if all its sub-samples are successful
- In parent mode, individual samples can still be seen in the Tree View Listener but no longer appear as separate entries in other Listeners
- Sub-samples do not appear in CSV log files in parent mode, but can be saved to XML files
- Parent mode controllers do not currently properly support nested transaction controllers of either type
- When "Include duration of timer and pre-post processors in generated sample" is checked, the time includes all processing within the controller scope, not just the samples
- Default for `includeTimers` is `false` (since JMeter 2.11), meaning timers and pre/post processor time are excluded by default
