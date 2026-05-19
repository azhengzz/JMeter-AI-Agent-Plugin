# __logn

## Function Name
`__logn`

## Category
Information

## Description
The logn function logs a message, and returns the empty string.

## Parameters

| Parameter | Description | Required | Default |
|-----------|-------------|----------|---------|
| String to be logged | A string | Yes | - |
| Log Level | `OUT`, `ERR`, `DEBUG`, `INFO` (default), `WARN` or `ERROR` | No | `INFO` |
| Throwable text | If non-empty, creates a Throwable to pass to the logger | No | - |

## Usage Examples

### Log to Console
```
${__logn(VAR1=${VAR1},OUT)}
```
Write the value of the variable to the console window.

## Notes
- The `OUT` and `ERR` log level names are used to direct the output to `System.out` and `System.err` respectively.
- In the case of `OUT` and `ERR`, the output is always printed - it does not depend on the current log setting.
- Unlike `__log`, this function returns the empty string rather than the input string.

## Since
2.2

## Reference
- [Apache JMeter - __logn](https://jmeter.apache.org/usermanual/functions.html#__logn)
