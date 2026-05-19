# __TestPlanName

## Function Name
`__TestPlanName`

## Category
String

## Description
The TestPlanName function returns the name of the current test plan (can be used in Including Plans to know the name of the calling test plan).

## Parameters

There are no arguments for this function.

## Usage Examples

### Basic Usage
```
${__TestPlanName}
```
Returns the file name of your test plan. For example, if the plan is in a file named `Demo.jmx`, it will return `Demo.jmx`.

## Notes
- Can be used in Including Plans to know the name of the calling test plan.
- The function takes no arguments and parentheses can be omitted.

## Since
2.6

## Reference
- [Apache JMeter - __TestPlanName](https://jmeter.apache.org/usermanual/functions.html#__TestPlanName)
