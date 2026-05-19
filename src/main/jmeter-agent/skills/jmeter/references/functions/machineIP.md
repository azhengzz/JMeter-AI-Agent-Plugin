# __machineIP

## Function Name
`__machineIP`

## Category
Information

## Description
The machineIP function returns the local IP address. This uses the Java method `InetAddress.getLocalHost()` and passes it to `getHostAddress()`.

## Parameters

| Parameter | Description | Required | Default |
|-----------|-------------|----------|---------|
| Variable Name | A reference name for reusing the value computed by this function. | No | - |

## Usage Examples

### Basic Usage
```
${__machineIP()}
```
Returns the IP address of the machine.

### Without Parentheses
```
${__machineIP}
```
Returns the IP address of the machine.

## Notes
- Uses the Java method `InetAddress.getLocalHost()` and passes it to `getHostAddress()`.

## Since
2.6

## Reference
- [Apache JMeter - __machineIP](https://jmeter.apache.org/usermanual/functions.html#__machineIP)
