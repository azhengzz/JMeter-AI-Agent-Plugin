# View Results Tree

## Description

View Results Tree displays detailed results of samplers, including requests, responses, headers, and other debugging information. Essential for test development and debugging.

## Parameters

| Property | Required | Description | Example |
|----------|----------|-------------|---------|
| `ResultCollector.error_logging` | No | Log errors to file | `true` or `false` |
| `filename` | No | File to save results | `results.jtl` |
| `saveConfig` | No | What data to save | See save options |

## Usage Examples

### Example 1: Basic Result Viewer

```
create_jmeter_element with:
- elementType: "viewresultstree"
- elementName: "查看结果树"
- properties:
  - ResultCollector.error_logging: "false"
```

### Example 2: Save Results to File

```
create_jmeter_element with:
- elementType: "viewresultstree"
- elementName: "保存结果"
- properties:
  - filename: "results/test_results.jtl"
  - saveConfig:
    - time: "true"
    - latency: "true"
    - timestamp: "true"
    - success: "true"
    - response: "true"
```

### Example 3: Error Logging Only

```
create_jmeter_element with:
- elementType: "viewresultstree"
- elementName: "错误日志"
- properties:
  - ResultCollector.error_logging: "true"
  - filename: "errors/error_log.jtl"
```

## Save Configuration Options

| Option | Description | Default |
|--------|-------------|---------|
| `time` | Elapsed time | No |
| `latency` | Latency | No |
| `timestamp` | Timestamp | No |
| `success` | Success status | Yes |
| `response` | Response data | No |
| `responseHeaders` | Response headers | No |
| `requestHeaders` | Request headers | No |
| `bytes` | Bytes sent/received | No |
| `url` | URL | No |
| `fileName` | Filename (for files) | No |

## Display Modes

| Mode | Description | Use For |
|------|-------------|---------|
| `Sampler Result` | Basic sample info | Quick overview |
| `Request` | Full request details | Debugging requests |
| `Response Data` | Response body | Checking response content |
| `Response Headers` | Response headers | Checking headers |
| `Sampler` | All details | Complete view |

## Common Uses

### 1. Test Development
```
View requests and responses
Verify test logic
Debug issues
```

### 2. Parameter Debugging
```
Check variable substitution
Verify request data
Validate headers
```

### 3. Response Validation
```
Check response content
Test assertions
Verify extraction
```

### 4. Performance Debugging
```
Check response times
Identify slow requests
Analyze failures
```

## Features

### Request Tab
- URL and method
- Request headers
- Request body
- Parameters

### Response Data Tab
- Response body
- Formatted JSON/XML
- Image preview
- Download option

### Response Headers Tab
- All response headers
- Status codes
- Content types

### Assertion Results Tab
- Assertion status
- Failure messages
- Expected vs actual

## Best Practices

### During Development
1. **Enable for debugging**: Use during test creation
2. **Check all tabs**: Verify requests, responses, headers
3. **Test assertions**: Verify assertion results
4. **Save problematic samples**: For later analysis

### During Load Testing
1. **Disable or limit**: High overhead
2. **Save to file**: Instead of displaying
3. **Use error logging**: Only save failures
4. **Monitor selectively**: Don't keep open during test

## Tips

1. **Development**: Essential for building tests
2. **Production**: Disable during load tests
3. **Save options**: Configure what to save
4. **Search**: Use text search in responses
5. **Debug mode**: Check variables and assertions

## Performance Impact

| Scenario | Impact | Recommendation |
|----------|--------|----------------|
| Development | Low | Always use |
| Small load test (<10 users) | Medium | Use sparingly |
| Large load test (100+ users) | High | Disable or use file |
| Production simulation | Very High | Disable completely |

## Alternatives for Load Testing

| Listener | Overhead | Use Case |
|----------|----------|----------|
| View Results Tree | Very High | Debugging only |
| Summary Report | Low | Quick overview |
| Aggregate Report | Low | Detailed statistics |
| Backend Listener | Low | Production monitoring |

## Example: Debugging Setup

```
// Thread Group for testing
create_jmeter_element with:
- elementType: "threadgroup"
- elementName: "调试线程组"
- properties:
  - ThreadGroup.num_threads: "1"
  - LoopController.loops: "1"

// Add View Results Tree
create_jmeter_element with:
- elementType: "viewresultstree"
- elementName: "调试结果"

// Test samplers...
// Run test and examine results
```

## Notes

- Very high performance overhead
- Essential for test development
- Disable during production load tests
- Can save results to file for later analysis
- Shows complete request/response details
- Use for debugging and validation
