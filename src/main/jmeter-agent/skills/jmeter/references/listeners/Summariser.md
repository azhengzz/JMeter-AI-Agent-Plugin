# Summariser

## Description

Summariser provides periodic summary statistics during test execution, displaying live results in console output.

## Parameters

| Property | Required | Description | Example |
|----------|----------|-------------|---------|
| `summariser.out` | No | Output destination | `true` (console) or `false` |
| `summariser.name` | No | Summary label | Test Plan name |
| `summariser.interval` | No | Summary interval (seconds) | `30` |

## Usage Examples

### Example 1: Basic Summariser

```
create_jmeter_element with:
- elementType: "summariser"
- elementName: "测试摘要"
- properties:
  - summariser.out: "true"
```

### Example 2: Custom Interval

```
create_jmeter_element with:
- elementType: "summariser"
- elementName: "30秒摘要"
- properties:
  - summariser.out: "true"
  - summariser.interval: "30"
```

## Console Output Format

```
Generate Summary Results +      1 in 00:00:01 =    1.7/s Avg:   231 Min:   189 Max:   273 Err:     0 (0.00%) Active: 1 Started: 1
Generate Summary Results +      2 in 00:00:02 =    1.0/s Avg:   219 Min:   165 Max:   273 Err:     0 (0.00%) Active: 1 Started: 2
Generate Summary Results =      2 in 00:00:03 =    0.7/s Avg:   219 Min:   165 Max:   273 Err:     0 (0.00%)
```

## Output Fields

| Field | Description |
|-------|-------------|
| `+ 1` | Samples since last summary |
| `in 00:00:01` | Time elapsed since last summary |
| `= 1.7/s` | Throughput (samples per second) |
| `Avg: 231` | Average response time (ms) |
| `Min: 189` | Minimum response time (ms) |
| `Max: 273` | Maximum response time (ms) |
| `Err: 0` | Error count |
| `(0.00%)` | Error percentage |
| `Active: 1` | Active threads |
| `Started: 1` | Started threads |

## Use Cases

### 1. Live Test Monitoring
```
See test progress in real-time
Monitor error rates
Check throughput
```

### 2. Debugging
```
Verify test is running
Check for errors early
Monitor response times
```

### 3. Quick Health Check
```
At-a-glance test status
Identify issues quickly
No GUI needed
```

## Best Practices

1. **Always enable**: Good for test monitoring
2. **Check console**: Monitor during test
3. **Watch error rate**: Stop if errors spike
4. **Note throughput**: Verify expected rate
5. **Log output**: Redirect to file for analysis

## Tips

1. **Non-intrusive**: Minimal performance impact
2. **Always on**: No reason to disable
3. **Interval appropriate**: Default is fine
4. **Combine with others**: Use with other listeners
5. **Console focus**: Watch during test execution

## Output Frequency

| Interval | Use Case |
|----------|----------|
| 3 seconds | Default, good for most tests |
| 10 seconds | Less frequent updates |
| 30 seconds | Long-running tests |
| 60 seconds | Very long tests |

## Comparison with Other Listeners

| Listener | Real-time | Detail | Overhead |
|----------|-----------|--------|----------|
| Summariser | Yes | Low | Very Low |
| View Results Tree | Yes | Very High | Very High |
| Aggregate Report | End | High | Low |
| Summary Report | End | Medium | Low |

## Common Console Output Patterns

### Healthy Test
```
Err: 0 (0.00%) - No errors
Avg stable - Consistent performance
Throughput stable - Expected rate
```

### Problematic Test
```
Err: 125 (15.3%) - High error rate
Avg increasing - Performance degradation
Active < Started - Threads stopped
```

## Example: Console Output Analysis

```
Generate Summary Results +    100 in 00:00:10 =   10.0/s Avg:   234 Min:   123 Max:   567 Err:     5 (5.00%) Active: 50 Started: 50
```

Analysis:
- 100 samples in 10 seconds = 10/s throughput
- Average response time: 234ms
- Response times vary: 123ms to 567ms
- 5 errors = 5% error rate (investigate!)
- 50 threads active, 50 started = all running

## Notes

- Minimal performance overhead
- Always enabled by default
- Outputs to console
- Shows summary statistics
- Good for real-time monitoring
- No file output (use file logger if needed)
