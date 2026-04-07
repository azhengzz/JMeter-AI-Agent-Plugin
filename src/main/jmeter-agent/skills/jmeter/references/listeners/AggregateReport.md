# Aggregate Report

## Description

Aggregate Report provides detailed statistics for each sampler, including throughput, response times, and error percentages. Shows cumulative results at test end.

## Parameters

| Property | Required | Description | Example |
|----------|----------|-------------|---------|
| `ResultCollector.error_logging` | No | Log errors to file | `true` or `false` |
| `filename` | No | File to save results | `results/aggregate.jtl` |

## Usage Examples

### Example 1: Basic Aggregate Report

```
create_jmeter_element with:
- elementType: "aggregatereport"
- elementName: "聚合报告"
- properties:
  - ResultCollector.error_logging: "false"
```

### Example 2: Save to File

```
create_jmeter_element with:
- elementType: "aggregatereport"
- elementName: "聚合报告-保存"
- properties:
  - filename: "results/aggregate_results.jtl"
```

## Report Columns

| Column | Description |
|--------|-------------|
| `Label` | Sampler name |
| `# Samples` | Total sample count |
| `Average` | Average response time (ms) |
| `90% Line` | 90th percentile response time |
| `95% Line` | 95th percentile response time |
| `99% Line` | 99th percentile response time |
| `Min` | Minimum response time (ms) |
| `Max` | Maximum response time (ms) |
| `Error%` | Error percentage |
| `Throughput` | Samples per second |
| `Received KB/sec` | Kilobytes received per second |
| `Sent KB/sec` | Kilobytes sent per second |

## Percentile Lines

| Percentile | Meaning | Example |
|------------|---------|---------|
| 90% Line | 90% of requests ≤ this time | If 500ms, 90% complete in ≤500ms |
| 95% Line | 95% of requests ≤ this time | SLA often based on this |
| 99% Line | 99% of requests ≤ this time | Tail latency |

## Use Cases

### 1. Performance Analysis
```
Analyze response times
Check percentiles
Identify slow requests
```

### 2. SLA Validation
```
Verify 95% latency meets requirements
Check error rates
```

### 3. Comparison
```
Compare different samplers
Identify bottlenecks
```

### 4. Trend Analysis
```
Track performance over time
Save results for comparison
```

## Reading the Report

### Example Row
```
Label: GET_用户信息
Samples: 1000
Average: 234 ms
90% Line: 456 ms
95% Line: 567 ms
99% Line: 890 ms
Min: 123 ms
Max: 1234 ms
Error%: 0.5%
Throughput: 50.0/sec
```

Interpretation:
- 1000 requests executed
- Average response time: 234ms
- 90% of requests ≤ 456ms
- 95% of requests ≤ 567ms (common SLA metric)
- 99% of requests ≤ 890ms
- Fastest: 123ms, Slowest: 1234ms
- 0.5% error rate
- 50 requests per second

## Best Practices

1. **Focus on percentiles**: More meaningful than average
2. **Check error rate**: Should be near 0%
3. **Monitor throughput**: Verify expected rate
4. **Compare samplers**: Identify slow endpoints
5. **Save results**: Track over time

## Common Patterns

### Healthy Performance
```
Average: 200-500ms
95% Line: <2× Average
99% Line: <3× Average
Error%: <1%
Throughput: Stable
```

### Performance Issues
```
High 95%/99% lines: Outliers present
Increasing max: Degradation
High error%: Server issues
Low throughput: Bottleneck
```

## Performance Analysis

### Response Time Analysis
- **Average**: Typical response time
- **90% Line**: Most users experience
- **95% Line**: SLA target
- **99% Line**: Worst-case latency
- **Max**: Outlier (may ignore)

### Throughput Analysis
- **Higher is better**: Up to server limit
- **Compare samplers**: Find slow endpoints
- **Check limits**: Server saturation point

### Error Analysis
- **Should be low**: <1% is good
- **Investigate spikes**: Sudden errors
- **Check patterns**: Specific samplers failing

## Tips

1. **Sort by 95% Line**: Identify slowest requests
2. **Check error % first**: Filter out failed samplers
3. **Save results**: Compare test runs
4. **Use with Summary Report**: Get overview + details
5. **Export data**: Copy to spreadsheet for analysis

## Comparison: Aggregate vs Summary Report

| Feature | Aggregate Report | Summary Report |
|---------|-----------------|----------------|
| Percentiles | Yes | No |
| Per-sampler | Yes | Yes |
| File output | Yes | No |
| Detail | High | Medium |
| Use case | Detailed analysis | Quick overview |

## Example: Performance Report

```
+-------------------+-------+--------+------+------+------+------+------+------+------+-------+
| Label             | Samples| Average| 90%  | 95%  | 99%  | Min  | Max  | Error| Thruput|
+-------------------+-------+--------+------+------+------+------+------+------+------+-------+
| GET_用户列表      |  5000 |    234 |   456 |   567 |   890 |  123 | 1234 | 0.2% |  50.0 |
| POST_创建订单      |  1000 |    567 |   890 |  1234 |  1567 |   234 | 2345 | 0.5% |  10.0 |
| GET_订单详情      |  2000 |    189 |   345 |   456 |   567 |   98 |  890 | 0.1% |  20.0 |
+-------------------+-------+--------+------+------+------+------+------+------+------+-------+
```

Analysis:
- POST_创建订单 slowest (567ms avg)
- POST has highest error rate (0.5%)
- GET_订单详情 fastest (189ms avg)
- GET_用户列表 highest throughput (50/s)

## Notes

- Shows per-sampler statistics
- Includes percentiles (90%, 95%, 99%)
- Displays at test end
- Can save to file
- Low performance overhead
- Essential for performance analysis
