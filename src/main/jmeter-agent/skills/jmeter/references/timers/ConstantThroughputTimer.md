# Constant Throughput Timer

## Description

Constant Throughput Timer controls the throughput (requests per minute) of samplers. It paces the samplers under its influence so that the total number of samples per unit of time approaches a given constant.

## Parameters

| Property | Required | Description | Example |
|----------|----------|-------------|---------|
| `throughput` | Yes | Target throughput in samples per minute | `60` |
| `calcMode` | No | Calculation mode | `0` |

## Calculation Modes

| Value | Mode | Description |
|-------|------|-------------|
| `0` | This thread only | Each thread maintains its own pace |
| `1` | All active threads | Pace based on total active thread count |
| `2` | All active threads in current thread group | Pace based on threads in this group |
| `3` | All active threads (shared) | Alternate calculation synchronized across threads |
| `4` | All active threads in current thread group (shared) | Alternate calculation for thread group |

## Usage Examples

### Example 1: 60 Requests Per Minute (1 per second)

```
create_jmeter_element with:
- elementType: "constantthroughputtimer"
- elementName: "限速-60次/分钟"
- properties:
  - throughput: 60
  - calcMode: 0
```

### Example 2: Shared Across All Threads

```
create_jmeter_element with:
- elementType: "constantthroughputtimer"
- elementName: "全局限速-100次/分钟"
- properties:
  - throughput: 100
  - calcMode: 3
```

### Example 3: Thread Group Based

```
create_jmeter_element with:
- elementType: "constantthroughputtimer"
- elementName: "线程组限速"
- properties:
  - throughput: 300
  - calcMode: 4
```

### Example 4: High Throughput

```
create_jmeter_element with:
- elementType: "constantthroughputtimer"
- elementName: "限速-600次/分钟"
- properties:
  - throughput: 600
  - calcMode: 1
```

## How It Works

1. Calculates delay between samples based on throughput
2. Adjusts timing according to calculation mode
3. Pauses as needed to control rate
4. Maintains target samples per minute

Delay calculation (per thread):
```
delay_ms = (60000 / throughput) * thread_factor
```

Where thread_factor depends on calcMode.

## Throughput Calculations

| Throughput | Samples/Second | Description |
|------------|----------------|-------------|
| 60 | 1 | Low rate |
| 300 | 5 | Medium rate |
| 600 | 10 | High rate |
| 3600 | 60 | Very high rate |

## Calculation Mode Details

### This thread only (0)
Each thread independently maintains the target throughput.
- Total throughput = throughput × number of threads

### All active threads (1)
Throughput is divided among all currently active threads.
- Delay = (60000 / throughput) × activeThreadCount

### All active threads in current thread group (2)
Throughput is divided among threads in this thread group only.
- Useful for controlling throughput per thread group

### All active threads (shared) (3)
Shared calculation mode that synchronizes across all threads.
- More consistent than mode 1 for variable thread counts

### All active threads in current thread group (shared) (4)
Shared calculation for thread group only.
- Useful for per-thread-group throughput control

## Use Cases

### 1. API Rate Limiting
```
// API allows 100 requests/minute
create_jmeter_element with:
- elementType: "constantthroughputtimer"
- elementName: "API限速-100次/分钟"
- properties:
  - throughput: 100
  - calcMode: 0
```

### 2. Production Simulation
```
// Production has ~600 requests/minute
create_jmeter_element with:
- elementType: "constantthroughputtimer"
- elementName: "模拟生产流量"
- properties:
  - throughput: 600
  - calcMode: 3
```

### 3. SLA Testing
```
// Test at specific throughput level
create_jmeter_element with:
- elementType: "constantthroughputtimer"
- elementName: "SLA测试-300次/分钟"
- properties:
  - throughput: 300
  - calcMode: 1
```

## Best Practices

1. **Choose appropriate calcMode**: Select based on your throughput target scope
2. **Monitor actual throughput**: Verify with Aggregate Report listener
3. **Consider response time**: Slow responses reduce achieved rate
4. **Use realistic rates**: Based on actual requirements
5. **Test different modes**: Find which works best for your scenario

## Tips

1. **Per-minute value**: Throughput is samples per minute, not seconds
2. **CalcMode selection**: Use shared modes (3, 4) for more consistent pacing
3. **Thread count matters**: Modes 1 and 2 adjust based on active threads
4. **Global placement**: Place at test plan level or thread group level
5. **Monitor results**: Check actual vs target throughput in listeners

## Common Issues

### Issue: Throughput Not Achieved
**Cause**: Response time too long or thread count too high
**Solution**: Reduce throughput or reduce threads, improve server performance

### Issue: Uneven Distribution
**Cause**: Variable response times, thread startup/stutdown
**Solution**: This is normal, timer does best effort

### Issue: Too Much Pausing
**Cause**: Throughput too low for number of threads and calcMode
**Solution**: Reduce threads, increase throughput, or use mode 0

## Comparison with Precise Throughput Timer

| Feature | Constant Throughput Timer | Precise Throughput Timer |
|---------|------------------------|---------------------------|
| Algorithm | Delay calculation | Poisson process |
| Sample count | Approximate | Exact for duration |
| Complexity | Lower | Higher |
| Use case | Simple rate limiting | Precise load testing |

## Notes

- Throughput is samples per minute (not seconds)
- Different calcMode values change pacing behavior significantly
- Shared modes (3, 4) provide more consistent throughput
- Works by adding pauses between samples
- Good for API rate limiting and traffic simulation
