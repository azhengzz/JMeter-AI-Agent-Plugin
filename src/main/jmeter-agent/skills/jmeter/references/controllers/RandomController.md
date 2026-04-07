# Random Controller

## Description

Random Controller randomly selects one of its child elements to execute during each iteration. Only one child runs per iteration, chosen with equal probability.

## Source Code

Based on Apache JMeter source: `org.apache.jmeter.control.RandomController`

## Parameters

**No specific parameters** - RandomController inherits from `InterleaveControl` and uses random selection logic.

## How It Works

From the source code:

```java
@Override
protected void resetCurrent() {
    if (getSubControllers().isEmpty()) {
        current = 0;
    } else {
        // Random selection using ThreadLocalRandom
        current = ThreadLocalRandom.current().nextInt(this.getSubControllers().size());
    }
}

@Override
protected void incrementCurrent() {
    super.incrementCurrent();
    // Random selection for next iteration
    current = ThreadLocalRandom.current().nextInt(this.getSubControllers().size());
}
```

Key behaviors:
1. **Reset**: Randomly selects a child when first executed
2. **Next iteration**: Randomly selects a child again
3. **Equal probability**: Each child has `1/n` chance of being selected
4. **Thread-safe**: Uses `ThreadLocalRandom` for thread safety

## Usage Examples

### Example 1: Random API Selection

```
create_jmeter_element with:
- elementType: "randomcontroller"
- elementName: "随机API调用"

// Add child samplers - one will be randomly selected each iteration
create_jmeter_element with:
  elementType: "httpsampler"
  elementName: "GET_用户列表"
  // ... properties ...

create_jmeter_element with:
  elementType: "httpsampler"
  elementName: "GET_订单列表"
  // ... properties ...

create_jmeter_element with:
  elementType: "httpsampler"
  elementName: "GET_产品列表"
  // ... properties ...
```

### Example 2: Random Search Terms

```
create_jmeter_element with:
- elementType: "randomcontroller"
- elementName: "随机搜索"

create_jmeter_element with:
  elementType: "httpsampler"
  elementName: "搜索_手机"
  properties:
    - HTTPSampler.path: "/search?q=phone"

create_jmeter_element with:
  elementType: "httpsampler"
  elementName: "搜索_电脑"
  properties:
    - HTTPSampler.path: "/search?q=computer"

create_jmeter_element with:
  elementType: "httpsampler"
  elementName: "搜索_平板"
  properties:
    - HTTPSampler.path: "/search?q tablet"
```

### Example 3: Random User Actions

```
create_jmeter_element with:
- elementType: "randomcontroller"
- elementName: "随机用户行为"

create_jmeter_element with:
  elementType: "httpsampler"
  elementName: "查看主页"

create_jmeter_element with:
  elementType: "httpsampler"
  elementName: "查看个人资料"

create_jmeter_element with:
  elementType: "httpsampler"
  elementName: "查看设置"

create_jmeter_element with:
  elementType: "httpsampler"
  elementName: "退出登录"
```

## Selection Probability

For N children, each child has:
```
Probability = 1/N
```

Example with 3 children:
- Child 1: 33.3% chance
- Child 2: 33.3% chance  
- Child 3: 33.3% chance

## Comparison with Other Controllers

| Controller | Selection Pattern | Description |
|-----------|------------------|-------------|
| RandomController | Random | Equal probability each iteration |
| InterleaveController | Round-robin | Sequential, one per iteration |
| SwitchController | Specified | Based on switch value |
| LoopController | All | All children execute sequentially |

## Use Cases

### 1. Load Distribution
Distribute load across different endpoints randomly:
```
→ Simulates real-world random access patterns
→ Prevents all threads hitting same endpoint simultaneously
```

### 2. Realistic User Behavior
Simulate users choosing different actions:
```
→ Users don't follow fixed patterns
→ Random selection mimics real behavior
```

### 3. A/B Testing
Test different variants with random selection:
```
→ Random assignment to A or B variant
→ Natural distribution in results
```

### 4. Data Variety
Test with different input parameters:
```
→ Each iteration uses different data
→ Broader test coverage
```

## Best Practices

1. **Equal weighting**: All children have equal selection probability
2. **Descriptive child names**: Name children clearly to identify them
3. **Group related actions**: Put similar operations in one random controller
4. **Avoid empty**: Always add at least one child
5. **Consider sample size**: More children = fewer executions per child per iteration

## Tips

1. **Test with few users**: Verify random distribution with smaller tests first
2. **Monitor distribution**: Use Aggregate Report to verify sample counts
3. **Combine with Loop Controller**: Run multiple random selections per thread
4. **Use with timers**: Add think time between selections
5. **Production simulation**: Good for simulating real user random behavior

## Source Code Details

### Random Number Generation
```java
// Uses ThreadLocalRandom for thread-safe random generation
ThreadLocalRandom.current().nextInt(this.getSubControllers().size())
```

This ensures:
- Thread-safe random selection
- No shared state between threads
- Better performance than synchronized Random

### Inheritance
RandomController extends `InterleaveControl`, which provides:
- Child management
- Current index tracking
- Standard controller lifecycle methods

## Common Patterns

### Pattern 1: Random Endpoint Testing
```
RandomController
├── GET /api/v1/users
├── GET /api/v1/products
├── GET /api/v1/orders
└── GET /api/v1/reports
→ Each thread randomly selects one endpoint per iteration
```

### Pattern 2: Random User Journey
```
RandomController
├── View Home Page
├── Search Products
├── View Cart
└── Checkout
→ Simulates different user journey steps
```

### Pattern 3: Weighted Random (Using JSR223)
```
// WhileController with condition
WhileController: ${continue}

  // JSR223 PreProcessor: Select random action
  script: |
    def rand = new Random()
    def actions = ["view", "add", "cart", "checkout"]
    def selected = actions[rand.nextInt(actions.size())]
    vars.put("selected_action", selected)

  // IfController for each action
  IfController: ${selected_action} == "view"
    → GET /home
  
  IfController: ${selected_action} == "add"
    → POST /cart
```

## Notes

- Random selection happens at the start of each iteration
- Each iteration is independent
- All children have equal probability of selection
- Selection is per-thread (each thread makes its own random choice)
- Good for simulating real-world random user behavior
- Can be nested inside other controllers
