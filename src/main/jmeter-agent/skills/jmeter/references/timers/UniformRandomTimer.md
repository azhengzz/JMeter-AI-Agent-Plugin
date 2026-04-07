# Uniform Random Timer

## Description

Uniform Random Timer adds a random pause with uniform distribution. Each pause is calculated as: constant delay + random value (0 to range).

## Parameters

| Property | Required | Description | Example |
|----------|----------|-------------|---------|
| `ConstantTimer.delay` | Yes | Constant delay in milliseconds | `1000` |
| `RandomTimer.range` | Yes | Maximum random delay in milliseconds | `500` |

## Usage Examples

### Example 1: Random Think Time 1-3 Seconds

```
create_jmeter_element with:
- elementType: "uniformrandomtimer"
- elementName: "思考时间-1到3秒"
- properties:
  - ConstantTimer.delay: "1000"
  - RandomTimer.range: "2000"
```

### Example 2: Small Random Delay

```
create_jmeter_element with:
- elementType: "uniformrandomtimer"
- elementName: "随机间隔-100到500ms"
- properties:
  - ConstantTimer.delay: "100"
  - RandomTimer.range: "400"
```

### Example 3: Base Delay with Random Variation

```
create_jmeter_element with:
- elementType: "uniformrandomtimer"
- elementName: "基础2秒+随机1秒"
- properties:
  - ConstantTimer.delay: "2000"
  - RandomTimer.range: "1000"
```

## How It Works

The total delay is calculated as:
```
total_delay = constant_delay + random(0, range)
```

For delay=1000 and range=500:
- Minimum delay: 1000ms
- Maximum delay: 1500ms
- Average delay: 1250ms

## Distribution

Uniform distribution means:
- All values in range have equal probability
- Predictable average = delay + range/2
- No bias toward any value

## Use Cases

### 1. Realistic User Behavior
```
Users don't act at exact intervals
Simulate natural variation
```

### 2. Server Protection
```
Avoid synchronized requests
Spread load over time
```

### 3. Throttling
```
Control request rate
Add base delay with variation
```

## Common Patterns

| Base Delay | Range | Total Delay | Use Case |
|------------|-------|-------------|----------|
| 1000ms | 500ms | 1-1.5s | Normal think time |
| 2000ms | 3000ms | 2-5s | Complex operation |
| 500ms | 500ms | 0.5-1s | Quick actions |
| 0ms | 1000ms | 0-1s | Pure random |

## Best Practices

1. **Use realistic values**: Base on real user behavior
2. **Avoid too much variance**: Don't make range too large
3. **Balance load**: Prevent request bunching
4. **Consider impact**: Delays extend test duration
5. **Monitor rate**: Check actual requests per second

## Tips

1. **Start small**: Begin with small range
2. **Adjust based on data**: Use analytics to set values
3. **Per-action timers**: Different actions need different think times
4. **Test impact**: Check total test time impact
5. **Combine with other timers**: Use multiple timers for different scenarios

## Comparison with Other Timers

| Timer | Delay Distribution | Best For |
|-------|-------------------|----------|
| Constant Timer | Fixed delay | Precise timing |
| Uniform Random Timer | Uniform random | General randomness |
| Gaussian Random Timer | Normal distribution | Natural clustering |
| Poisson Random Timer | Poisson distribution | Arrival rate simulation |

## Example: User Journey with Timers

```
// Login page
create_jmeter_element with:
- elementType: "uniformrandomtimer"
- elementName: "阅读登录页"
- properties:
  - ConstantTimer.delay: "2000"
  - RandomTimer.range: "1000"

// Submit login
create_jmeter_element with:
- elementType: "httpsampler"
- elementName: "POST_登录"

// After login, navigate
create_jmeter_element with:
- elementType: "uniformrandomtimer"
- elementName: "浏览页面"
- properties:
  - ConstantTimer.delay: "3000"
  - RandomTimer.range: "2000"
```

## Notes

- Each sampler gets different random delay
- Uniform distribution = equal probability
- Total delay = constant + random
- Adds to total test duration
- More realistic than constant delay
