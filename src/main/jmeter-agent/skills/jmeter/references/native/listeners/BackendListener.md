# Backend Listener
> **Source**: Apache JMeter built-in component (ships with JMeter, no extra install needed)

## Description

The backend listener is an Asynchronous listener that enables you to plug custom implementations of BackendListenerClient. By default, a Graphite implementation is provided. It sends test results to external backends (Graphite, InfluxDB) for real-time monitoring and analysis.

## Parameters

| Property | Required | Default | Description | Example |
|----------|----------|---------|-------------|---------|
| `classname` | Yes | `"com.gitee.qa.jmeter.visualizers.backend.influxdb.InfluxdbBackendListenerClient"` | Backend listener client class name. Class of the BackendListenerClient implementation. | `"com.gitee.qa.jmeter.visualizers.backend.influxdb.InfluxdbBackendListenerClient"` |
| `QUEUE_SIZE` | No | `"5000"` | Size of the queue that holds the SampleResults while they are processed asynchronously | `"5000"` |
| `Arguments.arguments` | Yes | — | Backend-specific configuration parameters. An array of name/value pairs that configure the selected backend client implementation. | See examples |

### classname Enum Values

| Value | Description |
|-------|-------------|
| `org.apache.jmeter.visualizers.backend.graphite.GraphiteBackendListenerClient` | Sends metrics to Graphite using the Carbon protocol (default) |
| `org.apache.jmeter.visualizers.backend.influxdb.InfluxdbBackendListenerClient` | Standard InfluxDB backend client |
| `org.apache.jmeter.visualizers.backend.influxdb.InfluxDBRawBackendListenerClient` | Sends raw data to InfluxDB |
| `com.gitee.qa.jmeter.visualizers.backend.influxdb.InfluxdbBackendListenerClient` | Enhanced InfluxDB client with detailed error tracking (responseCode, rawResponseCode, responseMessage, responseBody, requestHeader, sampleData), custom TAG_ parameters, regex filtering (samplersRegex), and VictoriaMetrics compatibility with smart field truncation |

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
    - Argument.name: "graphiteMetricsSender"
      Argument.value: "org.apache.jmeter.visualizers.backend.graphite.TextGraphiteMetricsSender"
    - Argument.name: "graphiteHost"
      Argument.value: "graphite.example.com"
    - Argument.name: "graphitePort"
      Argument.value: "2003"
    - Argument.name: "rootMetricsPrefix"
      Argument.value: "jmeter."
    - Argument.name: "summaryOnly"
      Argument.value: "false"
    - Argument.name: "samplersList"
      Argument.value: "LoginAPI;HomePage;SearchAPI"
    - Argument.name: "useRegexpForSamplersList"
      Argument.value: "false"
    - Argument.name: "percentiles"
      Argument.value: "90;95;99"
```

### Example 2: Graphite with Regex Filter

```
create_jmeter_element with:
- elementType: "backendlistener"
- elementName: "发送到Graphite（正则过滤）"
- properties:
  - classname: "org.apache.jmeter.visualizers.backend.graphite.GraphiteBackendListenerClient"
  - QUEUE_SIZE: "5000"
  - Arguments.arguments:
    - Argument.name: "graphiteMetricsSender"
      Argument.value: "org.apache.jmeter.visualizers.backend.graphite.PickleGraphiteMetricsSender"
    - Argument.name: "graphiteHost"
      Argument.value: "graphite.example.com"
    - Argument.name: "graphitePort"
      Argument.value: "2004"
    - Argument.name: "rootMetricsPrefix"
      Argument.value: "jmeter.testing."
    - Argument.name: "samplersList"
      Argument.value: "API_.*|Service_.*"
    - Argument.name: "useRegexpForSamplersList"
      Argument.value: "true"
    - Argument.name: "percentiles"
      Argument.value: "90;95;99"
