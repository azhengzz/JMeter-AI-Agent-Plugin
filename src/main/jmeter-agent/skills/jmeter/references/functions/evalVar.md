# __evalVar

## Function Name
`__evalVar`

## Category
Variables

## Description
The evalVar function returns the result of evaluating an expression stored in a variable.

This allows one to read a string from a file, and process any variable references in it. For example, if the variable `query` contains `select ${column} from ${table}` and `column` and `table` contain `name` and `customers`, then `${__evalVar(query)}` will evaluate as `select name from customers`.

## Parameters

| Parameter | Description | Required | Default |
|-----------|-------------|----------|---------|
| Variable name | The variable to be evaluated. | Yes | - |

## Usage Examples

### Basic Usage
```
${__evalVar(query)}
```
If the variable `query` contains `select ${column} from ${table}`, and `column`=`name`, `table`=`customers`, then this evaluates as `select name from customers`.

### Read Template from File
```
${__evalVar(email_template)}
```
If the variable `email_template` was loaded from a file and contains `Dear ${firstname} ${lastname}, welcome to ${company}`, and the corresponding variables are defined, the full interpolated string will be returned.

## Notes
- This function allows one to read a string from a file, and process any variable references in it.
- The difference between `__evalVar` and `__eval` is that `__evalVar` takes just the variable name (e.g., `query`), while `__eval` takes the variable expression itself (e.g., `${SQL}`).

## Since
2.3.1

## Reference
- [Apache JMeter - __evalVar](https://jmeter.apache.org/usermanual/functions.html#__evalVar)
