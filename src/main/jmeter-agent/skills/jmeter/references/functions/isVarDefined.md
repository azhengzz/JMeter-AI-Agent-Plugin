# __isVarDefined

## Function Name
`__isVarDefined`

## Category
Properties

## Description
The `__isVarDefined` function returns true if variable exists or false if not.

## Parameters

| Parameter | Description | Required | Default |
|-----------|-------------|----------|---------|
| Variable Name | The Variable Name to be used to check if defined | Yes | - |

## Usage Examples

### Basic Usage
```
${__isVarDefined(JMeterThread.last_sample_ok)}
```
Returns `true` because `JMeterThread.last_sample_ok` is a built-in JMeter variable.

### Check Custom Variable
```
${__isVarDefined(my.extracted.value)}
```
Returns `true` if the variable `my.extracted.value` has been defined (e.g., by a Regular Expression Extractor or JSON Post Processor), or `false` if it has not been defined.

## Notes
- This function is useful for conditional logic in test plans where behavior should differ based on whether a variable has been defined.
- Can be used in combination with If Controller to branch test plan execution based on the existence of a variable.
- Commonly used to check whether an extractor (e.g., Regex Extractor, JSON Path Post Processor) successfully captured a value.

## Since
4.0

## Reference
- [Apache JMeter - __isVarDefined](https://jmeter.apache.org/usermanual/functions.html#__isVarDefined)
