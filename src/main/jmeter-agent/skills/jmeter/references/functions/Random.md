# __Random

## Function Name
`__Random`

## Category
Calculation

## Description
The random function returns a random number that lies between the given min and max values.

## Parameters

| Parameter | Description | Required | Default |
|-----------|-------------|----------|---------|
| Minimum value | A number | Yes | - |
| Maximum value | A bigger number | Yes | - |
| Variable Name | A reference name for reusing the value computed by this function. | No | - |

## Usage Examples

### Basic Usage
```
${__Random(0,10)}
```
Returns a random number between 0 and 10.

### With Variable Name
```
${__Random(0,10, MYVAR)}
```
Returns a random number between 0 and 10 and stores it in `MYVAR`. `${MYVAR}` will contain the random number.

### Random Thread Delay
```
${__Random(100,500)}
```
Returns a random number between 100 and 500, useful for simulating variable think times.

## Notes
- The returned value is inclusive of both the minimum and maximum values.

## Since
1.9

## Reference
- [Apache JMeter - __Random](https://jmeter.apache.org/usermanual/functions.html#__Random)