```

### Example 3: InfluxDB Backend (Enhanced)

```
create_jmeter_element with:
- elementType: "backendlistener"
- elementName: "发送到InfluxDB（增强版）"
- properties:
  - classname: "com.gitee.qa.jmeter.visualizers.backend.influxdb.InfluxdbBackendListenerClient"
  - QUEUE_SIZE: "5000"
  - Arguments.arguments:
    - Argument.name: "influxdbMetricsSender"
      Argument.value: "com.gitee.qa.jmeter.visualizers.backend.influxdb.HttpMetricsSender"
    - Argument.name: "influxdbUrl"
      Argument.value: "http://host_to_change:8086/write?db=jmeter"
    - Argument.name: "application"
      Argument.value: "jmeterTest"
    - Argument.name: "measurement"
      Argument.value: "jmeter"
    - Argument.name: "summaryOnly"
      Argument.value: "false"
    - Argument.name: "samplersRegex"
      Argument.value: ".*"
    - Argument.name: "percentiles"
      Argument.value: "90;95;99"
    - Argument.name: "testTitle"
      Argument.value: "Test name"
    - Argument.name: "eventTags"
      Argument.value: ""
```

### Example 4: InfluxDB Raw Backend

```
create_jmeter_element with:
- elementType: "backendlistener"
- elementName: "发送到InfluxDB（原始数据）"
- properties:
  - classname: "org.apache.jmeter.visualizers.backend.influxdb.InfluxDBRawBackendListenerClient"
  - QUEUE_SIZE: "5000"
  - Arguments.arguments:
    - Argument.name: "influxdbMetricsSender"
      Argument.value: "org.apache.jmeter.visualizers.backend.influxdb.HttpMetricsSender"
    - Argument.name: "influxdbUrl"
      Argument.value: "http://influxdb.example.com:8086/write?db=jmeter"
    - Argument.name: "influxdbToken"
      Argument.value: "my-token-for-influxdb2"
    - Argument.name: "measurement"
      Argument.value: "jmeter-raw"
```

### Example 5: Summary Only Mode

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

### Example 6: InfluxDB with Custom Tags and Regex Filter

```
create_jmeter_element with:
- elementType: "backendlistener"
- elementName: "发送到InfluxDB（带自定义标签和正则过滤）"
- properties:
  - classname: "com.gitee.qa.jmeter.visualizers.backend.influxdb.InfluxdbBackendListenerClient"
  - QUEUE_SIZE: "5000"
  - Arguments.arguments:
    - Argument.name: "influxdbUrl"
      Argument.value: "http://influxdb.example.com:8086/write?db=jmeter"
    - Argument.name: "application"
      Argument.value: "MyApp"
    - Argument.name: "samplersRegex"
      Argument.value: "API_.*|Service_.*"
    - Argument.name: "testTitle"
      Argument.value: "Production Load Test"
    - Argument.name: "eventTags"
      Argument.value: "production v1.0.0"
    - Argument.name: "TAG_environment"
      Argument.value: "production"
    - Argument.name: "TAG_version"
      Argument.value: "1.0.0"
    - Argument.name: "TAG_region"
      Argument.value: "us-west"
