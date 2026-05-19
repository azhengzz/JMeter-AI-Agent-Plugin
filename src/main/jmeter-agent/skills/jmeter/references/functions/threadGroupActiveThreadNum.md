# __threadGroupActiveThreadNum

## Function Name
`__threadGroupActiveThreadNum`

## Category
Information

## Description
Returns the number of active (running) threads in the current thread group. Optionally rounds up to the nearest multiple of a given step value. Returns `"0"` if the thread group has not been initialized yet.

## Parameters

| Parameter | Description | Required | Default |
|-----------|-------------|----------|---------|
| Step size | If provided, the thread count is rounded up to the next multiple of this value | No | -- |

## Usage Examples

### Basic Usage
```
${__threadGroupActiveThreadNum}
```
Returns the current number of active threads in the thread group, e.g. `10`.

### With Step Rounding
```
${__threadGroupActiveThreadNum(4)}
```
If there are 5 active threads, rounds up to `8` (next multiple of 4).

### For Dynamic Concurrency Control
```
${__threadGroupActiveThreadNum()}
```
Can be used in If Controllers or Throughput Timers to make decisions based on the current thread count.

## Notes
- Returns `"0"` if called before the thread group is initialized (e.g. during test plan setup)
- Without a step parameter, returns the raw active thread count
- Step rounding formula: if thread count is not evenly divisible by step, rounds up to `count / step * step + step`
- This function is thread-group scoped, unlike `__threadNum` which returns the individual thread number

## Since
Custom (Gitee extension)

## Reference
- Source: `com.gitee.qa.jmeter.functions.ThreadGroupActiveThreadNum`
