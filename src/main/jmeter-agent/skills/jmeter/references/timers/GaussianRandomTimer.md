# Gaussian Random Timer

## Description

Gaussian Random Timer adds a random pause with normal (Gaussian) distribution. Delays cluster around the mean, simulating natural human behavior patterns.

## Parameters

| Property | Required | Description | Example |
|----------|----------|-------------|---------|
| `ConstantTimer.delay` | Yes | Mean delay in milliseconds | `1000` |
| `RandomTimer.range` | Yes | Standard deviation (spread) | `100` |

## Usage Examples

### Example 1: Natural Think Time

```
create_jmeter_element with:
- elementType: "gaussianrandomtimer"
- elementName: "自然思考时间"
- properties:
  - ConstantTimer.delay: "2000"
  - RandomTimer.range: "500"
```

### Example 2: Tight Cluster Around Mean

```
create_jmeter_element with:
- elementType: "gaussianrandomtimer"
- elementName: "集中延迟-1秒"
- properties:
  - ConstantTimer.delay: "1000"
  - RandomTimer.range: "100"
```

### Example 3: Wide Variation

```
create_jmeter_element with:
- elementType: "gaussianrandomtimer"
- elementName: "大变化延迟"
- properties:
  - ConstantTimer.delay: "3000"
  - RandomTimer.range: "1000"
```

## How It Works

The delay follows normal distribution:
```
delay = mean + random_gaussian() * standard_deviation
```

For mean=2000 and deviation=500:
- ~68% of delays: 1500-2500ms (1σ)
- ~95% of delays: 1000-3000ms (2σ)
- ~99.7% of delays: 500-3500ms (3σ)

## Gaussian Distribution

```
        **
      **  **
     **    **
    **      **
   **        **
  **          **
 **            **
**--------------**--------------
   mean-delay    mean   mean+delay
```

Delays cluster around mean with fewer extreme values.

## Use Cases

### 1. Natural Human Behavior
```
Human actions cluster around average
Few very fast or very slow actions
```

### 2. Realistic Load Pattern
```
Request timing clusters around mean
Predictable with natural variation
```

### 3. Performance Testing
```
Simulate real user timing patterns
More realistic than uniform
```

## Comparison: Gaussian vs Uniform

| Timer | Distribution | Characteristics |
|-------|--------------|----------------|
| Gaussian | Normal cluster | Values cluster around mean |
| Uniform | Flat distribution | Equal probability across range |

### Example Comparison

For mean=2000, range=1000:
- **Gaussian**: Most delays ~2000ms, few at 1000ms or 3000ms
- **Uniform**: Equal chance for any delay 1000-3000ms

## Standard Deviation Guidelines

| Deviation | Effect | Use Case |
|-----------|--------|----------|
| mean × 0.05 | Very tight | Consistent timing |
| mean × 0.1 | Tight | Low variation |
| mean × 0.25 | Moderate | Normal variation |
| mean × 0.5+ | High | High variation |

## Best Practices

1. **Use for human simulation**: More realistic than uniform
2. **Set appropriate deviation**: Too small = no variation, too large = too spread
3. **Mean should be target**: Most delays will be near mean
3. **Handle negatives**: Delays can't be negative (clipped at 0)
5. **Monitor distribution**: Check actual delay distribution

## Tips

1. **Deviation ~10-25%**: For most realistic scenarios
2. **Base on data**: Use real user timing data
3. **Avoid too wide**: Very large deviation = unpredictable
4. **Per-action**: Different actions need different timing
5. **Test impact**: Check effect on test duration

## When to Use Gaussian vs Uniform

| Scenario | Recommended Timer |
|----------|-------------------|
| Human think time | Gaussian Random Timer |
| Server throttling | Uniform Random Timer |
| Natural clustering | Gaussian Random Timer |
| Equal probability | Uniform Random Timer |

## Example: Realistic User Flow

```
// Load home page - quick consistent
create_jmeter_element with:
- elementType: "gaussianrandomtimer"
- elementName: "浏览主页"
- properties:
  - ConstantTimer.delay: "2000"
  - RandomTimer.range: "200"

// Read article - variable
create_jmeter_element with:
- elementType: "gaussianrandomtimer"
- elementName: "阅读文章"
- properties:
  - ConstantTimer.delay: "5000"
  - RandomTimer.range: "2000"

// Form fill - consistent
create_jmeter_element with:
- elementType: "gaussianrandomtimer"
- elementName: "填写表单"
- properties:
  - ConstantTimer.delay: "3000"
  - RandomTimer.range: "500"
```

## Notes

- Normal distribution clusters around mean
- Standard deviation controls spread
- More realistic for human behavior
- Negative delays become zero
- 68% of values within ±1 deviation
- 95% of values within ±2 deviations