```

## Best Practices

1. **Filter samplers with regex**: Use `samplersRegex` to only track critical endpoints (e.g., `"API_.*|Service_.*"`) and reduce network traffic
2. **Use custom tags**: Add `TAG_*` parameters to organize metrics by environment, version, region, etc.
3. **Leverage error tracking**: The enhanced InfluxDB client captures detailed error info (responseBody, requestHeader, sampleData) - use Grafana to query these fields when debugging failures
4. **Set appropriate queue size**: Default 5000 is usually sufficient; increase if you see dropped samples
5. **Place backend close to JMeter**: Reduce network latency between JMeter and the backend server
6. **Test connectivity first**: Verify the backend is reachable before running the full test
7. **Use test annotations**: Set `testTitle` and `eventTags` to create Grafana annotations for test start/end events

## Backend Listener Client Parameters

### Graphite Backend Listener Client Parameters

| Parameter | Default | Description |
|-----------|---------|-------------|
| `graphiteMetricsSender` | `org.apache.jmeter.visualizers.backend.graphite.TextGraphiteMetricsSender` | Metrics sender implementation class (Text or Pickle protocol) |
| `graphiteHost` | `""` | Graphite server hostname or IP address |
| `graphitePort` | `2003` | Graphite server port (default: 2003 for plaintext protocol) |
| `rootMetricsPrefix` | `"jmeter."` | Prefix for all metrics (should end with a dot) |
| `summaryOnly` | `"true"` | Only send summary metrics, not per-sampler metrics |
| `samplersList` | `""` | List of sampler names to track (semicolon separated, empty = all) |
| `useRegexpForSamplersList` | `"false"` | Treat `samplersList` as a regular expression pattern |
| `percentiles` | `"90;95;99"` | Percentiles to calculate (semicolon separated) |

### InfluxdbBackendListenerClient Parameters (Enhanced)

**Key Feature - Detailed Error Tracking**: Automatically captures and stores detailed error information for failed requests, including responseCode, rawResponseCode, responseMessage, responseBody, requestHeader, and sampleData. Fields are smart-truncated at 4KB (VictoriaMetrics compatible) to preserve as much content as possible.

| Parameter | Default | Description |
|-----------|---------|-------------|
| `influxdbMetricsSender` | `org.apache.jmeter.visualizers.backend.influxdb.HttpMetricsSender` | Metrics sender implementation class |
| `influxdbUrl` | `"http://host_to_change:8086/write?db=jmeter"` | InfluxDB write URL with database name |
| `application` | `"application name"` | Application name used as tag in InfluxDB |
| `measurement` | `"jmeter"` | Measurement name in InfluxDB |
| `summaryOnly` | `"false"` | Only send summary metrics (true/false) |
| `samplersRegex` | `".*"` | Regex pattern to filter samplers (matches all by default) |
| `percentiles` | `"99;95;90"` | Percentiles to calculate (semicolon separated) |
| `testTitle` | `"Test name"` | Test title for annotations (Grafana) |
| `eventTags` | `""` | Tags for test annotations (space separated) |

#### Error Tracking Fields

When a request fails, the following fields are automatically captured as InfluxDB tags:

| Field | Description | Note |
|-------|-------------|------|
| `responseCode` | HTTP response code or error code | Always captured |
| `rawResponseCode` | Raw response code from server | Always captured |
| `responseMessage` | Response message or error message | Truncated at 4KB |
| `responseBody` | Response body content | Truncated at 4KB |
| `requestHeader` | Request headers | Truncated at 4KB |
| `sampleData` | Sample/request data | Truncated at 4KB |

**Smart Truncation**: When total tag length approaches 4KB limit, fields are sorted by size and truncated to preserve maximum information. Newlines are replaced with spaces.

#### Custom User Tags (Enhanced Version Only)

You can add custom tags by prefixing parameter names with `TAG_`. These tags will be included in all metrics sent to InfluxDB.

| Parameter | Example | Description |
|-----------|---------|-------------|
| `TAG_environment` | `production` | Environment name |
| `TAG_version` | `1.0.0` | Application version |
| `TAG_region` | `us-west` | Geographic region |
| `TAG_build` | `12345` | Build number |

### InfluxDBRawBackendListenerClient Parameters

| Parameter | Default | Description |
|-----------|---------|-------------|
| `influxdbMetricsSender` | `org.apache.jmeter.visualizers.backend.influxdb.HttpMetricsSender` | Metrics sender implementation class |
| `influxdbUrl` | `"http://host_to_change:8086/write?db=jmeter"` | InfluxDB write URL with database name |
| `influxdbToken` | `""` | Authentication token for InfluxDB 2.x |
| `measurement` | `"jmeter"` | Measurement name in InfluxDB |

**Note**: This client sends raw data for each sample (duration, latency, connectTime) without aggregation.

## Notes

- Sends data asynchronously, so it has minimal impact on test performance
- Requires an external backend service (Graphite, InfluxDB, etc.) to be running
- The Graphite implementation supports both Text and Pickle metric senders
- Since JMeter 3.2, InfluxdbBackendListenerClient writes directly to InfluxDB with a custom schema
- InfluxDB 2 authentication token can be set via `influxdbToken` parameter
- The rootMetricsPrefix should end with a dot (`"."`) as JMeter does not add a separator automatically
- `samplersRegex` uses Java regex pattern to filter which samplers to track
- Custom `TAG_*` parameters allow flexible tagging for better metric organization
- VictoriaMetrics compatible: tag values are truncated at 4KB to meet label size requirements
- **Enhanced error tracking**: Failed requests automatically capture responseCode, rawResponseCode, responseMessage, responseBody, requestHeader, and sampleData for debugging
