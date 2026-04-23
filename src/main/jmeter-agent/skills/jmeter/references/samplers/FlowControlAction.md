# Flow Control Action

## Description

The Flow Control Action sampler is a sampler that is intended for use in a conditional controller. Rather than generate a sample, the test element either pauses or stops the selected target.

This sampler can also be useful in conjunction with the Transaction Controller, as it allows pauses to be included without needing to generate a sample. For variable delays, set the pause time to zero, and add a Timer as a child.

The "Stop" action stops the thread or test after completing any samples that are in progress. The "Stop Now" action stops the test without waiting for samples to complete; it will interrupt any active samples. If some threads fail to stop within the 5 second time-limit, a message will be displayed in GUI mode. You can try using the Stop command to see if this will stop the threads, but if not, you should exit JMeter. In CLI mode, JMeter will exit if some threads fail to stop within the 5 second time limit.

**Note:** The time to wait can be changed using the JMeter property `jmeterengine.threadstop.wait`. The time is given in milliseconds.

## Parameters

| Property | Required | Default | Description | Example |
|----------|----------|---------|-------------|---------|
| `ActionProcessor.action` | Yes | `0` | Action to perform. See Action Values table below. | `"1"` |
| `ActionProcessor.target` | No | `0` | Target for stop actions: `0`=Current Thread, `2`=All Threads (ignored for Pause and Go to next loop iteration). See Target Values table below. | `"0"` |
| `ActionProcessor.duration` | No | `""` | How long to pause for in milliseconds. Only used when action=Pause. | `"5000"` |

### Action Values

| Value | Name | Description |
|-------|------|-------------|
| `0` | Stop | Gracefully stop thread or test after completing in-progress samples |
| `1` | Pause | Pause for specified duration (milliseconds) |
| `2` | Stop Now | Immediately stop thread or test without waiting |
| `3` | Start Next Thread Loop | Start next iteration of thread loop |
| `4` | Go To Next Iteration Of Current Loop | Start next iteration of current loop |
| `5` | Break Current Loop | Exit current loop |

### Target Values

| Value | Name | Description |
|-------|------|-------------|
| `0` | Current Thread | Affect only the current thread |
| `2` | All Threads | Affect all threads in the test |

## Usage Examples

### Example 1: Pause (Think Time)

```
create_jmeter_element with:
- elementType: "flowcontrolaction"
- elementName: "暂停5秒"
- properties:
  - ActionProcessor.action: "1"
  - ActionProcessor.duration: "5000"
```

### Example 2: Stop Thread on Error

```
create_jmeter_element with:
- elementType: "flowcontrolaction"
- elementName: "错误时停止线程"
- properties:
  - ActionProcessor.action: "0"
  - ActionProcessor.target: "0"
```

### Example 3: Stop Entire Test

```
create_jmeter_element with:
- elementType: "flowcontrolaction"
- elementName: "停止整个测试"
- properties:
  - ActionProcessor.action: "2"
  - ActionProcessor.target: "2"
```

### Example 4: Restart Next Loop

```
create_jmeter_element with:
- elementType: "flowcontrolaction"
- elementName: "跳过本次迭代进入下一次"
- properties:
  - ActionProcessor.action: "3"
```

### Example 5: Break Current Loop

```
create_jmeter_element with:
- elementType: "flowcontrolaction"
- elementName: "退出当前循环"
- properties:
  - ActionProcessor.action: "5"
```

## Best Practices

1. **Use Pause for think time**: Simulate real user behavior with pauses between requests
2. **Stop Now for critical errors**: Use immediate termination when critical failures occur
3. **Stop for graceful shutdown**: Allow threads to finish current samples before stopping
4. **Combine with If Controller**: Use conditional flow control for error handling
5. **Use meaningful names**: Describe the flow control purpose clearly

## Notes

- Produces no sample result (returns null)
- Actions take effect immediately
- Duration is in milliseconds for Pause action
- Stop is graceful (allows cleanup); Stop Now is immediate (no cleanup)
- Loop actions work with Loop Controller and While Controller
- Target is ignored for Pause and Go to next loop iteration actions
