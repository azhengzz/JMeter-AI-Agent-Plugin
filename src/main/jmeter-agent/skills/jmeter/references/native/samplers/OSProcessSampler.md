# OS Process Sampler
> **Source**: Apache JMeter built-in component (ships with JMeter, no extra install needed)

## Description

The OS Process Sampler is a sampler that can be used to execute commands on the local machine. It should allow execution of any command that can be run from the command line. Validation of the return code can be enabled, and the expected return code can be specified.

Note that OS shells generally provide command-line parsing. This varies between OSes, but generally the shell will split parameters on white-space. Some shells expand wild-card file names; some don't. The quoting mechanism also varies between OSes. The sampler deliberately does not do any parsing or quote handling. The command and its parameters must be provided in the form expected by the executable. This means that the sampler settings will not be portable between OSes.

Many OSes have some built-in commands which are not provided as separate executables. For example the Windows `DIR` command is part of the command interpreter (`CMD.EXE`). These built-ins cannot be run as independent programs, but have to be provided as arguments to the appropriate command interpreter.

For example, the Windows command-line `DIR C:\TEMP` needs to be specified as follows:
- Command: `CMD`
- Param 1: `/C`
- Param 2: `DIR`
- Param 3: `C:\TEMP`

## Parameters

| Property | Required | Default | Description | Example |
|----------|----------|---------|-------------|---------|
| `SystemSampler.command` | Yes | — | The program name to execute. Can be a full path or command available in PATH. | `"python"` |
| `SystemSampler.directory` | No | `""` | Directory from which command will be executed, defaults to folder referenced by `user.dir` System property. | `"/home/user/scripts"` |
| `SystemSampler.checkReturnCode` | No | `false` | If checked, sampler will compare return code with Expected Return Code. | `"true"` |
| `SystemSampler.expectedReturnCode` | No | `"0"` | Expected return code for System Call, required if Check Return Code is checked. Note 500 is used as an error indicator in JMeter so you should not use it. | `"0"` |
| `SystemSampler.arguments` | No | — | Command-line arguments passed to the executable as an array. See Nested Arguments below. | See examples |
| `SystemSampler.environment` | No | — | Key/Value pairs added to environment when running command. See Nested Environment below. | See examples |
| `SystemSampler.stdin` | No | `""` | Name of file from which input is to be taken (STDIN). | `"input.txt"` |
| `SystemSampler.stdout` | No | `""` | Name of output file for standard output (STDOUT). If omitted, output is captured and returned as the response data. | `"output.txt"` |
| `SystemSampler.stderr` | No | `""` | Name of output file for standard error (STDERR). If omitted, output is captured and returned as the response data. | `"error.txt"` |
| `SystemSampler.timeout` | No | `0` | Timeout for command in milliseconds. Defaults to 0 which means no timeout. If the timeout expires before the command finishes, JMeter will attempt to kill the OS process. Min: 0. | `"30000"` |

### Nested Arguments (`SystemSampler.arguments`)

Each argument item supports:

| Property | Required | Default | Description | Example |
|----------|----------|---------|-------------|---------|
| `Argument.value` | Yes | — | Command-line argument value | `"-c"` |

### Nested Environment (`SystemSampler.environment`)

Each environment variable item supports:

| Property | Required | Default | Description | Example |
|----------|----------|---------|-------------|---------|
| `Argument.name` | Yes | — | Environment variable name | `"JAVA_HOME"` |
| `Argument.value` | Yes | — | Environment variable value | `"/usr/lib/jvm/java-8"` |

## Usage Examples

### Example 1: Run Python Script

```
create_jmeter_element with:
- elementType: "osprocesssampler"
- elementName: "执行Python脚本"
- properties:
  - SystemSampler.command: "python"
  - SystemSampler.arguments:
    - {"Argument.value": "script.py"}
    - {"Argument.value": "--verbose"}
```

### Example 2: Run Windows DIR Command

```
create_jmeter_element with:
- elementType: "osprocesssampler"
- elementName: "列出目录内容"
- properties:
  - SystemSampler.command: "CMD"
  - SystemSampler.arguments:
    - {"Argument.value": "/C"}
    - {"Argument.value": "DIR"}
    - {"Argument.value": "C:\\TEMP"}
```

### Example 3: Run Shell Script with Environment Variables

```
create_jmeter_element with:
- elementType: "osprocesssampler"
- elementName: "执行Shell脚本"
- properties:
  - SystemSampler.command: "/bin/bash"
  - SystemSampler.arguments:
    - {"Argument.value": "-c"}
    - {"Argument.value": "echo $MY_VAR"}
  - SystemSampler.environment:
    - {"Argument.name": "MY_VAR", "Argument.value": "hello world"}
```

### Example 4: Run with Timeout and Return Code Check

```
create_jmeter_element with:
- elementType: "osprocesssampler"
- elementName: "执行并检查返回码"
- properties:
  - SystemSampler.command: "curl"
  - SystemSampler.checkReturnCode: "true"
  - SystemSampler.expectedReturnCode: "0"
  - SystemSampler.timeout: "30000"
  - SystemSampler.arguments:
    - {"Argument.value": "-s"}
    - {"Argument.value": "https://api.example.com/health"}
```

### Example 5: Run with I/O Redirection

```
create_jmeter_element with:
- elementType: "osprocesssampler"
- elementName: "带输入输出重定向"
- properties:
  - SystemSampler.command: "python"
  - SystemSampler.stdin: "input.txt"
  - SystemSampler.stdout: "output.txt"
  - SystemSampler.stderr: "error.txt"
  - SystemSampler.arguments:
    - {"Argument.value": "process.py"}
```

## Best Practices

1. **Use absolute paths**: Specify full paths to executables to avoid PATH dependency issues
2. **Check return codes**: Enable return code checking for critical operations
3. **Set timeouts**: Always set a timeout to prevent hanging processes
4. **Be OS-aware**: Remember that commands and arguments differ between operating systems
5. **Use environment variables**: Set environment explicitly instead of relying on system defaults
6. **Redirect output**: Use stdout/stderr redirection for large outputs to avoid memory issues

## Notes

- The sampler deliberately does not do any parsing or quote handling
- The command and its parameters must be provided in the form expected by the executable
- Settings will not be portable between OSes
- Windows built-in commands (like `DIR`, `COPY`) must be run through `CMD /C`
- Standard Unix convention: exit code 0 = success, non-zero = error
- Note 500 is used as an error indicator in JMeter so you should not use it as expected return code
- If stdout/stderr are omitted, output is captured and returned as response data
