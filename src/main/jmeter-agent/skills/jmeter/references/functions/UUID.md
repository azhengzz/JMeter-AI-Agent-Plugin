# __UUID

## Function Name
`__UUID`

## Category
Calculation

## Description
The UUID function returns a pseudo random type 4 Universally Unique IDentifier (UUID).

## Parameters

There are no arguments for this function.

## Usage Examples

### Basic Usage
```
${__UUID()}
```
Returns UUIDs with this format: `c69e0dd1-ac6b-4f2b-8d59-5d4e8743eecd`

### Without Parentheses
```
${__UUID}
```
Since this function takes no arguments, parentheses can be omitted.

## Notes
- The function generates a type 4 (pseudo randomly generated) UUID.
- Each call returns a different UUID.
- No parameters are required for this function.

## Since
2.9

## Reference
- [Apache JMeter - __UUID](https://jmeter.apache.org/usermanual/functions.html#__UUID)
