# __V

## Function Name
`__V`

## Category
Variables

## Description
The V (variable) function returns the result of evaluating a variable name expression.
This can be used to evaluate nested variable references (which are not currently supported).

For example, if one has variables `A1`, `A2` and `N`=`1`:

- `${A1}` - works OK
- `${A${N}}` - does not work (nested variable reference)
- `${__V(A${N})}` - works OK. `A${N}` becomes `A1`, and the `__V` function returns the value of `A1`

## Parameters

| Parameter | Description | Required | Default |
|-----------|-------------|----------|---------|
| Variable name | The variable to be evaluated. | Yes | - |
| Default value | The default value in case no variable found, if it's empty and no variable found function returns the variable name. | No | - |

## Usage Examples

### Basic Usage (Nested Variable)
```
${__V(A${N})}
```
If `N`=`1`, this evaluates `A1` and returns the value of variable `A1`.

### Dynamic Variable Access
```
${__V(col_${idx})}
```
If `idx`=`3`, this evaluates `col_3` and returns the value of variable `col_3`.

### With Default Value
```
${__V(A${N},default_value)}
```
If `N`=`1` and `A1` is not defined, returns `default_value`. If `A1` is defined, returns its value.

### Iterate Over Numbered Variables
```
${__V(var_${__counter(TRUE)})}
```
Use with a counter to access `var_1`, `var_2`, `var_3`, etc. in sequence.

## Notes
- Nested variable references like `${A${N}}` are not directly supported in JMeter; use `__V` to evaluate them.
- The function first resolves the inner variable references in the expression, then returns the value of the resulting variable name.
- A default value can be provided as a second parameter. If the resolved variable is not found and no default is provided, the function returns the variable name.

## Since
2.3RC3

## Reference
- [Apache JMeter - __V](https://jmeter.apache.org/usermanual/functions.html#__V)
