# __threadGroupName

## Function Name
`__threadGroupName`

## Category
Information

## Description
The thread group name function simply returns the name of the thread group being executed.

There are no arguments for this function.

## Parameters

There are no arguments for this function.

## Usage Examples

### Basic Usage
```
${__threadGroupName}
```
Returns the name of the currently executing thread group.

## Notes
- This function does not work in any Configuration elements (e.g. User Defined Variables) as these are run from a separate thread.
- Nor does it make sense to use it on the Test Plan.

## Since
4.1

## Reference
- [Apache JMeter - __threadGroupName](https://jmeter.apache.org/usermanual/functions.html#__threadGroupName)
