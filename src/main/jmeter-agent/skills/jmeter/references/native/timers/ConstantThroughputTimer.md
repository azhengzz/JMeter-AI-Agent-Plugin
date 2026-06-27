# Constant Throughput Timer
> **Source**: Apache JMeter built-in component (ships with JMeter, no extra install needed)

## Description

This timer introduces variable pauses, calculated to keep the total throughput (in terms of samples per minute) as close as possible to a given figure. Of course the throughput will be lower if the server is not capable of handling it, or if other timers or time-consuming test elements prevent it.

N.B. although the Timer is called the Constant Throughput timer, the throughput value does not need to be constant. It can be defined in terms of a variable or function call, and the value can be changed during a test. The value can be changed in various ways: using a counter variable, using a `__jexl3`, `__groovy` function to provide a changing value, or using the remote BeanShell server to change a JMeter property.

Note that the throughput value should not be changed too often during a test - it will take a while for the new value to take effect.

## Parameters

| Property | Required | Default | Description | Example |
|----------|----------|---------|-------------|---------|
| `throughput` | Yes | `0` | Target throughput in samples per minute. Throughput we want the timer to try to generate. | `"60"` |
| `calcMode` | Yes | `0` | Scope for throughput calculation. See Calculation Modes below. | `"0"` |

### Calculation Modes

The `calcMode` property determines how throughput is calculated across threads:

| Value | Mode | Description |
|-------|------|-------------|
| `0` | This thread only | Each thread will try to maintain the target throughput independently. The overall throughput will be proportional to the number of active threads. |
| `1` | All active threads | The target throughput is divided amongst all the active threads in all Thread Groups. Each thread will delay as needed, based on when it last ran. In this case, each other Thread Group will need a Constant Throughput Timer with the same settings. |
| `2` | All active threads in current thread group | The target throughput is divided amongst all the active threads in the group. Each thread will delay as needed, based on when it last ran. |
| `3` | All active threads (shared) | Each thread is delayed based on when any thread last ran. The shared algorithm should generate a more accurate overall transaction rate. |
| `4` | All active threads in current thread group (shared) | Each thread is delayed based on when any thread in the group last ran. Useful for per-thread-group throughput control. |

The shared and non-shared algorithms both aim to generate the desired throughput, and will produce similar results. The shared algorithm should generate a more accurate overall transaction rate. The non-shared algorithm should generate a more even spread of transactions across threads.

## Usage Examples

### Example 1: 60 Requests Per Minute (1 per second)

```
create_jmeter_element with:
- elementType: "constantthroughputtimer"
- elementName: "限速-60次/分钟"
- properties:
  - throughput: "60"
  - calcMode: "0"
```

### Example 2: Shared Across All Threads

```
create_jmeter_element with:
- elementType: "constantthroughputtimer"
- elementName: "全局限速-100次/分钟"
- properties:
  - throughput: "100"
  - calcMode: "3"
```

### Example 3: Thread Group Based Throughput

```
create_jmeter_element with:
- elementType: "constantthroughputtimer"
- elementName: "线程组限速-300次/分钟"
- properties:
  - throughput: "300"
  - calcMode: "4"
```

### Example 4: Simulate Production Traffic

```
create_jmeter_element with:
- elementType: "constantthroughputtimer"
- elementName: "模拟生产流量-600次/分钟"
- properties:
  - throughput: "600"
  - calcMode: "1"
```

## Best Practices

1. **Choose appropriate calcMode**: Select based on your throughput target scope (per-thread vs global)
2. **Monitor actual throughput**: Verify with Aggregate Report listener to confirm target is achieved
3. **Consider response time**: Slow responses reduce the achieved rate
4. **Use realistic rates**: Based on actual production requirements
5. **Shared modes for consistency**: Use shared modes (3, 4) for more consistent overall pacing
6. **Throughput is per minute**: Remember the throughput value is samples per minute, not per second

## Notes

- Throughput value is samples per minute (not seconds)
- The throughput value does not need to be constant; it can use variables or functions
- Do not change the throughput value too often during a test; it takes time for new values to take effect
- Different calcMode values change pacing behavior significantly
- Shared modes (3, 4) provide more accurate overall transaction rates
- Works by adding pauses between samples
