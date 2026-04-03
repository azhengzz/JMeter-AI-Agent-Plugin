# Thread Group

## Description

Thread Group is the starting point of a JMeter test plan. It defines the number of virtual users, loop count, and test execution behavior. Each thread represents a virtual user. Thread groups can control concurrent user loading, execution duration, startup delay, and other core test parameters, supporting various scenarios like performance testing, stress testing, and load testing.

## Parameters

| Property | Required | Description | Example |
|----------|----------|-------------|---------|
| `ThreadGroup.num_threads` | Yes | Number of virtual users to simulate | `10` |
| `ThreadGroup.ramp_time` | Yes | Time (seconds) to start all threads | `5` |
| `ThreadGroup.on_sample_error` | No | Behavior on sampler error | `continue` |
| `LoopController.continue_forever` | No | Loop infinitely | `false` |
| `LoopController.loops` | Yes | Number of loops (-1 for infinite) | `10` |
| `ThreadGroup.scheduler` | No | Enable scheduler for time-bound testing | `true` |
| `ThreadGroup.duration` | No | Test duration in seconds (requires scheduler) | `300` |
| `ThreadGroup.delay` | No | Startup delay in seconds (requires scheduler) | `10` |

### Error Handling Options

| Value | Description |
|-------|-------------|
| `continue` | Continue test, ignore error |
| `startnextloop` | Start next loop of current thread |
| `stopthread` | Stop current thread |
| `stoptest` | Stop test after current samples complete |
| `stoptestnow` | Stop test immediately |

## Thread Group Types

| Type | elementType | Description |
|------|-------------|-------------|
| Thread Group | `threadgroup` | Regular thread group for standard performance testing |
| Setup Thread Group | `setupthreadgroup` | Runs before main thread groups, for initialization |
| Teardown Thread Group | `teardownthreadgroup` | Runs after main thread groups, for cleanup |

## Usage Examples

### Example 1: Basic Thread Group

```
create_jmeter_element with:
- elementType: "threadgroup"
- elementName: "用户登录场景-100并发"
- properties:
  - ThreadGroup.num_threads: "100"
  - ThreadGroup.ramp_time: "10"
  - LoopController.loops: "5"
  - LoopController.continue_forever: "false"
  - ThreadGroup.on_sample_error: "continue"
```

### Example 2: Duration-Based Thread Group

```
create_jmeter_element with:
- elementType: "threadgroup"
- elementName: "持续压测-10分钟"
- properties:
  - ThreadGroup.num_threads: "50"
  - ThreadGroup.ramp_time: "30"
  - ThreadGroup.scheduler: "true"
  - ThreadGroup.duration: "600"
  - ThreadGroup.delay: "5"
  - LoopController.loops: "-1"
  - LoopController.continue_forever: "true"
```

### Example 3: Setup Thread Group

```
create_jmeter_element with:
- elementType: "setupthreadgroup"
- elementName: "初始化测试数据"
- properties:
  - ThreadGroup.num_threads: "1"
  - ThreadGroup.ramp_time: "1"
  - LoopController.loops: "1"
  - ThreadGroup.on_sample_error: "continue"
```

### Example 4: Teardown Thread Group

```
create_jmeter_element with:
- elementType: "teardownthreadgroup"
- elementName: "清理测试数据"
- properties:
  - ThreadGroup.num_threads: "1"
  - ThreadGroup.ramp_time: "1"
  - LoopController.loops: "1"
  - ThreadGroup.on_sample_error: "continue"
```

## Parameter Details

### Number of Threads (users)
- Virtual user count, each thread runs test plan independently
- Recommendation: Set based on actual business concurrency requirements

### Ramp-Up Period (seconds)
- Time required for all threads to start
- Calculation: Ramp-Up Period ÷ Number of Threads = interval between each thread start
- Example: 10 threads, Ramp-Up of 5 seconds → one thread starts every 0.5 seconds

### Loop Count
- Number of times each thread executes the test plan
- Check "Loop forever" for continuous execution until manual stop or scheduler duration

### Scheduler
- Enable to precisely control test duration
- Commonly used for long-running stability tests

### Duration
- Test duration in seconds (requires scheduler enabled)
- Useful for time-bound tests

### Startup Delay
- Delay before starting thread group (requires scheduler enabled)
- Useful for coordinating multiple thread groups

## Best Practices

1. **Meaningful names**: `用户登录场景-100并发`, `持续压测-10分钟`
2. **Appropriate ramp-up**: Avoid sudden load spikes
3. **Use scheduler**: For time-bound tests, prefer duration over loop count
4. **Error handling**: Set `continue` to ignore isolated failures
5. **Setup/Teardown**: Use for test data initialization and cleanup
