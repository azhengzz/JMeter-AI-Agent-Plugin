# __property

## Function Name
`__property`

## Category
Properties

## Description
The property function returns the value of a JMeter property.
If the property value cannot be found, and no default has been supplied, it returns the property name.
When supplying a default value, there is no need to provide a function name - the parameter can be set to null, and it will be ignored.

## Parameters

| Parameter | Description | Required | Default |
|-----------|-------------|----------|---------|
| Property Name | The property name to be retrieved. | Yes | - |
| Variable Name | A reference name for reusing the value computed by this function. | No | - |
| Default Value | The default value for the property. | No | - |

## Usage Examples

### Basic Usage
```
${__property(user.dir)}
```
Returns the value of `user.dir`.

### Store Property Value in a Variable
```
${__property(user.dir,UDIR)}
```
Returns the value of `user.dir` and saves it in the variable `UDIR`.

### With Default Value and Variable
```
${__property(abcd,ABCD,atod)}
```
Returns the value of property `abcd` (or `atod` if not defined) and saves it in `ABCD`.

### With Default Value Only (No Variable)
```
${__property(abcd,,atod)}
```
Returns the value of property `abcd` (or `atod` if not defined) but does not save it. Note the empty second parameter (variable name is omitted by using `null`).

## Notes
- If the property value cannot be found and no default has been supplied, the function returns the property name itself.
- When supplying a default value but not wanting to save to a variable, set the Variable Name parameter to empty (null).
- Properties are global to JMeter, so they can be used to communicate between threads and thread groups.

## Since
2.0

## Reference
- [Apache JMeter - __property](https://jmeter.apache.org/usermanual/functions.html#__property)
