# __isPropDefined

## Function Name
`__isPropDefined`

## Category
Properties

## Description
The `__isPropDefined` function returns true if property exists or false if not.

## Parameters

| Parameter | Description | Required | Default |
|-----------|-------------|----------|---------|
| Property Name | The Property Name to be used to check if defined | Yes | - |

## Usage Examples

### Basic Usage
```
${__isPropDefined(START.HMS)}
```
Returns `true` because `START.HMS` is a built-in JMeter property.

### Check Custom Property
```
${__isPropDefined(my.custom.property)}
```
Returns `true` if the property `my.custom.property` has been defined (e.g., via command line `-J` flag or in `user.properties`), or `false` if it has not been defined.

## Notes
- This function is useful for conditional logic in test plans where behavior should differ based on whether a property has been defined.
- Can be used in combination with If Controller to branch test plan execution.

## Since
4.0

## Reference
- [Apache JMeter - __isPropDefined](https://jmeter.apache.org/usermanual/functions.html#__isPropDefined)
