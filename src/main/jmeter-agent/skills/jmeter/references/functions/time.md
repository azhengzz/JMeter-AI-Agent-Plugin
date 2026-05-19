# __time

## Function Name
`__time`

## Category
Information

## Description
The time function returns the current time in various formats.

If the format string is omitted, then the function returns the current time in milliseconds since the epoch.
If the format matches `/ddd` (where `ddd` are decimal digits), then the function returns the current time in milliseconds divided by the value of `ddd`. For example, `/1000` returns the current time in seconds since the epoch.
Otherwise, the current time is passed to DateTimeFormatter.

The following shorthand aliases are provided:

- `YMD` = `yyyyMMdd`
- `HMS` = `HHmmss`
- `YMDHMS` = `yyyyMMdd-HHmmss`
- `USER1` = whatever is in the JMeter property `time.USER1`
- `USER2` = whatever is in the JMeter property `time.USER2`

The defaults can be changed by setting the appropriate JMeter property, e.g. `time.YMD=yyMMdd`.

## Parameters

| Parameter | Description | Required | Default |
|-----------|-------------|----------|---------|
| Format | The format to be passed to [DateTimeFormatter](https://docs.oracle.com/javase/8/docs/api/java/time/format/DateTimeFormatter.html). The function supports various shorthand aliases (see above). If omitted, the function returns the current time in milliseconds since the epoch. | No | milliseconds since epoch |
| Name of variable | The name of the variable to set. | No | - |

## Usage Examples

### Return Formatted Date
```
${__time(dd/MM/yyyy,)}
```
Returns `21/01/2018` if ran on 21 january 2018.

### Using Shorthand Alias
```
${__time(YMD,)}
```
Returns `20180121` if ran on 21 january 2018.

### Return Milliseconds
```
${__time()}
```
Returns time in millis, e.g. `1516540541624`.

## Notes
- The format used to be [SimpleDateFormat](https://docs.oracle.com/javase/8/docs/api/java/text/SimpleDateFormat.html), but that changed with JMeter 5.5 to [DateTimeFormatter](https://docs.oracle.com/javase/8/docs/api/java/time/format/DateTimeFormatter.html). While they use mostly the same codes, they differ slightly. Most notable is probably the code `u`, that meant *day number of week* and is now interpreted as *year*.
- If the format matches `/ddd` (where `ddd` are decimal digits), the function returns the current time in milliseconds divided by that value.

## Since
2.2

## Reference
- [Apache JMeter - __time](https://jmeter.apache.org/usermanual/functions.html#__time)
