# Ultimate Thread Group
> **Source**: Third-party plugin (jmeter-plugins "Custom Thread Groups", `kg.apc.jmeter`, must be installed separately)

## Description

The Ultimate Thread Group (from the Custom Thread Groups plugin) provides the most flexible thread scheduling among all thread group types. Instead of a single ramp-up profile, it uses a **table of schedule rows** where each row independently defines a batch of threads with its own timing:

- How many threads to start
- When to start (initial delay)
- How fast to ramp up (startup time)
- How long to hold at peak (hold load)
- How fast to ramp down (shutdown time)

Multiple rows run concurrently, enabling complex load patterns like wave testing, staggered starts, and mixed workload simulation.

**Each row lifecycle:**
1. Wait for **Initial Delay** seconds
2. Ramp up **Start Threads Count** threads over **Startup Time** seconds
3. Hold all threads for **Hold Load For** seconds
4. Ramp down over **Shutdown Time** seconds

Rows are independent — different batches can overlap, creating composite load shapes.

## Parameters

| Property | Required | Default | Description | Example |
|----------|----------|---------|-------------|---------|
| `ultimatethreadgroupdata` | Yes | — | Array of schedule rows. Each row has 5 fields (see below). | (see examples) |
| `ThreadGroup.on_sample_error` | No | `continue` | Behavior on sampler error. See [Error Handling Options](#error-handling-options). | `continue` |
| `ThreadGroup.main_controller` | Yes | — | Loop controller for this thread group (contains `LoopController.loops`, `LoopController.continue_forever`). | — |
| `LoopController.loops` | Yes | `1` | Number of loops (-1 for infinite). | `-1` |
| `LoopController.continue_forever` | No | `false` | Whether to loop infinitely. | `true` |

### Schedule Row Fields

Each row in `ultimatethreadgroupdata` contains:

| Field | Default | Description |
|-------|---------|-------------|
| Start Threads Count | `100` | Number of threads to start in this batch |
| Initial Delay | `0` | Delay in seconds before this batch starts |
| Startup Time | `30` | Ramp-up time in seconds for this batch |
| Hold Load For | `60` | Hold time in seconds at peak load for this batch |
| Shutdown Time | `10` | Ramp-down time in seconds for this batch |

### Error Handling Options

| Value | Description |
|-------|-------------|
| `continue` | Ignore the error and continue with the test |
| `startnextloop` | Ignore the error, start next loop and continue with the test |
| `stopthread` | Current thread exits |
| `stoptest` | The entire test is stopped at the end of any current samples |
| `stoptestnow` | The entire test is stopped abruptly. Any current samplers are interrupted if possible |

## Usage Examples

### Example 1: Wave Load Pattern

Two waves of 50 users each, staggered by 60 seconds:

```
create_jmeter_element with:
- elementType: "ultimatethreadgroup"
- elementName: "波浪压测-两波50用户"
- properties:
  - ultimatethreadgroupdata:
    - ["50", "0", "10", "60", "10"]
    - ["50", "60", "10", "60", "10"]
  - ThreadGroup.on_sample_error: "continue"
  - ThreadGroup.main_controller:
    - LoopController.loops: "-1"
    - LoopController.continue_forever: "true"
```

**Timeline:**
- Wave 1: 50 users start at 0s, ramp up 10s, hold 60s, ramp down 10s
- Wave 2: 50 users start at 60s, ramp up 10s, hold 60s, ramp down 10s
- Peak concurrent load: 100 users (at 60-70s when waves overlap)

### Example 2: Staircase Load

Gradually increase load in 4 steps:

```
create_jmeter_element with:
- elementType: "ultimatethreadgroup"
- elementName: "阶梯加压-4级"
- properties:
  - ultimatethreadgroupdata:
    - ["25", "0", "30", "0", "0"]
    - ["25", "30", "30", "0", "0"]
    - ["25", "60", "30", "0", "0"]
    - ["25", "90", "30", "120", "30"]
  - ThreadGroup.on_sample_error: "continue"
  - ThreadGroup.main_controller:
    - LoopController.loops: "-1"
    - LoopController.continue_forever: "true"
```

**Timeline:**
- 0-30s: 25 users ramp up
- 30-60s: 50 total (25 more ramp up)
- 60-90s: 75 total (25 more ramp up)
- 90-120s: 100 total (25 more ramp up)
- 120-240s: 100 users hold steady
- 240-270s: all ramp down

### Example 3: Spike Test

Baseline 20 users with a sudden spike of 80 users:

```
create_jmeter_element with:
- elementType: "ultimatethreadgroup"
- elementName: "突发流量-基线+峰值"
- properties:
  - ultimatethreadgroupdata:
    - ["20", "0", "5", "300", "5"]
    - ["80", "60", "2", "30", "2"]
  - ThreadGroup.on_sample_error: "continue"
  - ThreadGroup.main_controller:
    - LoopController.loops: "-1"
    - LoopController.continue_forever: "true"
```

**Timeline:**
- 0-5s: 20 baseline users ramp up
- 60-62s: 80 spike users ramp up (total: 100)
- 62-92s: spike holds
- 92-94s: spike users ramp down (back to 20)
- 20 users continue until 300s

## Best Practices

1. **Set infinite loops for timed tests**: Use `LoopController.loops: -1` with `LoopController.continue_forever: true` — let the row timings control thread lifecycle
2. **Plan row overlap carefully**: Total concurrent users is the sum of all active rows at any point in time
3. **Use multiple rows for complex patterns**: Wave tests, spike tests, and staircase loads are natural fits
4. **Keep Shutdown Time reasonable**: Avoid very long shutdown times that extend the test unnecessarily

## Notes

- This component requires the **Custom Thread Groups** plugin (part of JMeter Plugins Standard Set)
- The total thread count displayed in JMeter is the sum of all rows' `Start Threads Count`
- Rows execute concurrently — they are not sequential
- An alternative way to specify the schedule is via the `threads_schedule` JMeter property using the format: `spawn(count, delay, startup, hold, shutdown)` — e.g. `threads_schedule="spawn(10,1s,1s,1s,1s) spawn(20,2s,3s,1s,2s)"`
