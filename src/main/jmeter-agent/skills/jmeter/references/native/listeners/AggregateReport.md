# Aggregate Report
> **Source**: Apache JMeter built-in component (ships with JMeter, no extra install needed)

## Description

The aggregate report creates a table row for each differently named request in your test. For each request, it totals the response information and provides request count, min, max, average, error rate, approximate throughput (request/second) and Kilobytes per second throughput. Once the test is done, the throughput is the actual through for the duration of the entire test.

The throughput is calculated from the point of view of the sampler target (e.g. the remote server in the case of HTTP samples). JMeter takes into account the total time over which the requests have been generated. If other samplers and timers are in the same thread, these will increase the total time, and therefore reduce the throughput value. So two identical samplers with different names will have half the throughput of two samplers with the same name. It is important to choose the sampler names correctly to get the best results from the Aggregate Report.

Calculation of the Median and 90% Line (90th percentile) values requires additional memory. JMeter now combines samples with the same elapsed time, so far less memory is used. However, for samples that take more than a few seconds, the probability is that fewer samples will have identical times, in which case more memory will be needed. Note you can use this listener afterwards to reload a CSV or XML results file which is the recommended way to avoid performance impacts. See the Summary Report for a similar Listener that does not store individual samples and so needs constant memory.

> Starting with JMeter 2.12, you can configure the 3 percentile values you want to compute by setting properties: `aggregate_rpt_pct1` (defaults to 90th percentile), `aggregate_rpt_pct2` (defaults to 95th percentile), `aggregate_rpt_pct3` (defaults to 99th percentile).

## Parameters

| Property | Required | Default | Description | Example |
|----------|----------|---------|-------------|---------|
| `filename` | No | — | File path to save test results | `"results/aggregate.jtl"` |
| `ResultCollector.error_logging` | No | `false` | Log errors to file | `"true"` |
| `ResultCollector.success_only_logging` | No | `false` | Only log successful samples | `"true"` |
| `saveConfig` | No | — | Configuration for which fields to save in results (XML/CSV format). Contains child boolean properties controlling save behavior. | See saveConfig properties |
| `saveConfig.time` | No | `true` | Save elapsed time | `"true"` |
| `saveConfig.latency` | No | `true` | Save latency | `"true"` |
| `saveConfig.timestamp` | No | `true` | Save timestamp | `"true"` |
| `saveConfig.success` | No | `true` | Save success flag | `"true"` |
| `saveConfig.label` | No | `true` | Save sample label | `"true"` |
| `saveConfig.code` | No | `true` | Save response code | `"true"` |
| `saveConfig.message` | No | `true` | Save response message | `"true"` |
| `saveConfig.threadName` | No | `true` | Save thread name | `"true"` |
| `saveConfig.dataType` | No | `true` | Save data type | `"true"` |
| `saveConfig.encoding` | No | `false` | Save encoding | `"true"` |
| `saveConfig.assertions` | No | `true` | Save assertion results (XML only) | `"true"` |
| `saveConfig.subresults` | No | `true` | Save sub-results (XML only) | `"true"` |
| `saveConfig.responseData` | No | `false` | Save response data (XML only) | `"true"` |
| `saveConfig.samplerData` | No | `false` | Save sampler data (XML only) | `"true"` |
| `saveConfig.xml` | No | `false` | Save in XML format (false = CSV) | `"true"` |
| `saveConfig.fieldNames` | No | `true` | Print field names in CSV header | `"true"` |
| `saveConfig.responseHeaders` | No | `false` | Save response headers (XML only) | `"true"` |
| `saveConfig.requestHeaders` | No | `false` | Save request headers (XML only) | `"true"` |
| `saveConfig.assertionResultsFailureMessage` | No | `true` | Save assertion failure messages | `"true"` |
| `saveConfig.bytes` | No | `true` | Save bytes read | `"true"` |
| `saveConfig.sentBytes` | No | `true` | Save bytes sent | `"true"` |
| `saveConfig.url` | No | `true` | Save URL | `"true"` |
| `saveConfig.fileName` | No | `false` | Save file name (for ResultSaver) | `"true"` |
| `saveConfig.hostname` | No | `false` | Save hostname | `"true"` |
| `saveConfig.threadCounts` | No | `true` | Save active/total thread counts | `"true"` |
| `saveConfig.sampleCount` | No | `false` | Save sample and error count | `"true"` |
| `saveConfig.idleTime` | No | `true` | Save idle time | `"true"` |
| `saveConfig.connectTime` | No | `true` | Save connect time | `"true"` |

## Usage Examples

### Example 1: Basic Aggregate Report

```
create_jmeter_element with:
- elementType: "aggregatereport"
- elementName: "聚合报告"
- properties:
  - ResultCollector.error_logging: "false"
```

### Example 2: Save Results to File

```
create_jmeter_element with:
- elementType: "aggregatereport"
- elementName: "聚合报告-保存结果"
- properties:
  - filename: "results/aggregate_results.jtl"
  - saveConfig:
    - xml: "false"
    - time: "true"
    - latency: "true"
    - success: "true"
    - label: "true"
    - code: "true"
    - fieldNames: "true"
```

### Example 3: Error Logging with Save Configuration

```
create_jmeter_element with:
- elementType: "aggregatereport"
- elementName: "错误日志记录"
- properties:
  - filename: "results/errors.jtl"
  - ResultCollector.error_logging: "true"
  - saveConfig:
    - xml: "true"
    - time: "true"
    - success: "true"
    - responseData: "true"
    - responseHeaders: "true"
    - requestHeaders: "true"
```

## Best Practices

1. **Focus on percentiles**: More meaningful than average for understanding user experience
2. **Save results to file**: Use `filename` to write results to CSV/XML for post-test analysis, avoiding memory overhead during the test
3. **Check error rate first**: Should be near 0%; investigate if above 1%
4. **Use CSV format**: Set `saveConfig.xml` to `"false"` for smaller file sizes and faster writes
5. **Choose sampler names correctly**: Identical sampler names are aggregated together; use distinct names for different endpoints

## Notes

- Times are in milliseconds
- The throughput is calculated from the sampler target point of view
- Two identical samplers with different names will have half the throughput of two samplers with the same name
- Percentile values (90%, 95%, 99%) are configurable via JMeter properties
- Loading results from a saved file after the test is recommended to avoid performance impacts
- The Summary Report uses less memory as it does not store individual samples
