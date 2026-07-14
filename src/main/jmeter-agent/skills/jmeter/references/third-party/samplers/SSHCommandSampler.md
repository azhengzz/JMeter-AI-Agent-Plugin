# SSH Command Sampler
> **Source**: Third-party plugin (SSH Sampler, must be installed separately)

## Description

The SSH Command Sampler opens an SSH session to a remote host and executes a single command via the `exec` channel. Standard output (and optionally standard error) of the command is captured as the sampler response data. The sampler supports both password and public-key authentication.

This sampler is useful when a load test must drive operations that only exist on a remote server command line, for example:

- Triggering a remote script or admin command (`systemctl restart ...`, `df -h`, `tail -n 200 /var/log/app.log`)
- Reading remote process state, queue depth, or file metadata as part of a scenario
- Verifying that a deploy or migration step finished successfully on a backend host

The SSH session is created fresh for every sample (it connects at the start of `sample()` and disconnects before returning). This means each iteration pays the full SSH handshake cost — there is no connection reuse across iterations. For high-throughput scenarios, prefer running the remote logic under HTTP or another stateless protocol and use this sampler only when SSH is genuinely required.

## Authentication

Authentication mode is chosen automatically based on the `sshkeyfile` field:

| Condition | Authentication used |
|-----------|--------------------|
| `sshkeyfile` is non-empty | Public-key authentication; `passphrase` is used to decrypt the key if it is encrypted |
| `sshkeyfile` is empty | Password authentication (or keyboard-interactive, which falls back to `password`) |

The sampler forces `StrictHostKeyChecking=no` and `PreferredAuthentications=publickey,keyboard-interactive,password`, so unknown host keys are accepted automatically. This is convenient for testing but should NOT be used against production hosts you do not control.

## Parameters

| Property | Required | Default | Description | Example |
|----------|----------|---------|-------------|---------|
| `hostname` | Yes | `""` | SSH server host name or IP address. | `"10.0.0.5"` |
| `port` | No | `22` | SSH server TCP port. | `"2222"` |
| `connectionTimeout` | No | `5000` | SSH session connect timeout in milliseconds. Min: 0. | `"10000"` |
| `username` | Yes | `""` | SSH login user name. | `"deploy"` |
| `password` | No | `""` | Password for password authentication. Ignored if `sshkeyfile` is set. | `"s3cr3t"` |
| `sshkeyfile` | No | `""` | Local path to the private key file for public-key authentication. Empty means use password authentication. | `"/home/jmeter/.ssh/id_rsa"` |
| `passphrase` | No | `""` | Passphrase used to decrypt the private key, if it is encrypted. | `"key-pass-123"` |
| `command` | Yes | `"date"` | Command to execute on the remote host. The string is sent verbatim — no shell quoting or escaping is applied by the sampler. | `"ls -la /var/log"` |
| `useReturnCode` | No | `true` | If `true`, the sampler marks itself successful only when the command's exit code is `0`. If `false`, any exit code is considered successful. | `"true"` |
| `useTty` | No | `false` | If `true`, allocates a pseudo-terminal (PTY) for the channel. Some programs require a TTY to run; most do not. | `"false"` |
| `printStdErr` | No | `true` | If `true`, standard error is appended to the response data after a `=== stderr ===` marker. If `false`, only standard output is returned. | `"true"` |

## Response Data

The sampler writes the captured command output into the response body:

- When `printStdErr=true`, the layout is:
  ```
  === stdin ===

  <stdout lines>

  === stderr ===

  <stderr lines>
  ```
- When `printStdErr=false`, only the stdout lines are returned.

The response code is set to the command's exit code when `useReturnCode=true`, otherwise it is set to a generic success code. The sample label is automatically suffixed with `(user@host:port)` so multiple SSH targets are distinguishable in listeners.

## Usage Examples

### Example 1: Run a Simple Remote Command (Password Auth)

```
create_jmeter_element with:
- elementType: "sshcommandsampler"
- elementName: "检查磁盘空间"
- properties:
  - hostname: "10.0.0.5"
  - port: "22"
  - username: "deploy"
  - password: "${SSH_PASSWORD}"
  - command: "df -h /"
  - useReturnCode: "true"
```

### Example 2: Public-Key Authentication

```
create_jmeter_element with:
- elementType: "sshcommandsampler"
- elementName: "重启服务"
- properties:
  - hostname: "app-server-1"
  - username: "ci"
  - sshkeyfile: "/home/jmeter/.ssh/id_ed25519"
  - passphrase: "${KEY_PASSPHRASE}"
  - command: "sudo systemctl restart myapp"
  - useReturnCode: "true"
  - useTty: "true"
```

### Example 3: Capture Only stdout (Suppress stderr)

```
create_jmeter_element with:
- elementType: "sshcommandsampler"
- elementName: "读取日志尾部"
- properties:
  - hostname: "log-host"
  - username: "reader"
  - password: "${LOG_PW}"
  - command: "tail -n 200 /var/log/app.log"
  - printStdErr: "false"
  - useReturnCode: "false"
```

### Example 4: Run a Command That Requires a TTY

```
create_jmeter_element with:
- elementType: "sshcommandsampler"
- elementName: "执行交互式命令"
- properties:
  - hostname: "10.0.0.9"
  - username: "ops"
  - sshkeyfile: "/home/jmeter/.ssh/id_rsa"
  - command: "top -bn1 | head -20"
  - useTty: "true"
```

### Example 5: Use Exit Code to Gate the Test

```
create_jmeter_element with:
- elementType: "sshcommandsampler"
- elementName: "校验部署完成"
- properties:
  - hostname: "build-agent"
  - username: "ci"
  - sshkeyfile: "/home/jmeter/.ssh/deploy_key"
  - command: "test -f /opt/app/READY && echo ok"
  - useReturnCode: "true"
  - printStdErr: "false"
```

## Best Practices

1. **Prefer key-based auth over passwords**: Store the private key under `${JMETER_HOME}` and reference it by absolute path; put the passphrase in a JMeter variable rather than hard-coding it.
2. **Keep commands idempotent and fast**: Each sample reconnects to the host. Avoid commands that take longer than the connect timeout or that mutate state in ways that cannot be safely re-run.
3. **Set `useReturnCode=true` for verify-style checks**: It maps exit code `0` to sampler success automatically, so you can use If Controller or assertion logic on the result.
4. **Suppress stderr when only stdout matters**: Set `printStdErr=false` to keep the response body small and avoid confusing listeners with non-fatal warnings.
5. **Do not enable `useTty` unless required**: Allocating a PTY can change how the remote program buffers and flushes output, and some hosts log PTY sessions differently.

## Notes

- A new SSH session is opened and closed for every sample; there is no connection pooling across iterations.
- The sampler forces `StrictHostKeyChecking=no`, so it accepts unknown host keys without prompting. Do not use against untrusted hosts.
- The `command` string is sent verbatim — there is no shell quoting or wildcard expansion performed by the sampler. The remote shell interprets the string.
- The `=== stdin ===` header in the response body is the sampler's own label for stdout (a historical quirk in the upstream plugin); it does not indicate that the sampler sent anything on stdin.
- `connectionTimeout` controls only the SSH handshake. A command that hangs indefinitely will still hold the sample open until the remote program returns.
- When `useReturnCode=true` and the command exits with a non-zero code, the sampler is marked failed but the response data still contains the captured output for debugging.
