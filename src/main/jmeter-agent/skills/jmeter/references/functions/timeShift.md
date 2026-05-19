# __timeShift

## Function Name
`__timeShift`

## Category
Information

## Description
The timeShift function returns a date in the given format with the specified amount of seconds, minutes, hours, days or months added.

## Parameters

| Parameter | Description | Required | Default |
|-----------|-------------|----------|---------|
| Format | The format to be passed to DateTimeFormatter (for input data parsing and output formatting). See [DateTimeFormatter](https://docs.oracle.com/javase/8/docs/api/java/time/format/DateTimeFormatter.html). If omitted, the function uses milliseconds since epoch format. | No | milliseconds since epoch |
| Date to shift | Indicate the date in the format set by the parameter `Format` to shift. If omitted, the date is set to *ZonedDateTime.now* with system zone *ZoneId.systemDefault()*. If `Format` is empty then this parameter must be a long value (see examples). | No | current date/time |
| Value to shift | Indicate the specified amount of seconds, minutes, hours or days to shift according to a textual representation of a duration such as `PnDTnHnMn.nS`. See [Duration#parse(CharSequence)](https://docs.oracle.com/javase/8/docs/api/java/time/Duration.html#parse-java.lang.CharSequence-). If omitted, no shifting will be done. Examples: `PT20.345S` (20.345 seconds), `PT15M` (15 minutes), `PT10H` (10 hours), `P2D` (2 days), `-P6H3M` (-6 hours and -3 minutes). | No | no shift |
| Locale to use for format | The string format of a locale. The language code must be lowercase. The country code must be uppercase. The separator must be an underscore (`_`). For example `en_EN`. See [supported locales on Java 7](http://www.oracle.com/technetwork/java/javase/javase7locales-334809.html). If omitted, by default the function uses the current locale from the JVM. | No | JVM default locale |
| Name of variable | The name of the variable to set. | No | - |

## Usage Examples

### Shift Date by Days
```
${__timeShift(dd/MM/yyyy,21/01/2018,P2D,,)}
```
Returns `23/01/2018`.

### Shift Date with Locale
```
${__timeShift(dd MMMM yyyy,21 fevrier 2018,P2D,fr_FR,)}
```
Returns `23 fevrier 2018`.

### Shift Milliseconds Value
```
${__timeShift(,10000,PT10S,,)}
```
Returns `20000` = 10sec input + 10sec shift.

### Shift Current Time
```
${__timeShift(,,PT10S,,)}
```
Returns current time in milliseconds + 10sec shift.

## Notes
- If `Format` is empty, the `Date to shift` parameter must be a long value (milliseconds since epoch).
- Duration format follows ISO-8601 duration format: `PnDTnHnMn.nS`.
- Negative shifts are supported (e.g. `-P6H3M` for -6 hours and -3 minutes).

## Since
3.3

## Reference
- [Apache JMeter - __timeShift](https://jmeter.apache.org/usermanual/functions.html#__timeShift)
