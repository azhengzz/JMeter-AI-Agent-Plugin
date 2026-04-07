# Poisson Random Timer

## Description

Poisson Random Timer generates delays based on Poisson distribution, simulating independent random events occurring at a constant average rate.

## Parameters

| Property | Required | Description | Example |
|----------|----------|-------------|---------|
| `ConstantTimer.delay` | Yes | Average delay (lambda) in milliseconds | `1000` |
| `RandomTimer.range` | Yes | Not used (present for compatibility) | `0` |

## Usage Examples

### Example 1: Average 2 Second Delay

```
create_jmeter_element with:
- elementType: "poissonrandomtimer"
- elementName: "泊松延迟-平均2秒"
- properties:
  - ConstantTimer.delay: "2000"
  - RandomTimer.range: "0"
```

### Example 2: Fast Event Rate

```
create_jmeter_element with:
- elementType: "poissonrandomtimer"
- elementName: "快速事件-平均500ms"
- properties:
  - ConstantTimer.delay: "500"
  - RandomTimer.range: "0"
```

### Example 3: Slow Event Rate

```
create_jmeter_element with:
- elementType: "poissonrandomtimer"
- elementName: "慢速事件-平均5秒"
- properties:
  - ConstantTimer.delay: "5000"
  - RandomTimer.range: "0"
```

## How It Works

Poisson distribution models:
- Independent events at constant average rate
- Time between events follows exponential distribution
- Most delays are short, some are very long

Delay calculation:
```
delay = -ln(random()) * lambda
```

Where lambda = average delay (ConstantTimer.delay)

## Poisson Distribution Characteristics

```
Probability
    ^
    |*
    | *
    |  *
    |   **
    |     ***
    |        ***************
    +------------------------> Time
    Short    Average    Long
```

- Many short delays
- Few long delays
- Average = lambda

## Use Cases

### 1. User Arrival Simulation
```
Users arriving at random intervals
Constant average arrival rate
```

### 2. Natural Event Patterns
```
Independent events
Random timing between events
```

### 3. Load Testing
```
Simulate real user arrival patterns
More realistic than constant intervals
```

## Comparison with Other Timers

| Timer | Distribution | Best For |
|-------|--------------|----------|
| Constant Timer | Fixed | Precise timing |
| Uniform Random | Equal probability | General randomness |
| Gaussian Random | Normal cluster | Human behavior |
| Poisson Random | Exponential | Arrival rate simulation |

## Common Patterns

| Lambda | Pattern | Use Case |
|--------|---------|----------|
| 500ms | Fast events | Rapid actions |
| 2000ms | Normal | Average user actions |
| 5000ms | Slow | Deliberate actions |
| 10000ms+ | Very slow | Background tasks |

## Best Practices

1. **Use for arrival**: Best for user arrival simulation
2. **Check lambda**: Set based on expected rate
3. **Long tail**: Be aware of occasional very long delays
4. **Test duration**: May significantly extend test time
5. **Monitor rate**: Verify actual event rate

## Tips

1. **Arrival simulation**: Best for user arrivals
2. **Short delays common**: Most delays are shorter than lambda
3. **Long delays happen**: Occasionally very long delays occur
4. **Calculate average**: Lambda = 1/rate (rate = events/second)
5. **Combine with other timers**: Use for specific scenarios

## Rate Calculations

| Lambda | Rate | Description |
|--------|------|-------------|
| 100ms | 10/sec | Very high frequency |
| 500ms | 2/sec | High frequency |
| 1000ms | 1/sec | Normal |
| 2000ms | 0.5/sec | Slow |
| 5000ms | 0.2/sec | Very slow |

## When to Use

### Use Poisson Random Timer When:
- Simulating user arrivals
- Independent events at constant rate
- Realistic random timing needed
- Natural event patterns

### Use Other Timers When:
- Fixed delay needed → Constant Timer
- Uniform randomness → Uniform Random Timer
- Human behavior → Gaussian Random Timer

## Example: User Arrival Simulation

```
// Thread group with Poisson timer
create_jmeter_element with:
- elementType: "poissonrandomtimer"
- elementName: "用户到达-平均3秒间隔"
- properties:
  - ConstantTimer.delay: "3000"

// User action in thread
create_jmeter_element with:
- elementType: "httpsampler"
- elementName: "GET_首页"
```

## Notes

- Simulates independent events at constant average rate
- Time between events is exponentially distributed
- Many short delays, occasional very long delays
- Best for arrival rate simulation
- Lambda = average delay in milliseconds
