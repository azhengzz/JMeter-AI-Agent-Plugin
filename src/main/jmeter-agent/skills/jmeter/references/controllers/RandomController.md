# Random Controller

## Description

The Random Logic Controller acts similarly to the Interleave Controller, except that instead of going in order through its sub-controllers and samplers, it picks one at random at each pass.

Interactions between multiple controllers can yield complex behavior. This is particularly true of the Random Controller. Experiment before you assume what results any given interaction will give.

## Parameters

| Property | Required | Default | Description | Example |
|----------|----------|---------|-------------|---------|
| `InterleaveControl.style` | No | `1` | If checked (0), the interleave controller will treat sub-controllers like single request elements and only allow one request per controller at a time. | `1` |

### InterleaveControl.style Values

| Value | Meaning |
|-------|---------|
| `0` | Checked - treat sub-controllers like single request elements and only allow one request per controller at a time |
| `1` | Unchecked - normal behavior (default) |

## Usage Examples

### Example 1: Random API Selection

```
create_jmeter_element with:
- elementType: "randomcontroller"
- elementName: "随机API调用"
- properties:
  - InterleaveControl.style: "1"

// Add child samplers - one will be randomly selected each iteration
create_jmeter_element with:
  elementType: "httpsampler"
  elementName: "GET_用户列表"

create_jmeter_element with:
  elementType: "httpsampler"
  elementName: "GET_订单列表"

create_jmeter_element with:
  elementType: "httpsampler"
  elementName: "GET_产品列表"
```

### Example 2: Random Search Terms

```
create_jmeter_element with:
- elementType: "randomcontroller"
- elementName: "随机搜索"
- properties:
  - InterleaveControl.style: "1"

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
```

### Example 3: Random User Actions

```
create_jmeter_element with:
- elementType: "randomcontroller"
- elementName: "随机用户行为"
- properties:
  - InterleaveControl.style: "0"
```

## Best Practices

1. **Equal weighting**: All children have equal selection probability (1/N for N children)
2. **Descriptive child names**: Name children clearly to identify them in results
3. **Group related actions**: Put similar operations in one random controller
4. **Avoid empty**: Always add at least one child sampler or controller
5. **Consider sample size**: More children means fewer executions per child per iteration

## Notes

- Random selection happens at the start of each iteration
- Each iteration is independent - previous selections do not affect future ones
- All children have equal probability of selection
- Selection is per-thread (each thread makes its own random choice)
- Interactions between multiple controllers can yield complex behavior; experiment before assuming results
