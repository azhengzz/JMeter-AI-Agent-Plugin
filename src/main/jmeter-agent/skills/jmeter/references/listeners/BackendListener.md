# Backend Listener

## Description

Backend Listener sends test results to external backends for monitoring and analysis. Supports Graphite, InfluxDB, and other time-series databases.

## Parameters

| Property | Required | Description | Example |
|----------|----------|-------------|---------|
| `BackendListener.backend_graphite` | Yes | Backend implementation | `Graphite`, `InfluxDB` |
| `BackendListener.graphite.host` | Yes* | Backend server host | `graphite.example.com` |
| `BackendListener.graphite.port` | Yes* | Backend server port | `2003` |
| `BackendListener.graphite.rootMetricsPrefix` | No | Metrics prefix | `jmeter.results` |
| `BackendListener.samplersList` | No | Specific samplers to track | Empty = all |
| `BackendListener.summaryOnly` | No | Send summary only | `true` or `false` |

## Usage Examples

### Example 1: Graphite Backend

```
create_jmeter_element with:
- elementType: "backendlistener"
- elementName: "发送到Graphite"
- properties:
  - BackendListener.backend_graphite: "Graphite"
  - BackendListener.graphite.host: "graphite.example.com"
  - BackendListener.graphite.port: "2003"
  - BackendListener.graphite.rootMetricsPrefix: "jmeter.test"
```

### Example 2: InfluxDB Backend

```
create_jmeter_element with:
- elementType: "backendlistener"
- elementName: "发送到InfluxDB"
- properties:
  - BackendListener.backend_graphite: "InfluxDB"
  - BackendListener.graphite.host: "influxdb.example.com"
  - BackendListener.graphite.port: "8086"
  - BackendListener.graphite.rootMetricsPrefix: "jmeter"
```

### Example 3: Summary Only

```
create_jmeter_element with:
- elementType: "backendlistener"
- elementName: "发送摘要到Graphite"
- properties:
  - BackendListener.backend_graphite: "Graphite"
  - BackendListener.graphite.host: "graphite.example.com"
  - BackendListener.graphite.port: "2003"
  - BackendListener.summaryOnly: "true"
```

## Supported Backends

| Backend | Protocol | Port | Use Case |
|---------|----------|------|----------|
| `Graphite` | Carbon/Plaintext | 2003 | Time-series monitoring |
| `InfluxDB` | InfluxDB Line Protocol | 8086 | Modern metrics storage |
| `Custom` | Custom implementation | Varies | Specialized backends |

## Metrics Sent

| Metric | Description |
|--------|-------------|
| `responseTime` | Response time in ms |
| `success` | Success count |
| `failure` | Failure count |
| `bytes` | Bytes received |
| `sentBytes` | Bytes sent |
| `activeThreads` | Active thread count |

## Use Cases

### 1. Real-time Monitoring
```
Monitor test execution in real-time
Dashboards showing live results
Alert on threshold breaches
```

### 2. Historical Analysis
```
Store results in time-series DB
Compare test runs over time
Trend analysis
```

### 3. Integration
```
Send to monitoring systems
Grafana dashboards
PagerDuty alerts
```

### 4. Production Monitoring
```
Continuous monitoring
Long-running tests
Production APM integration
```

## Best Practices

1. **Check backend capacity**: Ensure backend can handle load
2. **Use summary only**: Reduce data volume if needed
3. **Filter samplers**: Only track critical endpoints
4. **Monitor network**: Check for network issues
5. **Test connectivity**: Verify backend is reachable

## Tips

1. **Real-time graphs**: Use Grafana with Graphite/InfluxDB
2. **Batching**: Summary mode reduces network traffic
3. **Prefix naming**: Use descriptive prefixes
4. **Server location**: Place backend close to JMeter
5. **Async sending**: Non-blocking to test execution

## Graphite Metrics Format

```
jmeter.test.GET_users.all.avg.responseTime 234 1234567890
jmeter.test.GET_users.all.count 1000 1234567890
jmeter.test.GET_users.all.success 990 1234567890
jmeter.test.GET_users.all.failure 10 1234567890
```

## InfluxDB Line Format

```
jmeter_test,operation=GET_users,success=true avgResponseTime=234i,count=1000i 1234567890000000000
```

## Configuration Examples

### Grafana + Graphite Setup

```
1. Install Graphite with Carbon
2. Configure Backend Listener in JMeter
3. Add Graphite data source in Grafana
4. Create dashboards with metrics
```

### InfluxDB + Grafana Setup

```
1. Install InfluxDB
2. Configure Backend Listener
3. Add InfluxDB source in Grafana
4. Build performance dashboards
```

## Performance Impact

| Mode | Impact | When to Use |
|------|--------|-------------|
| All samples | Medium | Short tests, detailed analysis |
| Summary only | Low | Long tests, overview |
| Filtered samplers | Low-Medium | Specific endpoint monitoring |

## Common Issues

### Issue: Backend Not Receiving Data
**Cause**: Network or configuration issue
**Solution**: Check host, port, firewall

### Issue: High Test Overhead
**Cause**: Sending all samples
**Solution**: Use summary only or filter samplers

### Issue: Metrics Not Appearing
**Cause**: Incorrect prefix or naming
**Solution**: Verify rootMetricsPrefix format

## Notes

- Sends data asynchronously
- Minimal impact on test performance
- Requires external backend service
- Summary mode reduces data volume
- Useful for production monitoring
