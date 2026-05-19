# __log

## Function Name
`__log`

## Category
Information

## Description
The log function logs a message, and returns its input string.

## Parameters

| Parameter | Description | Required | Default |
|-----------|-------------|----------|---------|
| String to be logged | A string | Yes | - |
| Log Level | `OUT`, `ERR`, `DEBUG`, `INFO` (default), `WARN` or `ERROR` | No | `INFO` |
| Throwable text | If non-empty, creates a Throwable to pass to the logger | No | - |
| Comment | If present, it is displayed in the string. Useful for identifying what is being logged. | No | - |

## Usage Examples

### Log at Default Level (INFO)
```
${__log(Message)}
```
Written to the log file as `... thread Name : Message`.

### Log to Console
```
${__log(Message,OUT)}
```
Written to console window.

### Log with Comment
```
${__log(${VAR},,,VAR=)}
```
Written to log file as `... thread Name VAR=value`.

## Notes
- The `OUT` and `ERR` log level names are used to direct the output to `System.out` and `System.err` respectively.
- In the case of `OUT` and `ERR`, the output is always printed - it does not depend on the current log setting.
- The function returns its input string, so it can be used inline in expressions without affecting the value.

## Since
2.2

## Reference
- [Apache JMeter - __log](https://jmeter.apache.org/usermanual/functions.html#__log)
