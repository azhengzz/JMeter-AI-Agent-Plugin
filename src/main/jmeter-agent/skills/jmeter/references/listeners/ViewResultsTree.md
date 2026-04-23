# View Results Tree

## Description

The View Results Tree shows a tree of all sample responses, allowing you to view the response for any sample. In addition to showing the response, you can see the time it took to get this response, and some response codes. Note that the Request panel only shows the headers added by JMeter. It does not show any headers (such as `Host`) that may be added by the HTTP protocol implementation.

> View Results Tree MUST NOT BE USED during load test as it consumes a lot of resources (memory and CPU). Use it only for either functional testing or during Test Plan debugging and Validation.

There are several ways to view the response, selectable by a drop-down box at the bottom of the left hand panel, including: CSS/JQuery Tester, Document, HTML, HTML (download resources), HTML Source formatted, JSON, JSON Path Tester, JSON JMESPath Tester, Regexp Tester, Text, XML, XPath Tester, and Boundary Extractor Tester.

> Starting with version 3.2 the number of entries in the View is restricted to the value of the property `view.results.tree.max_results` which defaults to 500 entries. The old behaviour can be restored by setting the property to 0. Beware, that this might consume a lot of memory.

## Parameters

| Property | Required | Default | Description | Example |
|----------|----------|---------|-------------|---------|
| `filename` | No | — | File path to save test results | `"results/test_results.jtl"` |
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

### Example 1: Basic Result Viewer

```
create_jmeter_element with:
- elementType: "viewresultstree"
- elementName: "查看结果树"
- properties:
  - ResultCollector.error_logging: "false"
```

### Example 2: Save Results to File with Save Configuration

```
create_jmeter_element with:
- elementType: "viewresultstree"
- elementName: "保存测试结果"
- properties:
  - filename: "results/test_results.jtl"
  - saveConfig:
    - time: "true"
    - latency: "true"
    - timestamp: "true"
    - success: "true"
    - label: "true"
    - code: "true"
    - xml: "false"
    - fieldNames: "true"
```

### Example 3: Error Logging Only

```
create_jmeter_element with:
- elementType: "viewresultstree"
- elementName: "错误日志记录"
- properties:
  - ResultCollector.error_logging: "true"
  - filename: "errors/error_log.jtl"
  - saveConfig:
    - xml: "true"
    - success: "true"
    - responseData: "true"
    - responseHeaders: "true"
    - requestHeaders: "true"
```

## Best Practices

1. **Use only during development**: View Results Tree consumes a lot of resources (memory and CPU); do not use during load tests
2. **Disable for production load tests**: Remove or disable this listener before running performance tests
3. **Save to file instead of displaying**: Use `filename` to write results to a file for later analysis
4. **Use error logging to reduce overhead**: Set `ResultCollector.error_logging` to `"true"` to only save failed samples
5. **Limit displayed entries**: The `view.results.tree.max_results` property (default 500) limits entries to avoid memory issues

## Notes

- MUST NOT BE USED during load testing due to high resource consumption
- The Request panel only shows headers added by JMeter, not protocol-level headers like `Host`
- Response data larger than 200K will not be displayed; change `view.results.tree.max_size` to adjust
- The number of displayed entries is limited by `view.results.tree.max_results` (default 500)
- If no `content-type` is provided, content will not be displayed in Response Data panels
- Multiple renderers are available: Text, HTML, JSON, XML, Regexp Tester, JSON Path Tester, XPath Tester, etc.
- The search function uses the Java regular expression engine (not the ORO engine used by the Regular Expression Extractor)
