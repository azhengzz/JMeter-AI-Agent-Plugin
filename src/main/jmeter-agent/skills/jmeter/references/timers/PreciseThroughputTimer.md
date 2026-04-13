# Precise Throughput Timer

## Description

Precise Throughput Timer generates Poisson arrivals with constant throughput. It maintains an exact sample count for a given timeframe by using a constant throughput/throughputPeriod configuration.

This timer is more accurate than Constant Throughput Timer for maintaining exact sample counts over time.

## Parameters

| Property | Required | Description | Example |
|----------|----------|-------------|---------|
| `throughput` | Yes | Desired throughput in samples per period | `60` |
| `throughputPeriod` | No | Time period in seconds for the throughput value | `60` |
| `duration` | No | Test duration in seconds | `300` |
| `randomSeed` | No | Random seed for reproducible delays | `12345` |
| `batchSize` | No | Number of events to generate in a batch | `1` |
| `batchThreadDelay` | No | Delay in seconds between batches | `0` |

## Usage Examples

### Example 1: 60 Samples Per Minute

```
create_jmeter_element with:
- elementType: "precisethroughputTimer"
- elementName: "精确吞吐量-60次/分钟"
- properties:
  - throughput: 60
  - throughputPeriod: 60
```

### Example 2: 120 Samples Per Hour

```
create_jmeter_element with:
- elementType: "precisethroughputTimer"
- elementName: "精确吞吐量-120次/小时"
- properties:
  - throughput: 120
  - throughputPeriod: 3600
```

### Example 3: With Exact Duration

```
create_jmeter_element with:
- elementType: "precisethroughputTimer"
- elementName: "精确吞吐量-5分钟测试"
- properties:
  - throughput: 300
  - throughputPeriod: 300
  - duration: 300
```

### Example 4: Reproducible Delays

```
create_jmeter_element with:
- elementType: "precisethroughputTimer"
- elementName: "可重现延迟的吞吐量"
- properties:
  - throughput: 60
  - throughputPeriod: 60
  - randomSeed: 42
```

### Example 5: Batch Processing

```
create_jmeter_element with:
- elementType: "precisethroughputTimer"
- elementName: "批量事件吞吐量"
- properties:
  - throughput: 120
  - throughputPeriod: 60
  - batchSize: 5
  - batchThreadDelay: 1
```

## Calculation

The timer generates events based on: `throughput / throughputPeriod` samples per second.

For example:
- `throughput=60`, `period=60` → 1 sample per second
- `throughput=120`, `period=60` → 2 samples per second
- `throughput=3600`, `period=3600` → 1 sample per second

## Parameters Explained

### throughput
Number of samples to generate during each throughputPeriod.

### throughputPeriod
Time period in seconds for the throughput calculation.

### duration
Test duration in seconds. The timer ensures exact sample count for this timeframe, which is useful for getting round numbers in reports (e.g., "100 samples per hour").

### randomSeed
- `0` (default): Random delays (not reproducible)
- `> 0`: Fixed seed for reproducible delay sequences

### batchSize
Number of events to generate as a batch. Useful for scenarios like:
- Send pairs of events with specific delay between them
- Generate bursts of requests

### batchThreadDelay
Delay in seconds between events when using batch size > 1.

## Best Practices

1. **Use for exact throughput**: Better than Constant Throughput Timer for precise sample counts
2. **Set appropriate period**: Match throughput to your period (e.g., samples per minute)
3. **Use randomSeed for debugging**: Makes tests reproducible during development
4. **Keep batchSize=1** for normal use: Only use batching when specifically needed
5. **Calculate actual rate**: `throughput / throughputPeriod = samples per second`

## Tips

1. **Think time**: Combine with other timers for more realistic user behavior
2. **Load testing**: Use to control exact request rate
3. **Duration**: Set duration to match your test plan for accurate reporting
4. **Batch processing**: Use batchSize and batchThreadDelay for specific scenarios
5. **Reproducibility**: Set randomSeed for debugging, remove for production

## Comparison with Constant Throughput Timer

| Feature | Precise Throughput Timer | Constant Throughput Timer |
|---------|------------------------|---------------------------|
| Algorithm | Poisson process | Delay calculation |
| Sample count | Exact for duration | Approximate |
| Complexity | Higher | Lower |
| Use case | Precise load testing | Simple rate limiting |

## Notes

- Produces Poisson-distributed arrivals for realistic load patterns
- Maintains exact sample counts when duration is specified
- More computationally intensive than Constant Throughput Timer
- RandomSeed allows exact reproduction of test scenarios
- Batch size can create unnatural patterns if not used carefully
