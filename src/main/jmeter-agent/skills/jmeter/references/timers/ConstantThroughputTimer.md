# Constant Throughput Timer

## Description

Constant Throughput Timer controls the throughput (requests per minute) of samplers. It pauses to maintain a target throughput rate.

## Parameters

| Property | Required | Description | Example |
|----------|----------|-------------|---------|
| `ThroughputConstant.throughput` | Yes | Target throughput (samples per minute) | `60` |

## Usage Examples

### Example 1: 60 Requests Per Minute (1 per second)

```
create_jmeter_element with:
- elementType: "constantthroughputtimer"
- elementName: "限速-60次/分钟"
- properties:
  - ThroughputConstant.throughput: "60"
```

### Example 2: High Throughput

```
create_jmeter_element with:
- elementType: "constantthroughputtimer"
- elementName: "限速-600次/分钟"
- properties:
  - ThroughputConstant.throughput: "600"
```

### Example 3: Low Throughput

```
create_jmeter_element with:
- elementType: "constantthroughputtimer"
- elementName: "限速-10次/分钟"
- properties:
  - ThroughputConstant.throughput: "10"
```

## How It Works

1. Calculates delay between samples
2. Adjusts timing to maintain target throughput
3. Works across all threads in test plan
4. Pauses as needed to control rate

Delay calculation:
```
delay = (60000 / throughput) - actual_response_time
```

## Throughput Calculations

| Throughput | Samples/Second | Description |
|------------|----------------|-------------|
| 60 | 1 | Low rate |
| 300 | 5 | Medium rate |
| 600 | 10 | High rate |
| 3600 | 60 | Very high rate |

## Scope

The timer operates at:
- **Test Plan level**: All samplers across all threads
- **Global throughput**: Total samples per minute

All threads share the throughput budget.

## Use Cases

### 1. Rate Limiting
```
Don't exceed API rate limits
Protect server from overload
```

### 2. SLA Testing
```
Test at specific throughput levels
Validate performance at target rate
```

### 3. Server Protection
```
Control request rate
Avoid overwhelming server
```

### 4. Production Simulation
```
Simulate production traffic rate
Match real-world usage
```

## Best Practices

1. **Set realistic rates**: Based on actual requirements
2. **Monitor actual throughput**: Verify with listeners
3. **Consider response time**: Slow responses reduce achieved rate
4. **Use with multiple threads**: Spreads load across threads
5. **Combine with other timers**: Use for overall rate control

## Tips

1. **Per-minute value**: Throughput is samples per minute
2. **Global control**: Affects all threads in test plan
3. **Adjust based on load**: Lower throughput if server slows
4. **Monitor in Listener**: Check actual vs target throughput
5. **Avoid oversubscription**: Don't set higher than server can handle

## Common Issues

### Issue: Throughput Not Achieved
**Cause**: Response time too long
**Solution**: Reduce throughput or improve server performance

### Issue: Uneven Distribution
**Cause**: Variable response times
**Solution**: This is normal, timer does best effort

### Issue: Too Much Pausing
**Cause**: Throughput too low for number of threads
**Solution**: Reduce threads or increase throughput

## Comparison with Other Rate Control

| Method | Scope | Granularity |
|--------|-------|-------------|
| Constant Throughput Timer | Test Plan | Overall rate |
| Pacing (Plugin) | Thread | Per-thread rate |
| Think Timers | Request | Per-request delay |
| Thread Groups | Group | Fixed user count |

## Example: API Rate Limiting

```
// API allows 100 requests/minute
create_jmeter_element with:
- elementType: "constantthroughputtimer"
- elementName: "API限速-100次/分钟"
- properties:
  - ThroughputConstant.throughput: "100"

// Multiple threads share this rate
// Thread Group with 10 threads
// Each thread gets ~10 requests/minute
```

## Example: Production Traffic Simulation

```
// Production has ~600 requests/minute
create_jmeter_element with:
- elementType: "constantthroughputtimer"
- elementName: "模拟生产流量"
- properties:
  - ThroughputConstant.throughput: "600"

// Thread Group with 20 users
// Distributes 600 req/min across 20 users
```

## Notes

- Throughput is samples per minute (not seconds)
- Applies to all threads in test plan
- Works by adding pauses between samples
- Best effort - actual may vary with response time
- Good for API rate limiting
- Global scope affects entire test plan
