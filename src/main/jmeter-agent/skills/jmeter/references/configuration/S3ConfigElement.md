# S3 Connection Configuration

## Description

S3 Connection Configuration defines the connection parameters for S3-compatible object storage services (e.g. MinIO, AWS S3, Ceph). It creates an `S3Service` instance stored in JMeter variables, which is referenced by S3 Sampler via the unique identifier name.

The connection is initialized on test start (`testStarted`) and closed on test end (`testEnded`).

## Parameters

| Property | Required | Default | Description | Example |
|----------|----------|---------|-------------|---------|
| `S3ConfigElement.s3UidName` | Yes | `""` | Unique identifier for this S3 connection. Must be globally unique across the test plan. | `"my-s3-conn"` |
| `S3ConfigElement.s3EndPoint` | Yes | `""` | S3 service endpoint URL. | `"http://127.0.0.1:9000"` |
| `S3ConfigElement.s3Region` | No | `""` | S3 region name. | `"us-east-1"` |
| `S3ConfigElement.s3AccessKeyId` | Yes | `""` | Access key ID for authentication. | `"minioadmin"` |
| `S3ConfigElement.s3SecretAccessKey` | Yes | `""` | Secret access key for authentication. | `"minioadmin"` |

## Usage Examples

### Example 1: MinIO Local Connection

```
create_jmeter_element with:
- elementType: "s3configelement"
- elementName: "MinIO连接配置"
- properties:
  - S3ConfigElement.s3UidName: "my-s3-conn"
  - S3ConfigElement.s3EndPoint: "http://127.0.0.1:9000"
  - S3ConfigElement.s3Region: "us-east-1"
  - S3ConfigElement.s3AccessKeyId: "minioadmin"
  - S3ConfigElement.s3SecretAccessKey: "minioadmin"
```

### Example 2: AWS S3 Connection

```
create_jmeter_element with:
- elementType: "s3configelement"
- elementName: "AWS S3连接"
- properties:
  - S3ConfigElement.s3UidName: "aws-s3-prod"
  - S3ConfigElement.s3EndPoint: "https://s3.amazonaws.com"
  - S3ConfigElement.s3Region: "ap-southeast-1"
  - S3ConfigElement.s3AccessKeyId: "${ACCESS_KEY}"
  - S3ConfigElement.s3SecretAccessKey: "${SECRET_KEY}"
```

## Notes

- The `s3UidName` must be globally unique — duplicate names will cause a conflict error
- This component must be placed before the S3 Sampler in the test plan
- The S3 Sampler references this connection by matching its `uidName` with the `S3Sampler.uidName` property
- Connection lifecycle is tied to the test: created on start, closed on end
