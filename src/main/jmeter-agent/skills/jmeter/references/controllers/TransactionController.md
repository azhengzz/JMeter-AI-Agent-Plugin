# Transaction Controller

## Description

Transaction Controller groups multiple samplers into a single transaction. It measures the total time for all child samplers and can generate a single sample result for the group.

## Source Code

Based on Apache JMeter source: `org.apache.jmeter.control.TransactionController`

## Parameters

| Property | Required | Default | Description | Example |
|----------|----------|---------|-------------|---------|
| `TransactionController.parent` | No | `false` | Generate a parent sample containing nested samples | `true` |
| `TransactionController.includeTimers` | No | `true` | Include timer duration in total time | `true` |

## Parameter Details

### TransactionController.parent
- **Required**: No
- **Default**: `false`
- **Description**:
  - `false`: Generate additional sample after nested samples (JMeter 2.2 behavior)
  - `true`: Generate parent sampler containing nested samples

### TransactionController.includeTimers
- **Required**: No
- **Default**: `true` (for backwards compatibility)
- **Description**: Whether to include timer and pre/post processor time in overall sample time

## Two Modes of Operation

### Mode 1: Additional Sample (generateParentSample = false)
- Creates an **additional sample** after nested samplers complete
- Sample name: Transaction Controller name
- Response message format: `"Number of samples in transaction : X, number of failing samples : Y"`
- Good for legacy compatibility

### Mode 2: Parent Sample (generateParentSample = true) 
- Creates a **parent sampler** that contains all nested samples
- Nested samples become children of the transaction sample
- Transaction sample contains aggregated timing and data
- **Recommended for modern testing**

## Usage Examples

### Example 1: Basic Transaction (Parent Sample Mode)

```
create_jmeter_element with:
- elementType: "transactioncontroller"
- elementName: "用户登录流程"
- properties:
  - TransactionController.parent: "true"

// Add child samplers
// 1. GET login page
// 2. POST login credentials  
// 3. GET user profile
```

### Example 2: Checkout Transaction with Timers

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

### Example 3: Legacy Mode (Additional Sample)

```
create_jmeter_element with:
- elementType: "transactioncontroller"
- elementName: "登录流程"
- properties:
  - TransactionController.parent: "false"
  - TransactionController.includeTimers: "true"
```

## Source Code Behavior

From the source code:

```java
// Mode selection
if (isGenerateParentSample()) {
    return nextWithTransactionSampler();
}
return nextWithoutTransactionSampler();

// Parent mode: Creates TransactionSampler
transactionSampler = new TransactionSampler(this, getName());

// Additional sample mode: Creates SampleResult at end
res = new SampleResult();
res.setSampleLabel(getName());
res.setResponseMessage(
    "Number of samples in transaction : " + calls + 
    ", number of failing samples : " + noFailingSamples);
```

## Timing Calculation

### When includeTimers = true (default)
- Total time includes sampler execution time
- **Includes** timer pauses, pre-processor, and post-processor time

### When includeTimers = false
- Total time excludes timer pauses
- **Only** includes actual sampler execution time
- More accurate measure of pure server response time

## Response Message Format

In additional sample mode:
```
Number of samples in transaction : 5, number of failing samples : 0
```

## Best Practices

1. **Use parent sample mode**: Set `TransactionController.parent: true` for better reporting
2. **Meaningful names**: Use business process names like `用户登录流程`, `下单流程`
3. **Set appropriate includeTimers**: 
   - Use `true` for end-to-end timing (includes think time)
   - Use `false` for server-performance-only timing
4. **Add assertions**: Set duration assertions on the transaction for SLA validation
5. **Nesting support**: Can nest transaction controllers for complex flows

## Nested Transactions

```
TransactionController: "完整购物流程" (parent: true)
├── TransactionController: "登录" (parent: true)
│   ├── POST /login
│   └── JSON Extractor: token
├── TransactionController: "浏览商品" (parent: true)
│   ├── GET /products
│   └── GET /product/${id}
└── TransactionController: "结算" (parent: true)
    ├── POST /cart
    └── POST /checkout
```

## Common Patterns

### Standard Transaction
```
parent: true
includeTimers: true
→ Measures end-to-end time including think time
```

### Server Time Only
```
parent: true
includeTimers: false
→ Measures pure server response time (excludes timers/pre/post processors)
```

### SLA Validation
```
// Transaction Controller
parent: true

// Duration Assertion on transaction
duration: 3000 (3 seconds)
→ Fails if entire transaction takes longer than 3 seconds
```

## Notes

- Two modes: parent sample (recommended) vs additional sample (legacy)
- Parent mode creates hierarchical result structure
- Transaction name appears as the sample label in Listeners
- Can add Duration Assertion directly to Transaction Controller
- Nested transactions provide hierarchical reporting
- `includeTimers` affects how total time is calculated
