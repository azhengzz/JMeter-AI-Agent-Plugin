# Summary Report

## Description

Summary Report provides basic statistics for each sampler in a tabular format. Shows cumulative results at test end with less detail than Aggregate Report.

## Parameters

| Property | Required | Description | Example |
|----------|----------|-------------|---------|
| `ResultCollector.error_logging` | No | Log errors to file | `true` or `false` |
| `filename` | No | File to save results | `results/summary.jtl` |

## Usage Examples

### Example 1: Basic Summary Report

```
create_jmeter_element with:
- elementType: "statvisualizer"
- elementName: "汇总报告"
- properties:
  - ResultCollector.error_logging: "false"
```

### Example 2: Save to File

```
create_jmeter_element with:
- elementType: "statvisualizer"
- elementName: "汇总报告-保存"
- properties:
  - filename: "results/summary_results.jtl"
```

## Report Columns

| Column | Description |
|--------|-------------|
| `Label` | Sampler name |
| `# Samples` | Total sample count |
| `Average` | Average response time (ms) |
| `Deviation` | Standard deviation (ms) |
| `Error%` | Error percentage |
| `Throughput` | Samples per second |

## Report Display

```
+-------------------+-------+--------+-----------+--------+----------+
| Label             | Samples| Average| Deviation | Error% | Throughput|
+-------------------+-------+--------+-----------+--------+----------+
| GET_用户列表      |  5000  |   234  |    45.6   |  0.2%  |    50.0  |
| POST_创建订单      |  1000  |   567  |   123.4   |  0.5%  |    10.0  |
| GET_订单详情      |  2000  |   189  |    34.5   |  0.1%  |    20.0  |
+-------------------+-------+--------+-----------+--------+----------+
```

## Column Details

### Label
- Name of the sampler
- Groups related samples

### Samples
- Total count of requests
- Higher = more data collected

### Average
- Mean response time in milliseconds
- Lower is better
- Can be skewed by outliers

### Deviation
- Standard deviation in milliseconds
- Higher = more variation
- Consistent responses = low deviation

### Error%
- Percentage of failed requests
- Should be as low as possible
- Investigate if >1%

### Throughput
- Requests per second
- Higher = better performance
- Depends on server capacity

## Use Cases

### 1. Quick Overview
```
Fast summary of test results
At-a-glance performance metrics
```

### 2. Error Detection
```
Check error rates
Identify problematic samplers
```

### 3. Performance Comparison
```
Compare different samplers
Find slow requests
```

### 4. Basic Analysis
```
Understand overall performance
Track key metrics
```

## Best Practices

1. **Use for overview**: Quick summary of results
2. **Check error %**: Verify low error rate
3. **Compare samplers**: Identify performance issues
4. **Look at deviation**: High deviation = inconsistent
5. **Monitor throughput**: Verify expected rate

## Reading the Report

### Example Interpretation
```
Label: GET_用户信息
Samples: 5000
Average: 234 ms
Deviation: 45.6 ms
Error%: 0.2%
Throughput: 50.0/sec
```

Analysis:
- Good sample size (5000 requests)
- Reasonable response time (234ms)
- Consistent responses (45ms deviation)
- Low error rate (0.2%)
- Good throughput (50/sec)

## Comparison: Summary vs Aggregate Report

| Feature | Summary Report | Aggregate Report |
|---------|----------------|------------------|
| Percentiles | No | Yes (90%, 95%, 99%) |
| Deviation | Yes | No |
| Min/Max | No | Yes |
| Detail | Basic | Detailed |
| Use case | Quick overview | Deep analysis |
| Performance | Lower overhead | Slightly higher |

## Common Patterns

### Good Performance
```
Average: < 500ms
Deviation: < 100ms
Error%: < 1%
Throughput: High
```

### Performance Issues
```
Average: > 1000ms
Deviation: > 200ms
Error%: > 5%
Throughput: Low
```

## Tips

1. **Sort by Average**: Find slowest requests
2. **Check Deviation**: High = inconsistent
3. **Monitor Error%**: Should be minimal
4. **Compare tests**: Track over time
5. **Export data**: Copy to spreadsheet

## When to Use

### Use Summary Report When:
- Quick overview needed
- Basic metrics sufficient
- Lower overhead desired
- Error rate monitoring

### Use Aggregate Report When:
- Detailed analysis needed
- Percentiles important
- Min/Max values needed
- Full statistics required

## Example: Test Result Analysis

```
Summary Report:
+-------------------+-------+--------+-----------+--------+----------+
| Label             | Samples| Average| Deviation | Error% | Throughput|
+-------------------+-------+--------+-----------+--------+----------+
| GET_首页          |  2000  |   456  |   123.4   |  1.2%  |    20.0  |
| POST_登录         |   500  |   234  |    45.6   |  0.0%  |     5.0  |
| GET_用户列表      |  1000  |   789  |   234.5   |  5.5%  |    10.0  |
+-------------------+-------+--------+-----------+--------+----------+
```

Analysis:
- GET_用户列表 problematic (5.5% errors)
- GET_用户列表 slowest (789ms avg)
- GET_首页 high deviation (inconsistent)
- POST_登录 performing well

## Notes

- Basic statistics only
- No percentiles shown
- Lower overhead than Aggregate Report
- Good for quick overview
- Can save to file
- Shows at test end
