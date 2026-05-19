# __timePick

## Function Name
`__timePick`

## Category
Calculation

## Description
Picks a specific date based on the day of the week, day of the month, or day of the year, optionally calculated from a reference timestamp. Supports both positive and negative indexing (negative counts from the end of the period).

## Parameters

| Parameter | Description | Required | Default |
|-----------|-------------|----------|---------|
| Time type | `"W"` (week), `"M"` (month), or `"Y"` (year) | Yes | -- |
| Day number | Positive counts forward, -1 means last day, -2 means second to last, etc. For week: 1 = Monday | Yes | -- |
| Timestamp | Reference Unix timestamp (13-digit ms or 10-digit seconds) | No | Current time |
| Output format | `SimpleDateFormat` pattern, e.g. `yyyy-MM-dd HH:mm:ss` | No | Unix timestamp (milliseconds) |
| Variable name | Name of the JMeter variable to store the result | No | -- |

## Usage Examples

### Get This Week's Monday
```
${__timePick(W,1)}
```
Returns the timestamp of Monday of the current week.

### Get Last Day of Current Month (formatted)
```
${__timePick(M,-1,,yyyy-MM-dd)}
```
Returns the last day of the current month, e.g. `2024-01-31`.

### Get 15th Day of Current Month
```
${__timePick(M,15,,yyyy-MM-dd)}
```
Returns the 15th day of the current month.

### From a Specific Timestamp
```
${__timePick(W,3,1700000000000,yyyy-MM-dd HH:mm:ss)}
```
Returns Wednesday of the week containing the given timestamp.

### With Variable Storage
```
${__timePick(M,-1,,yyyy-MM-dd,endOfMonth)}
```
Stores the formatted date in the variable `endOfMonth`.

## Notes
- Week type: first day is Monday (`Calendar.MONDAY`); negative day numbers for week type are not supported
- Month type: supports both positive and negative day numbers; -1 = last day, -2 = second to last
- Year type: supports both positive and negative day numbers; day 1 = January 1st
- Timestamp must be 13 digits (milliseconds) or 10 digits (seconds); other lengths return `null`
- If no output format is specified, returns the Unix timestamp in milliseconds as a string

## Since
Custom (Gitee extension)

## Reference
- Source: `com.gitee.qa.jmeter.functions.TimePick`
