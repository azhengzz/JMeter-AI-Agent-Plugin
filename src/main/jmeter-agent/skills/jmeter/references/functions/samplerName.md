# __samplerName

## Function Name
`__samplerName`

## Category
Information

## Description
The samplerName function returns the name (i.e. label) of the current sampler.

The function does not work in Test elements that don't have an associated sampler. For example the Test Plan. Configuration elements also don't have an associated sampler. However some Configuration elements are referenced directly by samplers, such as the HTTP Header Manager and Http Cookie Manager, and in this case the functions are resolved in the context of the Http Sampler. Pre-Processors, Post-Processors and Assertions always have an associated Sampler.

## Parameters

| Parameter | Description | Required | Default |
|-----------|-------------|----------|---------|
| Variable Name | A reference name - `refName` - for reusing the value created by this function. Stored values are of the form `${refName}`. | No | - |

## Usage Examples

### Basic Usage
```
${__samplerName()}
```
Returns the name (label) of the current sampler.

## Notes
- The function does not work in Test elements that don't have an associated sampler (e.g. Test Plan).
- Configuration elements also don't have an associated sampler.
- Some Configuration elements are referenced directly by samplers, such as the HTTP Header Manager and Http Cookie Manager, and in this case the functions are resolved in the context of the Http Sampler.
- Pre-Processors, Post-Processors and Assertions always have an associated Sampler.

## Since
2.5

## Reference
- [Apache JMeter - __samplerName](https://jmeter.apache.org/usermanual/functions.html#__samplerName)
