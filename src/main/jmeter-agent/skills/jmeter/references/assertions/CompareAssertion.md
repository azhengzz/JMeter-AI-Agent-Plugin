# Compare Assertion

## Description

Compare Assertion compares the results of two samplers to verify they produce the same response. Useful for testing API consistency or validating refactored endpoints.

## Parameters

| Property | Required | Description | Example |
|----------|----------|-------------|---------|
| `CompareAssertion.comparisonType` | Yes | Type of comparison | `Text`, `Regexp`, `Duration`, `Size` |
| `CompareAssertion.scope` | Yes | What to compare | `Sample`, `Subresults`, `Both` |

## Usage Examples

### Example 1: Compare Two API Responses

```
// First sampler (reference)
create_jmeter_element with:
- elementType: "httpsampler"
- elementName: "V1_API_获取用户"
- properties:
  - HTTPSampler.path: "/api/v1/user"

// Second sampler (compare to first)
create_jmeter_element with:
- elementType: "httpsampler"
- elementName: "V2_API_获取用户"
- properties:
  - HTTPSampler.path: "/api/v2/user"

// Compare assertion on second sampler
create_jmeter_element with:
- elementType: "compareassertion"
- elementName: "比较V1和V2响应"
- properties:
  - CompareAssertion.comparisonType: "Text"
```

### Example 2: Compare Response Times

```
create_jmeter_element with:
- elementType: "compareassertion"
- elementName: "比较响应时间"
- properties:
  - CompareAssertion.comparisonType: "Duration"
  - CompareAssertion.durationTolerance: "100"
```

### Example 3: Compare with Regex Pattern

```
create_jmeter_element with:
- elementType: "compareassertion"
- elementName: "正则比较响应"
- properties:
  - CompareAssertion.comparisonType: "Regexp"
  - CompareAssertion.regex: "<user>.*</user>"
```

## Comparison Types

| Type | Description | Use Case |
|------|-------------|----------|
| `Text` | Exact text match | Response content identical |
| `Regexp` | Regex pattern match | Response matches pattern |
| `Duration` | Compare response times | Performance comparison |
| `Size` | Compare response sizes | Size comparison |
| `Document` | Compare XML/JSON | Structured comparison |

## Scope Options

| Scope | Description |
|-------|-------------|
| `Sample` | Compare main sample only |
| `Subresults` | Compare sub-samples only |
| `Both` | Compare both main and sub-samples |

## How It Works

1. **Setup**: Create reference sampler
2. **Compare**: Add Compare Assertion to second sampler
3. **Execute**: Both samplers run
4. **Validate**: Responses are compared
5. **Result**: Pass if comparison succeeds

## Use Cases

### 1. API Version Comparison
```
Compare v1 and v2 API responses
Ensure refactoring doesn't change output
```

### 2. A/B Testing
```
Compare responses from different servers
```

### 3. Cache Validation
```
Compare cached vs uncached response
```

### 4. Load Balancer Testing
```
Compare responses from different backend servers
```

## Best Practices

1. **Clean comparisons**: Normalize data before comparing
2. **Exclude dynamic fields**: Ignore timestamps, IDs
3. **Same thread**: Run samplers in same thread for comparison
4. **Debug mode**: Use View Results Tree to see differences
5. **Tolerance**: Use tolerance for duration/size comparisons

## Tips

1. **Use text comparison**: For exact match requirements
2. **Regex flexibility**: Use regex to ignore variable parts
3. **Duration tolerance**: Allow for small time differences
4. **Pre-process**: Use JSR223 to normalize responses
5. **Scope correctly**: Choose appropriate comparison scope

## Common Issues

### Issue: Comparison Fails
**Cause**: Responses differ (timestamps, whitespace, etc.)
**Solution**: Use regex or pre-process to normalize

### Issue: Samplers Not Compared
**Cause**: Samplers in different threads
**Solution**: Place in same thread or use test variable

### Issue: Dynamic Content
**Cause**: Responses contain dynamic data
**Solution**: Use regex or post-process to normalize

## Example: Normalizing Responses Before Comparison

```
// First sampler
create_jmeter_element with:
- elementType: "httpsampler"
- elementName: "API_v1"
  // ... properties ...

// Normalize response
create_jmeter_element with:
- elementType: "jsr223postprocessor"
- elementName: "标准化V1响应"
- properties:
  - script: |
    // Remove dynamic fields
    def json = new groovy.json.JsonSlurper().parseText(prev.getResponseDataAsString())
    json.remove('timestamp')
    json.remove('requestId')
    vars.put("v1_response", groovy.json.JsonOutput.toJson(json))

// Second sampler with same normalization
create_jmeter_element with:
- elementType: "httpsampler"
- elementName: "API_v2"
  // ... properties ...

// Normalize and compare
create_jmeter_element with:
- elementType: "jsr223postprocessor"
- elementName: "标准化V2响应并比较"
- properties:
  - script: |
    def json = new groovy.json.JsonSlurper().parseText(prev.getResponseDataAsString())
    json.remove('timestamp')
    json.remove('requestId')
    def v2Response = groovy.json.JsonOutput.toJson(json)

    def v1Response = vars.get("v1_response")
    if (v1Response != v2Response) {
        AssertionResult.setFailure(true)
        AssertionResult.setFailureMessage("Responses differ")
    }
```

## Notes

- Compares results of two samplers
- Samplers must execute in same test
- Text comparison is exact match
- Use regex for flexible comparison
- Duration comparison allows tolerance
