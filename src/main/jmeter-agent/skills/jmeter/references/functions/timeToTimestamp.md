# __timeToTimestamp

## Function Name
`__timeToTimestamp`

## Category
Calculation

## Description
Converts a formatted date-time string to a Unix timestamp (milliseconds since epoch). The input time string must match the specified format pattern exactly.

## Parameters

| Parameter | Description | Required | Default |
|-----------|-------------|----------|---------|
| Time format | `SimpleDateFormat` pattern, e.g. `yyyy-MM-dd HH:mm:ss` | Yes | -- |
| Time string | The date-time string to convert, must match the format pattern | Yes | -- |
| Variable name | Name of the JMeter variable to store the timestamp result | No | -- |

## Usage Examples

### Basic Conversion
```
${__timeToTimestamp(yyyy-MM-dd HH:mm:ss,2024-01-15 10:30:00)}
```
Returns the Unix timestamp in milliseconds, e.g. `1705312200000`.

### With Milliseconds Precision
```
${__timeToTimestamp(yyyy-MM-dd HH:mm:ss:SSS,2024-01-15 10:30:00:123)}
```
Returns the timestamp including milliseconds precision.

### With Variable Storage
```
${__timeToTimestamp(yyyy-MM-dd,${startDate},startTs)}
```
Converts the `${startDate}` variable to a timestamp and stores it in `startTs`.

### Calculate Duration
```
${__timeToTimestamp(yyyy-MM-dd HH:mm:ss,${endTime})} - ${__timeToTimestamp(yyyy-MM-dd HH:mm:ss,${startTime})}
```
Use in combination to calculate time durations between two dates.

## Notes
- Returns a millisecond-precision Unix timestamp (13 digits)
- The time string must exactly match the specified format pattern
- Uses `SimpleDateFormat` for parsing; ensure format patterns are valid
- If the format does not match the time string, parsing will fail and produce unexpected results

## Since
Custom (Gitee extension)

## Reference
- Source: `com.gitee.qa.jmeter.functions.TimeToTimestamp`
