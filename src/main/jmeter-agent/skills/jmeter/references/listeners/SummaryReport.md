# Summary Report

## Description

The summary report creates a table row for each differently named request in your test. This is similar to the Aggregate Report, except that it uses less memory.

The throughput is calculated from the point of view of the sampler target (e.g. the remote server in the case of HTTP samples). JMeter takes into account the total time over which the requests have been generated. If other samplers and timers are in the same thread, these will increase the total time, and therefore reduce the throughput value. So two identical samplers with different names will have half the throughput of two samplers with the same name. It is important to choose the sampler labels correctly to get the best results from the Report.

## Parameters

| Property | Required | Default | Description | Example |
|----------|----------|---------|-------------|---------|
| `filename` | No | — | File path to save test results | `"results/summary.jtl"` |
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

### Example 1: Basic Summary Report

```
create_jmeter_element with:
- elementType: "summaryreport"
- elementName: "汇总报告"
- properties:
  - ResultCollector.error_logging: "false"
```

### Example 2: Save Results to File

```
create_jmeter_element with:
- elementType: "summaryreport"
- elementName: "汇总报告-保存结果"
- properties:
  - filename: "results/summary_results.jtl"
  - saveConfig:
    - xml: "false"
    - time: "true"
    - success: "true"
    - label: "true"
    - fieldNames: "true"
```

### Example 3: Error Logging Only

```
create_jmeter_element with:
- elementType: "summaryreport"
- elementName: "错误汇总"
- properties:
  - filename: "results/errors.jtl"
  - ResultCollector.error_logging: "true"
  - saveConfig:
    - xml: "true"
    - success: "true"
    - responseData: "true"
```

## Best Practices

1. **Use for quick overview**: Summary Report provides basic metrics with lower memory overhead than Aggregate Report
2. **Check error percentage first**: Should be near 0%; investigate if above 1%
3. **Look at standard deviation**: High deviation indicates inconsistent response times
4. **Monitor throughput**: Verify the expected request rate is being achieved
5. **Use CSV format for saving**: Smaller file sizes and faster writes than XML

## Notes

- Uses less memory than Aggregate Report because it does not store individual samples
- Does not show percentile values (90%, 95%, 99%); use Aggregate Report for those
- Shows standard deviation instead of percentiles
- Times are in milliseconds
- The throughput is calculated from the sampler target point of view
- Two identical samplers with different names will have half the throughput of two samplers with the same name
