# Duration Assertion

## Description

Duration Assertion validates that the response time of a sampler meets specified criteria. It's used to ensure API responses are within acceptable time limits.

## Parameters

| Property | Required | Description | Example |
|----------|----------|-------------|---------|
| `DurationAssertion.duration` | Yes | Maximum allowed duration in milliseconds | `3000` |

## Usage Examples

### Example 1: Assert Response Under 3 Seconds

```
create_jmeter_element with:
- elementType: "durationassertion"
- elementName: "断言_响应时间小于3秒"
- properties:
  - DurationAssertion.duration: "3000"
```

### Example 2: Fast API Response

```
create_jmeter_element with:
- elementType: "durationassertion"
- elementName: "断言_API响应小于500ms"
- properties:
  - DurationAssertion.duration: "500"
```

### Example 3: SLA Validation

```
create_jmeter_element with:
- elementType: "transactioncontroller"
- elementName: "完整交易流程"

create_jmeter_element with:
- elementType: "durationassertion"
- elementName: "断言_交易流程小于5秒"
- properties:
  - DurationAssertion.duration: "5000"
```

## Duration Values

| Duration | Use Case |
|----------|----------|
| `100-500ms` | High-performance APIs |
| `500-1000ms` | Standard APIs |
| `1000-3000ms` | Complex operations |
| `3000+ms` | Heavy computations |

## How It Works

1. **Measure**: Records sampler execution time
2. **Compare**: Checks if time <= specified duration
3. **Pass**: If response time is within limit
4. **Fail**: If response time exceeds limit

## Use Cases

### 1. API Performance SLA
```
Ensure API responses meet service level agreements
```

### 2. User Experience
```
Ensure pages load within acceptable time
```

### 3. Regression Testing
```
Detect performance degradation over time
```

### 4. Transaction Validation
```
Validate complete user journey duration
```

## Best Practices

1. **Realistic limits**: Set durations based on actual requirements
2. **Baseline first**: Establish baseline before setting limits
3. **Percentile-based**: Consider using 95th percentile values
4. **Environment-aware**: Adjust for test vs production
5. **Transaction level**: Assert on complete flows, not just individual calls

## Tips

1. **Gradual tightening**: Start loose, tighten over time
2. **Network conditions**: Account for network latency
3. **Server state**: Consider server load variations
4. **Monitor trends**: Track duration trends over time
5. **Separate SLAs**: Different SLAs for different operations

## Common Duration Values by API Type

| API Type | Expected Duration | Assertion Value |
|----------|------------------|-----------------|
| Simple GET | < 100ms | 200 |
| Complex GET | < 500ms | 1000 |
| POST/PUT | < 300ms | 500 |
| Search | < 1000ms | 2000 |
| Report generation | < 5000ms | 10000 |
| File upload/download | Varies | Based on size |

## Monitoring Failed Assertions

When duration assertion fails:
1. Check server metrics (CPU, memory)
2. Review database query performance
3. Check network latency
4. Verify concurrent user count
5. Analyze response size

## Example: Complete API Test with Assertions

```
create_jmeter_element with:
- elementType: "httpsampler"
- elementName: "GET_用户信息"

// Response assertion
create_jmeter_element with:
- elementType: "responseassertion"
- elementName: "断言_状态码200"
- properties:
  - Assertion.test_string: "200"

// Duration assertion
create_jmeter_element with:
- elementType: "durationassertion"
- elementName: "断言_响应时间小于1秒"
- properties:
  - DurationAssertion.duration: "1000"

// JSON assertion
create_jmeter_element with:
- elementType: "jsonpathassertion"
- elementName: "断言_包含用户ID"
- properties:
  - JSON_PATH_ASSERTION.jsonPath: "$.userId"
```

## Notes

- Duration is measured in milliseconds
- Includes complete sampler execution time
- Assertion fails if duration > specified value
- Use with Response Assertion for complete validation
- Consider network conditions when setting limits
