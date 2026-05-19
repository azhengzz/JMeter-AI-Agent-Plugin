# __machineName

## Function Name
`__machineName`

## Category
Information

## Description
The machineName function returns the local host name. This uses the Java method `InetAddress.getLocalHost()` and passes it to `getHostName()`.

## Parameters

| Parameter | Description | Required | Default |
|-----------|-------------|----------|---------|
| Variable Name | A reference name for reusing the value computed by this function. | No | - |

## Usage Examples

### Basic Usage
```
${__machineName()}
```
Returns the host name of the machine.

### Without Parentheses
```
${__machineName}
```
Returns the host name of the machine.

## Notes
- Uses the Java method `InetAddress.getLocalHost()` and passes it to `getHostName()`.

## Since
1.X

## Reference
- [Apache JMeter - __machineName](https://jmeter.apache.org/usermanual/functions.html#__machineName)
