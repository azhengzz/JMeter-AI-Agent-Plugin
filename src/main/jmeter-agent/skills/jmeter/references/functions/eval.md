# __eval

## Function Name
`__eval`

## Category
Variables

## Description
The eval function returns the result of evaluating a string expression.

This allows one to interpolate variable and function references in a string which is stored in a variable. For example, given the following variables:

- `name`=`Smith`
- `column`=`age`
- `table`=`birthdays`
- `SQL`=`select ${column} from ${table} where name='${name}'`

then `${__eval(${SQL})}` will evaluate as `select age from birthdays where name='Smith'`.

This can be used in conjunction with CSV Dataset, for example where both SQL statements and the values are defined in the data file.

## Parameters

| Parameter | Description | Required | Default |
|-----------|-------------|----------|---------|
| Variable name | The variable to be evaluated. | Yes | - |

## Usage Examples

### Basic Usage
```
${__eval(${SQL})}
```
If the variable `SQL` contains `select ${column} from ${table} where name='${name}'`, and `column`=`age`, `table`=`birthdays`, `name`=`Smith`, then this evaluates as `select age from birthdays where name='Smith'`.

### With CSV Dataset
```
${__eval(${query_template})}
```
Read a query template from a CSV file and evaluate all variable references within it. For example, if the CSV file contains a column with `INSERT INTO ${table} (${columns}) VALUES (${values})`, and the corresponding variables are defined, the full interpolated string will be returned.

## Notes
- This function allows interpolation of variable and function references in a string stored in a variable.
- This can be used in conjunction with CSV Dataset, where both SQL statements and the values are defined in the data file.
- The difference between `__eval` and `__evalVar` is that `__eval` takes the variable expression itself (e.g., `${SQL}`), while `__evalVar` takes just the variable name (e.g., `query`).

## Since
2.3.1

## Reference
- [Apache JMeter - __eval](https://jmeter.apache.org/usermanual/functions.html#__eval)
