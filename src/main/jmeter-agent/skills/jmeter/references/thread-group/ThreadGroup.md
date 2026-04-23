# Thread Group

## Description

A Thread Group defines a pool of users that will execute a particular test case against your server. In the Thread Group GUI, you can control the number of users simulated (number of threads), the ramp up time (how long it takes to start all the threads), the number of times to perform the test, and optionally, a start and stop time for the test.

Each thread represents a virtual user that runs the test plan independently. Thread groups can control concurrent user loading, execution duration, startup delay, and other core test parameters, supporting various scenarios like performance testing, stress testing, and load testing.

**Scheduler behavior:** When using the scheduler, JMeter runs the thread group until either the number of loops is reached or the duration/end-time is reached — whichever occurs first. The condition is only checked between samples; when the end condition is reached, that thread will stop. JMeter does not interrupt samplers which are waiting for a response, so the end time may be delayed arbitrarily.

**See also:** [Setup Thread Group](#thread-group-types) and [Teardown Thread Group](#thread-group-types).

## Parameters

| Property | Required | Default | Description | Example |
|----------|----------|---------|-------------|---------|
| `ThreadGroup.num_threads` | Yes | `1` | Number of users to simulate. Each thread executes the test plan independently. | `100` |
| `ThreadGroup.ramp_time` | No | `1` | How long JMeter should take to get all the threads started. If there are 10 threads and a ramp-up time of 100 seconds, then each thread will begin 10 seconds after the previous thread started, for a total time of 100 seconds to get the test fully up to speed. **Note:** The first thread will always start directly, so if you configured one thread, the ramp-up time is effectively zero. For the same reason, the tenth thread in the above example will actually be started after 90 seconds and not 100 seconds. | `10` |
| `ThreadGroup.on_sample_error` | No | `continue` | Determines what happens if a sampler error occurs, either because the sample itself failed or an assertion failed. See [Error Handling Options](#error-handling-options). | `continue` |
| `ThreadGroup.main_controller` | Yes | — | Loop controller for this thread group (Object type, contains nested properties: `LoopController.loops`, `LoopController.continue_forever`). | — |
| `LoopController.loops` | Yes | `1` | Number of times to perform the test case. Alternatively, `-1` can be used causing the test to run until manually stopped or end of the thread lifetime is reached. | `10` |
| `LoopController.continue_forever` | No | `false` | Whether to loop infinitely. When `true`, the thread group runs until manually stopped or scheduler duration is reached. | `false` |
| `ThreadGroup.same_user_on_next_iteration` | No | `true` | If selected (`true`), cookie and cache data from the first sampler response are used in subsequent requests (requires a global Cookie and Cache Manager respectively). If not selected (`false`), cookie and cache data from the first sampler response are not used in subsequent requests. **Note:** If not selected, a new connection will be opened between iterations which will result in increased response times and consume more resources (memory and cpu). | `true` |
| `ThreadGroup.delayedStart` | No | `false` | If selected (`true`), threads are created only when the appropriate proportion of the ramp-up time has elapsed. This is most appropriate for tests with a ramp-up time that is significantly longer than the time to execute a single thread. I.e. where earlier threads finish before later ones start. If not selected (`false`), all threads are created when the test starts (they then pause for the appropriate proportion of the ramp-up time). This is the original default, and is appropriate for tests where threads are active throughout most of the test. | `false` |
| `ThreadGroup.scheduler` | No | `false` | If selected (`true`), confines Thread operation time to the given bounds (duration and startup delay). | `true` |
| `ThreadGroup.duration` | No | — | If the scheduler checkbox is selected, one can choose a relative end time. JMeter will use this to calculate the End Time. Value in seconds. | `600` |
| `ThreadGroup.delay` | No | — | If the scheduler checkbox is selected, one can choose a relative startup delay. JMeter will use this to calculate the Start Time. Value in seconds. | `5` |

### Error Handling Options

Determines what happens if a sampler error occurs, either because the sample itself failed or an assertion failed. The possible choices for `ThreadGroup.on_sample_error`:

| Value | Description |
|-------|-------------|
| `continue` | Ignore the error and continue with the test |
| `startnextloop` | Ignore the error, start next loop and continue with the test |
| `stopthread` | Current thread exits |
| `stoptest` | The entire test is stopped at the end of any current samples |
| `stoptestnow` | The entire test is stopped abruptly. Any current samplers are interrupted if possible |

## Thread Group Types

| Type | elementType | Description |
|------|-------------|-------------|
| Thread Group | `threadgroup` | Regular thread group for standard performance testing |
| Setup Thread Group | `setupthreadgroup` | Runs before main thread groups. Used for pre-test initialization actions. |
| Teardown Thread Group | `teardownthreadgroup` | Runs after main thread groups. Used for post-test cleanup actions. Note: By default, tearDown will not run if the test is gracefully shutdown. To enable it, check "Run tearDown Thread Groups after shutdown of main threads" in the Test Plan element. |

## Usage Examples

### Example 1: Basic Thread Group

```
create_jmeter_element with:
- elementType: "threadgroup"
- elementName: "用户登录场景-100并发"
- properties:
  - ThreadGroup.num_threads: "100"
  - ThreadGroup.ramp_time: "10"
  - ThreadGroup.on_sample_error: "continue"
  - ThreadGroup.main_controller:
    - LoopController.loops: "5"
    - LoopController.continue_forever: "false"
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
  - ThreadGroup.main_controller:
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
  - ThreadGroup.on_sample_error: "continue"
  - ThreadGroup.main_controller:
    - LoopController.loops: "1"
```

### Example 4: Teardown Thread Group

```
create_jmeter_element with:
- elementType: "teardownthreadgroup"
- elementName: "清理测试数据"
- properties:
  - ThreadGroup.num_threads: "1"
  - ThreadGroup.ramp_time: "1"
  - ThreadGroup.on_sample_error: "continue"
  - ThreadGroup.main_controller:
    - LoopController.loops: "1"
```

## Best Practices

1. **Appropriate ramp-up**: Avoid sudden load spikes. A common rule is ramp-up ≥ number of threads
2. **Use scheduler for duration-based tests**: Prefer `scheduler=true` with `duration` over high loop counts for time-bound tests
3. **Error handling**: Set `on_sample_error` to `continue` to tolerate isolated failures, or `stoptestnow` for critical validation
4. **Setup/Teardown**: Use Setup Thread Group for test data initialization and Teardown Thread Group for cleanup
5. **Delayed start**: Enable `delayedStart` when ramp-up is long relative to individual thread execution time

## Notes

- **Validation mode**: Since JMeter 3.0, you can validate a Thread Group by right-clicking and selecting "Validate". This runs the Thread Group with 1 thread, 1 iteration, no timers, and startup delay set to 0.
- **Scheduler limitation**: JMeter does not interrupt samplers that are waiting for a response. The actual end time may be delayed if a sampler is still running when the scheduled end time is reached.
- **tearDown behavior**: By default, tearDown Thread Groups will not run if the test is gracefully shutdown. Check "Run tearDown Thread Groups after shutdown of main threads" in the Test Plan element to enable this.
- **Ramp-up edge case**: The first thread always starts immediately regardless of ramp-up time. With a single thread, the ramp-up time is effectively zero.
