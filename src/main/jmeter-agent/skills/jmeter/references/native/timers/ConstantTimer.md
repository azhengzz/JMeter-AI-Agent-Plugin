# Constant Timer
> **Source**: Apache JMeter built-in component (ships with JMeter, no extra install needed)

## Description

Constant Timer adds a fixed pause before each sampler in its scope. It's used to simulate user think time between actions or control request rates.

## Parameters

| Property | Required | Default | Description | Example |
|----------|----------|---------|-------------|---------|
| `ConstantTimer.delay` | Yes | `300` | Delay in milliseconds | `1000` |

## Usage Examples

### Example 1: 1 Second Think Time

```
create_jmeter_element with:
- elementType: "constanttimer"
- elementName: "思考时间-1秒"
- properties:
  - ConstantTimer.delay: "1000"
```

### Example 2: 2 Second Delay Between Requests

```
create_jmeter_element with:
- elementType: "constanttimer"
- elementName: "请求间隔-2秒"
- properties:
  - ConstantTimer.delay: "2000"
```

## Use Cases

1. **Think Time Simulation**: Add realistic pauses between user actions
2. **Rate Limiting**: Control requests per second
3. **Server Protection**: Prevent overwhelming the server
4. **Realistic Load**: Mimic real user behavior patterns

## Best Practices

1. **Based on real data**: Use actual user behavior analysis
2. **Avoid excessive delays**: Too long delays extend test time unnecessarily
3. **Consider test type**: Reduce or remove for maximum load tests
4. **Use appropriate timers**: For random delays, use Uniform Random Timer
5. **Scope correctly**: Timer affects all samplers in its scope

## Timer Scope

- Timer applies to all samplers at same level and below
- Multiple timers in same scope add up (not override)
- Placing timer at thread group level affects all requests

## Notes

- Delay is in milliseconds (1000 ms = 1 second)
- Think time should be based on realistic user behavior
- For high-load testing, consider reducing think time
- For random delays, use `uniformrandomtimer` instead
