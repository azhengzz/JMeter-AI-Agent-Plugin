# SSH SFTP Sampler

## Description

The SSH SFTP Sampler opens an SSH session to a remote host and performs a single SFTP (SSH File Transfer Protocol) operation via the `sftp` channel. It supports file transfer (`get`, `put`), file and directory management (`rm`, `rmdir`), and directory listing (`ls`). Authentication mode is chosen automatically based on the `sshkeyfile` field.

This sampler is useful when a load test must move or verify files on a remote host, for example:

- Pulling a generated report from a backend server back to the JMeter host (`get`)
- Pushing a test fixture file (`put`) before the scenario starts
- Verifying that an upload landed in the expected directory (`ls`)
- Cleaning up artifacts between iterations (`rm`, `rmdir`)

A new SSH session is opened and closed for every sample; the sampler does not pool SFTP channels across iterations.

## Authentication

Authentication mode is chosen automatically based on the `sshkeyfile` field, identical to the SSH Command Sampler:

| Condition | Authentication used |
|-----------|--------------------|
| `sshkeyfile` is non-empty | Public-key authentication; `passphrase` decrypts the key if encrypted |
| `sshkeyfile` is empty | Password authentication (or keyboard-interactive, which falls back to `password`) |

The sampler forces `StrictHostKeyChecking=no` and `PreferredAuthentications=publickey,keyboard-interactive,password`, so unknown host keys are accepted automatically. Do not use against production hosts you do not control.

## Parameters

| Property | Required | Default | Description | Example |
|----------|----------|---------|-------------|---------|
| `hostname` | Yes | `""` | SSH/SFTP server host name or IP address. | `"10.0.0.5"` |
| `port` | No | `22` | SSH server TCP port. | `"2222"` |
| `connectionTimeout` | No | `5000` | SSH session connect timeout in milliseconds. Min: 0. | `"10000"` |
| `username` | Yes | `""` | SSH login user name. | `"deploy"` |
| `password` | No | `""` | Password for password authentication. Ignored if `sshkeyfile` is set. | `"s3cr3t"` |
| `sshkeyfile` | No | `""` | Local path to the private key file for public-key authentication. | `"/home/jmeter/.ssh/id_rsa"` |
| `passphrase` | No | `""` | Passphrase used to decrypt the private key, if encrypted. | `"key-pass-123"` |
| `action` | Yes | `"get"` | SFTP operation to perform. See Action Values below. | `"get"` |
| `source` | Yes* | `""` | Source path. Required for all actions. The path is interpreted relative to the remote user's home directory unless it is absolute. | `"/var/log/app.log"` |
| `destination` | Conditional | `""` | Destination path. Required for `get` (when `printFile=false`) and `put`. Ignored for other actions. | `"./app.log.bak"` |
| `printFile` | No | `true` | Only meaningful for `get`. If `true`, the downloaded file's contents are written into the response body. If `false`, the file is saved to `destination` on the local file system instead. | `"true"` |

\* `source` is required in practice; the sampler does not enforce a non-empty value, but an empty path will cause the underlying SFTP call to fail at runtime.

### Action Values

| Value | Description | Uses `destination`? |
|-------|-------------|---------------------|
| `get` | Download a remote file. When `printFile=true`, file contents are written into the response body. When `printFile=false`, the file is saved to the local path given by `destination`. | Yes (when `printFile=false`) |
| `put` | Upload a local file. `source` is the local file path, `destination` is the remote target path. | Yes |
| `rm` | Delete a remote file. | No |
| `rmdir` | Remove a remote directory (must be empty). | No |
| `ls` | List a remote directory. Each entry's long name (permissions, owner, size, name) is written into the response body, one per line. | No |

## Response Data

The response body depends on the action:

- `get` with `printFile=true`: the full file contents (as text, line by line).
- `get` with `printFile=false`: empty; the file is written to `destination` on disk.
- `put`, `rm`, `rmdir`: empty on success.
- `ls`: one line per directory entry, formatted as the long listing (e.g., `-rw-r--r-- 1 user group 1234 Jan 01 12:00 file.log`).

The sample label is automatically suffixed with `(user@host:port)`. The sampler marks itself successful whenever the underlying SFTP call completes without throwing; failure reasons (`SftpException`, `JSchException`, `IOException`) are reported in the response code and message.

