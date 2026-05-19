# __P

## Function Name
`__P`

## Category
Properties

## Description
This is a simplified property function which is intended for use with properties defined on the command line.
Unlike the `__property` function, there is no option to save the value in a variable, and if no default value is supplied, it is assumed to be 1.
The value of 1 was chosen because it is valid for common test variables such as loops, thread count, ramp up etc.

## Parameters

| Parameter | Description | Required | Default |
|-----------|-------------|----------|---------|
| Property Name | The property name to be retrieved. | Yes | - |
| Default Value | The default value for the property. If omitted, the default is set to `1`. | No | `1` |

## Usage Examples

### Basic Usage
```
${__P(group1.threads)}
```
Returns the value of `group1.threads`. If not defined, returns `1`.

### Define Properties on Command Line
```
jmeter -Jgroup1.threads=7 -Jhostname1=www.realhost.edu
```
Then fetch the values:
```
${__P(group1.threads)}
```
Returns `7`.

```
${__P(group1.loops)}
```
Returns the value of `group1.loops`. If not defined, returns `1`.

### With Default Value
```
${__P(hostname,www.dummy.org)}
```
Returns the value of property `hostname` or `www.dummy.org` if not defined.

## Notes
- This is a simplified version of the `__property` function, intended specifically for command-line defined properties.
- Unlike `__property`, there is no option to save the value in a variable.
- If no default value is supplied, the default is `1`, which is useful for common test variables like loops, thread count, and ramp up.
- Properties are typically defined on the command line using the `-J` flag (e.g., `-Jgroup1.threads=7`).

## Since
2.0

## Reference
- [Apache JMeter - __P](https://jmeter.apache.org/usermanual/functions.html#__P)
