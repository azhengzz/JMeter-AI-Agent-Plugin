# __RandomDate

## Function Name
`__RandomDate`

## Category
Calculation

## Description
The RandomDate function returns a random date that lies between the given start date and end date values.

## Parameters

| Parameter | Description | Required | Default |
|-----------|-------------|----------|---------|
| Time format | Format string for DateTimeFormatter (default `yyyy-MM-dd`) | No | `yyyy-MM-dd` |
| Start date | The start date. | No | *now* |
| End date | The end date. | Yes | - |
| Locale to use for format | The string format of a locale. The language code must be lowercase. The country code must be uppercase. The separator must be an underscore, e.g. `en_EN`. See [http://www.oracle.com/technetwork/java/javase/javase7locales-334809.html](http://www.oracle.com/technetwork/java/javase/javase7locales-334809.html). If omitted, by default the function uses the Apache JMeter locale one. | No | JMeter locale |
| Name of variable | The name of the variable to set. | No | - |

## Usage Examples

### Basic Usage
```
${__RandomDate(,,2050-07-08,,)}
```
Returns a random date between *now* and `2050-07-08`. For example `2039-06-21`.

### Custom Format
```
${__RandomDate(dd MM yyyy,,08 07 2050,,)}
```
Returns a random date with a custom format like `04 03 2034`.

### With Start Date
```
${__RandomDate(yyyy-MM-dd,2020-01-01,2030-12-31,,)}
```
Returns a random date between `2020-01-01` and `2030-12-31` in `yyyy-MM-dd` format.

### With Variable Name
```
${__RandomDate(,,2050-07-08,,MYVAR)}
```
Returns a random date between *now* and `2050-07-08` and stores the result in `MYVAR`.

## Notes
- The Time format follows Java's DateTimeFormatter syntax.
- If Start date is omitted, *now* is used as the default.
- Locale format uses lowercase language code, uppercase country code, separated by underscore (e.g. `en_EN`).

## Since
3.3

## Reference
- [Apache JMeter - __RandomDate](https://jmeter.apache.org/usermanual/functions.html#__RandomDate)
