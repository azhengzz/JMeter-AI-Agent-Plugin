# Precise Throughput Timer

## Description

This timer introduces variable pauses, calculated to keep the total throughput (e.g. in terms of samples per minute) as close as possible to a given figure. The timer does not generate threads, so the resulting throughput will be lower if the server is not capable of handling it, or if other timers add too big delays, or if there's not enough threads, or time-consuming test elements prevent it.

Note: In many cases, Open Model Thread Group would be a better choice for generating the desired load profile.

Note: If you alter timer configuration on the fly, then it might take time to adapt to the new settings. For instance, if the timer was initially configured for 1 request per hour, then it assigns incoming threads with 3600+sec pauses. Then, if the load configuration is altered to 1 per second, then the threads are not interrupted from their delays, and the threads keep waiting.

Although the Timer is called Precise Throughput Timer, it does not aim to produce precisely the same number of samples over one-second intervals during the test.

Precise Throughput Timer models Poisson arrivals schedule. That schedule often happens in real life, so it makes sense to use that for load testing. For instance, it naturally might generate samples that are close together thus it might reveal concurrency issues.

Constant Throughput Timer converges to the specified rate, however it tends to produce samples at even intervals. Precise Throughput Timer is more accurate for maintaining exact sample counts over time.

## Parameters

| Property | Required | Default | Description | Example |
|----------|----------|---------|-------------|---------|
| `throughput` | Yes | `"60"` | Maximum number of samples you want to obtain per "throughput period", including all threads in group, from all affected samplers. | `"60"` |
| `throughputPeriod` | Yes | `"60"` | Throughput period in seconds. For example, if throughput is set to 42 and throughputPeriod to 21 sec, then you'll get 2 samples per second. | `"60"` |
| `duration` | Yes | `"0"` | Test duration in seconds. This is used to ensure you'll get throughput*duration samples during "test duration" timeframe. Does NOT limit test duration; it is just a hint for the timer. Set to 0 for no duration constraint. | `"300"` |
| `batchSize` | Yes | `"1"` | Number of threads that depart simultaneously in each batch. If > 1, multiple threads leave at once while maintaining average throughput. | `"1"` |
| `batchThreadDelay` | Yes | `"0"` | Delay in milliseconds between thread departures within a batch. For instance, if set to 42, and the batch size is 3, then threads will depart at x, x+42ms, x+84ms. Only applies when batchSize > 1. | `"0"` |
| `randomSeed` | Yes | `"0"` | Seed for random number generator. Different timers should use different seed values. Constant seed ensures timer generates the same delays each test start. The value of "0" means the timer is truly random (non-repeatable). | `"0"` |

## Usage Examples

### Example 1: 60 Samples Per Minute

```
create_jmeter_element with:
- elementType: "precisethroughputTimer"
- elementName: "精确吞吐量-60次/分钟"
- properties:
  - throughput: "60"
  - throughputPeriod: "60"
```

### Example 2: 60 Iterations Per Hour

```
create_jmeter_element with:
- elementType: "precisethroughputTimer"
- elementName: "精确吞吐量-60次/小时"
- properties:
  - throughput: "60"
  - throughputPeriod: "3600"
  - duration: "3600"
```

### Example 3: With Exact Duration for Accurate Reporting

```
create_jmeter_element with:
- elementType: "precisethroughputTimer"
- elementName: "精确吞吐量-5分钟测试"
- properties:
  - throughput: "300"
  - throughputPeriod: "300"
  - duration: "300"
```

### Example 4: Reproducible Delays

```
create_jmeter_element with:
- elementType: "precisethroughputTimer"
- elementName: "可重现延迟的吞吐量"
- properties:
  - throughput: "60"
  - throughputPeriod: "60"
  - randomSeed: "42"
```

### Example 5: Batch Processing

```
create_jmeter_element with:
- elementType: "precisethroughputTimer"
- elementName: "批量事件吞吐量"
- properties:
  - throughput: "120"
  - throughputPeriod: "60"
  - batchSize: "5"
  - batchThreadDelay: "42"
```

## Best Practices

1. **Use for exact throughput**: Better than Constant Throughput Timer for precise sample counts
2. **Set appropriate period**: Match throughput to your period (e.g., samples per minute with period=60)
3. **Use randomSeed for debugging**: Makes tests reproducible during development; use 0 for production
4. **Keep batchSize=1 for normal use**: Only use batching when specifically needed (e.g., simulating bursts)
5. **Calculate actual rate**: `throughput / throughputPeriod = samples per second`
6. **Set duration for business-friendly configuration**: E.g., "60 samples per hour" with throughput=60, period=3600, duration=3600
7. **Best placement**: Place timer under the first element in a test loop for optimal scheduling

## Notes

- Produces Poisson-distributed arrivals for realistic load patterns
- The `duration` parameter does NOT limit test duration; it is a hint for the timer to ensure exact sample counts for that timeframe
- More computationally intensive than Constant Throughput Timer; keep schedule under 1,000,000 samples
- randomSeed allows exact reproduction of test scenarios; use different seeds for different timers
- Batch size can create unnatural patterns if not used carefully
- Works best for rates under 36000 requests/hour
- Note: In many cases, Open Model Thread Group would be a better choice for generating the desired load profile
