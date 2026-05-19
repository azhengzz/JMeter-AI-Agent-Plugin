# __counter

## Function Name
`__counter`

## Category
Calculation

## Description
The counter generates a new number each time it is called, starting with 1 and incrementing by +1 each time. The counter can be configured to keep each simulated user's values separate, or to use the same counter for all users. If each user's values is incremented separately, that is like counting the number of iterations through the test plan. A global counter is like counting how many times that request was run.

The counter uses an integer variable to hold the count, which therefore has a maximum of 2,147,483,647.

The counter function instances are completely independent. The global counter - `FALSE` - is separately maintained by each counter instance.

**Multiple `__counter` function calls in the same iteration won't increment the value further.**

If you want to have a count that increments for each sample, use the function in a Pre-Processor such as User Parameters.

## Parameters

| Parameter | Description | Required | Default |
|-----------|-------------|----------|---------|
| First argument | `TRUE` if you wish each simulated user's counter to be kept independent and separate from the other users. `FALSE` for a global counter. | Yes | - |
| Second argument | A reference name for reusing the value created by this function. Stored values are of the form `${refName}`. This allows you to keep one counter and refer to its value in multiple places. | No | - |

## Usage Examples

### Basic Usage
```
${__counter(TRUE)}
```
Returns an incrementing number starting from 1, with a separate counter for each simulated user.

### Global Counter
```
${__counter(FALSE)}
```
Uses a global counter shared across all users.

### With Variable Name
```
${__counter(TRUE,MYVAR)}
```
Returns the counter value and stores it in the `MYVAR` variable. You can then reference `${MYVAR}` elsewhere.

## Notes
- The counter uses an integer variable to hold the count, so the maximum value is 2,147,483,647.
- Multiple `__counter` function calls in the same iteration won't increment the value further.
- If you want a count that increments for each sample, use the function in a Pre-Processor such as User Parameters.
- Counter function instances are completely independent. The global counter (`FALSE`) is separately maintained by each counter instance.

## Since
1.X

## Reference
- [Apache JMeter - __counter](https://jmeter.apache.org/usermanual/functions.html#__counter)