## Usage Examples

### Example 1: Download a Remote File Into the Response Body

```
create_jmeter_element with:
- elementType: "sshsftpsampler"
- elementName: "读取远程配置"
- properties:
  - hostname: "10.0.0.5"
  - port: "22"
  - username: "deploy"
  - password: "${SSH_PASSWORD}"
  - action: "get"
  - source: "/etc/myapp/config.yaml"
  - printFile: "true"
```

### Example 2: Download a Remote File to a Local Path

```
create_jmeter_element with:
- elementType: "sshsftpsampler"
- elementName: "拉取日志文件"
- properties:
  - hostname: "log-host"
  - username: "ci"
  - sshkeyfile: "/home/jmeter/.ssh/id_rsa"
  - action: "get"
  - source: "/var/log/app.log"
  - destination: "${__property(user.dir)}/runtime/app.log"
  - printFile: "false"
```

### Example 3: Upload a Local File (Key Auth)

```
create_jmeter_element with:
- elementType: "sshsftpsampler"
- elementName: "上传测试数据"
- properties:
  - hostname: "upload-host"
  - username: "ci"
  - sshkeyfile: "/home/jmeter/.ssh/id_ed25519"
  - passphrase: "${KEY_PASSPHRASE}"
  - action: "put"
  - source: "./fixtures/users.csv"
  - destination: "/srv/inbox/users.csv"
```

### Example 4: List a Remote Directory

```
create_jmeter_element with:
- elementType: "sshsftpsampler"
- elementName: "检查上传目录"
- properties:
  - hostname: "10.0.0.5"
  - username: "deploy"
  - password: "${SSH_PASSWORD}"
  - action: "ls"
  - source: "/srv/inbox"
```

### Example 5: Clean Up a Run Directory

```
create_jmeter_element with:
- elementType: "sshsftpsampler"
- elementName: "清理运行目录"
- properties:
  - hostname: "10.0.0.5"
  - username: "deploy"
  - sshkeyfile: "/home/jmeter/.ssh/id_rsa"
  - action: "rmdir"
  - source: "/srv/work/run-001"
```

### Example 6: Delete a Remote File

```
create_jmeter_element with:
- elementType: "sshsftpsampler"
- elementName: "删除临时文件"
- properties:
  - hostname: "10.0.0.5"
  - username: "deploy"
  - password: "${SSH_PASSWORD}"
  - action: "rm"
  - source: "/tmp/scratch.dat"
```

## Best Practices

1. **Use key-based auth for shared CI machines**: Avoid embedding passwords in test plans; reference passphrases via JMeter variables.
2. **Keep `printFile=true` for small text files only**: Large binaries inflate the response body and listener memory. For big files, set `printFile=false` and write directly to disk via `destination`.
3. **Idempotency matters**: `rm` and `rmdir` will fail if the target does not exist. Wrap them in a controller or precede them with an `ls` check when re-running the same scenario.
4. **Use absolute remote paths**: Relative paths are resolved against the remote user's home directory, which can differ between hosts and surprise you.
5. **Allocate the local destination before `put`/`get`**: The sampler does not create parent directories on either side; ensure they exist beforehand.
6. **Verify with `ls` after `put`**: An SFTP `put` can succeed at the protocol level while the remote filesystem rejects the write (quotas, read-only mounts). Listing the destination confirms the file actually landed.

## Notes

- A new SSH session is opened and closed for every sample; SFTP channels are not pooled.
- The sampler forces `StrictHostKeyChecking=no`, so unknown host keys are accepted without prompting. Do not use against untrusted hosts.
- `rmdir` only removes empty directories; use `rm` (possibly via SSH Command Sampler) to remove non-empty trees.
- `source` and `destination` are interpreted by the SFTP subsystem. Remote paths follow the remote OS conventions (forward slashes on Unix hosts); local paths (`source` for `put`, `destination` for `get` with `printFile=false`) follow the JMeter host's conventions.
- The sampler marks itself successful whenever the underlying SFTP call returns normally; it does not check whether the operation had the intended filesystem effect. Use `ls` or assertions to verify outcomes that matter.
- The response code reflects the failure category (`SftpException`, `JSchException`, `IOException`, or `Connection Failed`) and the response message carries the underlying error text.
