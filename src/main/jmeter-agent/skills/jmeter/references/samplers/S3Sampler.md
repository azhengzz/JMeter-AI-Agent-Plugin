# S3 Sampler

## Description

S3 Sampler performs operations against S3-compatible object storage services (MinIO, AWS S3, Ceph, etc.). It supports 10 operations covering bucket management and file CRUD.

The sampler must reference an S3 Connection Configuration by its unique identifier name. Response data is returned in JSON format with a unified structure: `{"success": boolean, "errorMessage": string|null, "data": object}`.

## Parameters

| Property | Required | Default | Description | Example |
|----------|----------|---------|-------------|---------|
| `S3Sampler.uidName` | Yes | `""` | S3 connection identifier. Must match a `s3UidName` defined in S3 Connection Configuration. | `"my-s3-conn"` |
| `S3Sampler.action` | Yes | `"LIST_ALL_BUCKETS"` | S3 operation to perform. | `"UPLOAD_FILE"` |
| `S3Sampler.Arguments` | Varies | — | Action-specific key-value arguments (see below). | — |

## Actions and Arguments

### Bucket Operations

| Action | Description | Required Arguments |
|--------|-------------|--------------------|
| `LIST_ALL_BUCKETS` | List all buckets | (none) |
| `CREATE_BUCKET` | Create a new bucket | `<bucket-name>` |
| `DELETE_BUCKET` | Delete a bucket | `<bucket-name>` |
| `BUCKET_EXISTS` | Check if a bucket exists | `<bucket-name>` |

### Object Operations

| Action | Description | Required Arguments | Optional Arguments |
|--------|-------------|--------------------|--------------------|
| `LIST_OBJECTS` | List objects in a bucket | `<bucket-name>` | `<prefix>` |
| `GET_FILE_METADATA` | Get file metadata | `<bucket-name>`, `<key>` | — |
| `FILE_EXISTS` | Check if a file exists | `<bucket-name>`, `<key>` | — |
| `UPLOAD_FILE` | Upload a local file | `<bucket-name>`, `<key>`, `<file-path>` | — |
| `DOWNLOAD_FILE` | Download a file | `<bucket-name>`, `<key>`, `<download-dir>` | `<download-file-name>`, `<discard-file>` |
| `DELETE_FILE` | Delete a file | `<bucket-name>`, `<key>` | — |

### Argument Keys

| Key | Required | Description | Example |
|-----|----------|-------------|---------|
| `<bucket-name>` | Yes (most actions) | Target bucket name | `"test-bucket"` |
| `<key>` | Yes (object actions) | Object key (file path in S3, do not start with `/`) | `"data/report.csv"` |
| `<file-path>` | Yes (upload) | Local file path to upload | `"/tmp/testfile.txt"` |
| `<download-dir>` | Yes (download) | Local directory to save downloaded file | `"/tmp/downloads"` |
| `<download-file-name>` | No | Custom filename for download (defaults to original) | `"result.csv"` |
| `<discard-file>` | No | Discard after download, measure transfer only (`"true"`/`"false"`) | `"true"` |
| `<prefix>` | No | Object key prefix filter for listing | `"data/"` |

## Usage Examples

### Example 1: List All Buckets

```
create_jmeter_element with:
- elementType: "s3sampler"
- elementName: "列出所有存储桶"
- properties:
  - S3Sampler.uidName: "my-s3-conn"
  - S3Sampler.action: "LIST_ALL_BUCKETS"
```

### Example 2: Upload File

```
create_jmeter_element with:
- elementType: "s3sampler"
- elementName: "上传测试文件"
- properties:
  - S3Sampler.uidName: "my-s3-conn"
  - S3Sampler.action: "UPLOAD_FILE"
  - S3Sampler.Arguments:
    - "<bucket-name>": "test-bucket"
    - "<key>": "data/report.csv"
    - "<file-path>": "/tmp/report.csv"
```

### Example 3: Download File with Discard (Bandwidth Test)

```
create_jmeter_element with:
- elementType: "s3sampler"
- elementName: "下载带宽测试"
- properties:
  - S3Sampler.uidName: "my-s3-conn"
  - S3Sampler.action: "DOWNLOAD_FILE"
  - S3Sampler.Arguments:
    - "<bucket-name>": "test-bucket"
    - "<key>": "data/largefile.bin"
    - "<download-dir>": "/tmp"
    - "<discard-file>": "true"
```

### Example 4: List Objects with Prefix Filter

```
create_jmeter_element with:
- elementType: "s3sampler"
- elementName: "列出指定前缀的对象"
- properties:
  - S3Sampler.uidName: "my-s3-conn"
  - S3Sampler.action: "LIST_OBJECTS"
  - S3Sampler.Arguments:
    - "<bucket-name>": "test-bucket"
    - "<prefix>": "logs/2024/"
```

### Example 5: Delete File

```
create_jmeter_element with:
- elementType: "s3sampler"
- elementName: "删除文件"
- properties:
  - S3Sampler.uidName: "my-s3-conn"
  - S3Sampler.action: "DELETE_FILE"
  - S3Sampler.Arguments:
    - "<bucket-name>": "test-bucket"
    - "<key>": "data/old-report.csv"
```

## Response Format

All responses follow a unified JSON structure:

**Success:**
```json
{"success": true, "errorMessage": null, "data": {...}}
```

**Failure:**
```json
{"success": false, "errorMessage": "error details", "data": null}
```

## Notes

- This sampler requires a matching S3 Connection Configuration in the test plan
- The `uidName` in the sampler must exactly match the `s3UidName` in the connection config
- Upload/Download operations track byte counts (`sentBytes`/`bytes`) for throughput reporting
- `DOWNLOAD_FILE` with `<discard-file>` set to `"true"` discards the file after download — useful for bandwidth-only testing
- Object keys should NOT start with `/`
- `LIST_OBJECTS` returns up to 1000 objects per request
