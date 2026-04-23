# Backend Listener

## Description

The backend listener is an Asynchronous listener that enables you to plug custom implementations of BackendListenerClient. By default, a Graphite implementation is provided. It sends test results to external backends (Graphite, InfluxDB) for real-time monitoring and analysis.

## Parameters

| Property | Required | Default | Description | Example |
|----------|----------|---------|-------------|---------|
| `classname` | Yes | `"org.apache.jmeter.visualizers.backend.graphite.GraphiteBackendListenerClient"` | Backend listener client class name. Class of the BackendListenerClient implementation. | `"org.apache.jmeter.visualizers.backend.graphite.GraphiteBackendListenerClient"` |
| `QUEUE_SIZE` | No | `"5000"` | Size of the queue that holds the SampleResults while they are processed asynchronously | `"5000"` |
| `Arguments.arguments` | Yes | — | Backend-specific configuration parameters. An array of name/value pairs that configure the selected backend client implementation. | See examples |

### classname Enum Values

| Value | Description |
|-------|-------------|
| `org.apache.jmeter.visualizers.backend.graphite.GraphiteBackendListenerClient` | Sends metrics to Graphite using the Carbon protocol (default) |
| `org.apache.jmeter.visualizers.backend.influxdb.InfluxdbBackendListenerClient` | Writes directly to InfluxDB with a custom schema |
| `org.apache.jmeter.visualizers.backend.influxdb.InfluxDBRawBackendListenerClient` | Sends raw data to InfluxDB |

### Arguments.arguments Item Properties

Each item in the `Arguments.arguments` array has the following properties:

| Property | Required | Default | Description | Example |
|----------|----------|---------|-------------|---------|
| `Argument.name` | No | `""` | Backend-specific parameter name | `"graphiteHost"` |
| `Argument.value` | No | `""` | Backend-specific parameter value | `"graphite.example.com"` |
| `Argument.metadata` | No | `"="` | Metadata, always equals `=` | `"="` |

## Usage Examples

### Example 1: Graphite Backend

```
create_jmeter_element with:
- elementType: "backendlistener"
- elementName: "发送到Graphite"
- properties:
  - classname: "org.apache.jmeter.visualizers.backend.graphite.GraphiteBackendListenerClient"
  - QUEUE_SIZE: "5000"
  - Arguments.arguments:
    - Argument.name: "graphiteHost"
      Argument.value: "graphite.example.com"
    - Argument.name: "graphitePort"
      Argument.value: "2003"
    - Argument.name: "rootMetricsPrefix"
      Argument.value: "jmeter."
    - Argument.name: "summaryOnly"
      Argument.value: "false"
    - Argument.name: "samplersList"
      Argument.value: ""
    - Argument.name: "percentiles"
      Argument.value: "90;95;99"
```

### Example 2: InfluxDB Backend

```
create_jmeter_element with:
- elementType: "backendlistener"
- elementName: "发送到InfluxDB"
- properties:
  - classname: "org.apache.jmeter.visualizers.backend.influxdb.InfluxdbBackendListenerClient"
  - QUEUE_SIZE: "5000"
  - Arguments.arguments:
    - Argument.name: "influxdbMetricsSender"
      Argument.value: "org.apache.jmeter.visualizers.backend.influxdb.HttpMetricsSender"
    - Argument.name: "influxdbUrl"
      Argument.value: "http://influxdb.example.com:8086/write?db=jmeter"
    - Argument.name: "application"
      Argument.value: "MyApp"
    - Argument.name: "measurement"
      Argument.value: "jmeter"
    - Argument.name: "summaryOnly"
      Argument.value: "false"
    - Argument.name: "samplersList"
      Argument.value: ""
    - Argument.name: "percentiles"
      Argument.value: "90;95;99"
```

### Example 3: Summary Only Mode

```
create_jmeter_element with:
- elementType: "backendlistener"
- elementName: "摘要模式发送"
- properties:
  - classname: "org.apache.jmeter.visualizers.backend.graphite.GraphiteBackendListenerClient"
  - QUEUE_SIZE: "5000"
  - Arguments.arguments:
    - Argument.name: "graphiteHost"
      Argument.value: "graphite.example.com"
    - Argument.name: "graphitePort"
      Argument.value: "2003"
    - Argument.name: "rootMetricsPrefix"
      Argument.value: "jmeter.test."
    - Argument.name: "summaryOnly"
      Argument.value: "true"
```

## Best Practices

1. **Use summary only mode**: Reduces data volume for long-running tests; set `summaryOnly` to `"true"`
2. **Filter samplers**: Use `samplersList` to only track critical endpoints and reduce network traffic
3. **Set appropriate queue size**: Default 5000 is usually sufficient; increase if you see dropped samples
4. **Place backend close to JMeter**: Reduce network latency between JMeter and the backend server
5. **Test connectivity first**: Verify the backend is reachable before running the full test

## Notes

- Sends data asynchronously, so it has minimal impact on test performance
- Requires an external backend service (Graphite, InfluxDB, etc.) to be running
- The Graphite implementation supports both Text and Pickle metric senders
- Since JMeter 3.2, InfluxdbBackendListenerClient writes directly to InfluxDB with a custom schema
- InfluxDB 2 authentication token can be set via `influxdbToken` parameter
- The rootMetricsPrefix should end with a dot (`"."`) as JMeter does not add a separator automatically
