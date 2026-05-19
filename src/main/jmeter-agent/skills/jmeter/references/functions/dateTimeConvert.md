# __dateTimeConvert

## Function Name
`__dateTimeConvert`

## Category
Formatting

## Description
The `__dateTimeConvert` function converts a date that is in source format to a target format storing the result optionally in the variable name.

## Parameters

| Parameter | Description | Required | Default |
|-----------|-------------|----------|---------|
| Date String | The date string to convert from Source Date Format to Target Date Format. A date as an epoch time could be used here if Source Date Format is empty. | Yes | - |
| Source Date Format | The original date format. If empty, the Date String field must be an epoch time. | No | - |
| Target Date Format | The new date format. | Yes | - |
| Name of variable | The name of the variable to set. | No | - |

## Usage Examples

### Basic Usage
```
${__dateTimeConvert(01212018,MMddyyyy,dd/MM/yyyy,)}
```
Converts the date string `01212018` from `MMddyyyy` format to `dd/MM/yyyy` format, returning `21/01/2018`.

### Using Epoch Time
```
${__dateTimeConvert(1526574881000,,dd/MM/yyyy HH:mm,)}
```
Converts the epoch time value `1526574881000` to `dd/MM/yyyy HH:mm` format, returning `17/05/2018 16:34` in UTC time (`-Duser.timezone=GMT`).

### With Variable Name
```
${__dateTimeConvert(01212018,MMddyyyy,dd/MM/yyyy,MYVAR)}
```
Converts the date and stores the result in the `MYVAR` variable.

## Notes
- When using epoch time, leave the Source Date Format parameter empty.
- Date format patterns follow Java's DateTimeFormatter syntax.

## Since
4.0

## Reference
- [Apache JMeter - __dateTimeConvert](https://jmeter.apache.org/usermanual/functions.html#__dateTimeConvert)
