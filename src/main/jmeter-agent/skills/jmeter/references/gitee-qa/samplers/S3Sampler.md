# S3 Sampler
> **Source**: Gitee QA extension (third-party plugin `com.gitee.qa.jmeter`, requires the corresponding plugin)

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

### 1. LIST_ALL_BUCKETS — 列出所有存储桶

List all buckets in the S3 service. No additional arguments required.

| Argument Key | Required | NotNull | Default | Description |
|-------------|----------|---------|---------|-------------|
| *(none)* | — | — | — | This action takes no arguments |

---

### 2. CREATE_BUCKET — 创建存储桶

Create a new bucket.

| Argument Key | Required | NotNull | Default | Description |
|-------------|----------|---------|---------|-------------|
| `<bucket-name>` | Yes | Yes | — | 存储桶名称 |

---

### 3. DELETE_BUCKET — 删除存储桶

Delete an existing bucket.

| Argument Key | Required | NotNull | Default | Description |
|-------------|----------|---------|---------|-------------|
| `<bucket-name>` | Yes | Yes | — | 存储桶名称 |

---

### 4. BUCKET_EXISTS — 存储桶是否存在

Check whether a bucket exists.

| Argument Key | Required | NotNull | Default | Description |
|-------------|----------|---------|---------|-------------|
| `<bucket-name>` | Yes | Yes | — | 存储桶名称 |

---

### 5. LIST_OBJECTS — 列出存储桶中的对象（最多返回 1000 个）

List objects in a bucket, optionally filtered by a key prefix.

| Argument Key | Required | NotNull | Default | Description |
|-------------|----------|---------|---------|-------------|
| `<bucket-name>` | Yes | Yes | — | 存储桶名称 |
| `<prefix>` | No | No | `""` (empty = list all) | 对象键前缀（可选），用于过滤指定路径下的对象 |

---

### 6. GET_FILE_METADATA — 获取文件元数据

Retrieve metadata (size, content type, last modified, ETag) for a file.

| Argument Key | Required | NotNull | Default | Description |
|-------------|----------|---------|---------|-------------|
| `<bucket-name>` | Yes | Yes | — | 存储桶名称 |
| `<key>` | Yes | Yes | — | 对象键（S3 中的文件路径，不要以 `/` 开头） |

---

### 7. FILE_EXISTS — 检查文件是否存在

Check whether a file (object) exists in a bucket.

| Argument Key | Required | NotNull | Default | Description |
|-------------|----------|---------|---------|-------------|
| `<bucket-name>` | Yes | Yes | — | 存储桶名称 |
| `<key>` | Yes | Yes | — | 对象键（S3 中的文件路径） |

---

### 8. UPLOAD_FILE — 上传文件

Upload a local file to an S3 bucket.

| Argument Key | Required | NotNull | Default | Description |
|-------------|----------|---------|---------|-------------|
| `<bucket-name>` | Yes | Yes | — | 存储桶名称 |
| `<key>` | Yes | Yes | — | 对象键（S3 中的文件路径，注意：不要以 `/` 开头） |
| `<file-path>` | Yes | Yes | — | 本地文件路径（绝对路径或相对路径） |

---

### 9. DOWNLOAD_FILE — 下载文件

Download a file from an S3 bucket to a local directory.

| Argument Key | Required | NotNull | Default | Description |
|-------------|----------|---------|---------|-------------|
| `<bucket-name>` | Yes | Yes | — | 存储桶名称 |
| `<key>` | Yes | Yes | — | 对象键（S3 中的文件路径） |
| `<download-dir>` | Yes | Yes | — | 本地下载目录 |
| `<download-file-name>` | No | No | `""` (empty = use original file name) | 保存文件名，如果为空则使用原文件名 |
| `<discard-file>` | No | No | `"false"` | 是否丢弃文件。`"true"`：下载后丢弃不写磁盘（仅测量传输带宽）；`"false"`：保存到本地 |

---

### 10. DELETE_FILE — 删除文件

Delete a file (object) from a bucket.

| Argument Key | Required | NotNull | Default | Description |
|-------------|----------|---------|---------|-------------|
| `<bucket-name>` | Yes | Yes | — | 存储桶名称 |
| `<key>` | Yes | Yes | — | 对象键（S3 中的文件路径） |

---

## Argument Keys Reference

Summary of all argument keys used across actions:

| Key | Description | Example |
|-----|-------------|---------|
| `<bucket-name>` | Target bucket name | `"test-bucket"` |
| `<key>` | Object key (file path in S3, do NOT start with `/`) | `"data/report.csv"` |
| `<file-path>` | Local file path to upload | `"/tmp/testfile.txt"` |
| `<download-dir>` | Local directory to save downloaded file | `"/tmp/downloads"` |
| `<download-file-name>` | Custom filename for download (defaults to original) | `"result.csv"` |
| `<discard-file>` | Discard after download, measure transfer only (`"true"` / `"false"`) | `"true"` |
| `<prefix>` | Object key prefix filter for listing | `"data/"` |

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
