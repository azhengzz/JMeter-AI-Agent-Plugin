# __setProperty

## Function Name
`__setProperty`

## Category
Properties

## Description
The setProperty function sets the value of a JMeter property.
The default return value from the function is the empty string, so the function call can be used anywhere functions are valid.

The original value can be returned by setting the optional 3rd parameter to `true`.

Properties are global to JMeter, so they can be used to communicate between threads and thread groups.

## Parameters

| Parameter | Description | Required | Default |
|-----------|-------------|----------|---------|
| Property Name | The property name to be set. | Yes | - |
| Property Value | The value for the property. | Yes | - |
| True/False | Should the original value be returned? | No | `false` |

## Usage Examples

### Basic Usage
```
${__setProperty(my.property,hello)}
```
Sets the JMeter property `my.property` to `hello`. Returns an empty string.

### Set Property and Return Original Value
```
${__setProperty(my.property,new_value,true)}
```
Sets the JMeter property `my.property` to `new_value` and returns the original value of the property before it was changed.

### Communication Between Thread Groups
```
${__setProperty(thread1.complete,true)}
```
In one thread group, set a property to signal completion. In another thread group, use `${__property(thread1.complete)}` to check the value.

## Notes
- The default return value from the function is the empty string, so the function call can be used anywhere functions are valid.
- The original value can be returned by setting the optional 3rd parameter to `true`.
- Properties are global to JMeter, so they can be used to communicate between threads and thread groups.

## Since
2.1

## Reference
- [Apache JMeter - __setProperty](https://jmeter.apache.org/usermanual/functions.html#__setProperty)
