# Stepping Thread Group

## Description

The Stepping Thread Group (from the Custom Thread Groups plugin) provides step-based load generation, allowing you to precisely control how threads ramp up and ramp down in discrete batches. Unlike the standard Thread Group which ramps up linearly, the Stepping Thread Group lets you define:

- An initial burst of threads
- Subsequent batches of threads added at regular intervals
- A hold time (flight time) at peak load
- Gradual thread shutdown in batches

This is particularly useful for load testing scenarios where you need to observe system behavior under incrementally increasing load, or simulate realistic user arrival patterns.

**Lifecycle phases:**
1. **Initial delay** — wait before starting any threads
2. **Burst start** — first batch of threads starts immediately
3. **Step ramp-up** — additional batches start at regular intervals, with optional per-step ramp-up
4. **Flight time** — all threads running at peak load
5. **Step ramp-down** — threads stop in batches at regular intervals

## Parameters

| Property | Required | Default | Description | Example |
|----------|----------|---------|-------------|---------|
| `ThreadGroup.num_threads` | Yes | `1` | Total number of virtual users (threads) to simulate. | `100` |
| `Threads initial delay` | No | `0` | Initial delay in seconds before the first batch of threads starts. | `5` |
| `Start users count` | No | `0` | Number of threads to start per step during ramp-up. If `0`, defaults to total thread count (all threads start at once). | `10` |
| `Start users count burst` | No | `0` | Number of threads to start in the first (burst) batch. If `0` or negative, defaults to `Start users count`. Useful for starting more threads initially than in subsequent steps. | `20` |
| `Start users period` | No | `30` | Time interval in seconds between each thread-start step. | `30` |
| `rampUp` | No | `0` | Ramp-up time in seconds within each step. Threads within a step are evenly distributed across this time. | `5` |
| `flighttime` | No | `0` | Hold time in seconds after all threads have started and before ramp-down begins. | `60` |
| `Stop users period` | No | `0` | Time interval in seconds between each thread-stop step. | `10` |
| `Stop users count` | No | `0` | Number of threads to stop per step during ramp-down. If `0`, defaults to total thread count (all threads stop at once). | `10` |
| `ThreadGroup.on_sample_error` | No | `continue` | Behavior on sampler error. See [Error Handling Options](#error-handling-options). | `continue` |
| `ThreadGroup.main_controller` | Yes | — | Loop controller for this thread group (contains `LoopController.loops`, `LoopController.continue_forever`). | — |
| `LoopController.loops` | Yes | `1` | Number of times to perform the test case. `-1` for infinite. | `-1` |
| `LoopController.continue_forever` | No | `false` | Whether to loop infinitely. | `true` |

### Error Handling Options

| Value | Description |
|-------|-------------|
| `continue` | Ignore the error and continue with the test |
| `startnextloop` | Ignore the error, start next loop and continue with the test |
| `stopthread` | Current thread exits |
| `stoptest` | The entire test is stopped at the end of any current samples |
| `stoptestnow` | The entire test is stopped abruptly. Any current samplers are interrupted if possible |

## Usage Examples

### Example 1: Step Load Ramp-Up

Gradually increase from 0 to 100 users in steps of 10, with 30 seconds between each step:

```
create_jmeter_element with:
- elementType: "steppingthreadgroup"
- elementName: "阶梯加压场景-100并发"
- properties:
  - ThreadGroup.num_threads: "100"
  - Threads initial delay: "5"
  - Start users count burst: "10"
  - Start users count: "10"
  - Start users period: "30"
  - rampUp: "5"
  - flighttime: "60"
  - Stop users count: "100"
  - Stop users period: "10"
  - ThreadGroup.on_sample_error: "continue"
  - ThreadGroup.main_controller:
    - LoopController.loops: "-1"
    - LoopController.continue_forever: "true"
```

**Timeline:** Wait 5s → 10 threads (burst) → every 30s add 10 more threads with 5s ramp-up → hold 60s at 100 → stop all at once.

### Example 2: Burst Start with Gradual Ramp-Down

Start with 20 threads immediately, add 5 more every 10 seconds up to 50, then gradually stop:

```
create_jmeter_element with:
- elementType: "steppingthreadgroup"
- elementName: "突发启动-渐进停止"
- properties:
  - ThreadGroup.num_threads: "50"
  - Threads initial delay: "0"
  - Start users count burst: "20"
  - Start users count: "5"
  - Start users period: "10"
  - rampUp: "2"
  - flighttime: "120"
  - Stop users count: "5"
  - Stop users period: "15"
  - ThreadGroup.on_sample_error: "continue"
  - ThreadGroup.main_controller:
    - LoopController.loops: "-1"
    - LoopController.continue_forever: "true"
```

**Timeline:** 20 threads immediately → every 10s add 5 threads with 2s ramp-up → hold 120s at 50 → every 15s stop 5 threads.

### Example 3: Stress Test with All-at-Once Start

All 200 threads start simultaneously, hold for 5 minutes, then stop all at once:

```
create_jmeter_element with:
- elementType: "steppingthreadgroup"
- elementName: "瞬时并发-200用户"
- properties:
  - ThreadGroup.num_threads: "200"
  - Threads initial delay: "0"
  - Start users count: "0"
  - Start users period: "0"
  - flighttime: "300"
  - Stop users count: "0"
  - Stop users period: "0"
  - ThreadGroup.on_sample_error: "stoptestnow"
  - ThreadGroup.main_controller:
    - LoopController.loops: "-1"
    - LoopController.continue_forever: "true"
```

**Timeline:** 200 threads start immediately → hold 300s → all stop at once. When `Start users count` and `Stop users count` are both `0`, they default to total thread count.

## Best Practices

1. **Set infinite loops for timed tests**: Use `LoopController.loops: -1` with `LoopController.continue_forever: true` when running step-based tests, letting the thread lifecycle control duration
2. **Reasonable step size**: Keep `Start users count` reasonable relative to total threads — typically 5-20% of total
3. **Burst for initial baseline**: Use `Start users count burst` to establish an initial load before stepping up
4. **Monitor during flight time**: The `flighttime` phase is ideal for observing steady-state system behavior at peak load
5. **Symmetric ramp-up/down**: Consider matching `Start users count` and `Stop users count` for symmetric load patterns

## Notes

- This component requires the **Custom Thread Groups** plugin (part of JMeter Plugins Standard Set)
- When `Start users count` is `0`, it defaults to the total number of threads (all start at once)
- When `Start users count burst` is `0` or negative, it defaults to `Start users count`
- When `Stop users count` is `0`, it defaults to the total number of threads (all stop at once)
- The `rampUp` time is divided evenly among threads within each step, not across all threads globally
