# __threadNum

## Function Name
`__threadNum`

## Category
Information

## Description
The thread number function simply returns the number of the thread currently being executed. These numbers are only locally unique with respect to their ThreadGroup, meaning thread #1 in one threadgroup is indistinguishable from thread #1 in another threadgroup, from the point of view of this function.

The function returns a number between one and the max number of running threads configured in the containing Thread Group.

Note: If you're using JSR223 code with JMeterContext object (ctx variable), `ctx.getThreadNum()` returns a number between zero and (max number of running threads minus one).

## Parameters

There are no arguments for this function.

## Usage Examples

### Basic Usage
```
${__threadNum}
```
Returns a number between 1 and the max number of running threads.

### In HTTP Request
```
/user?id=${__threadNum}
```
Each thread will use its own thread number as the user ID.

## Notes
- This function does not work in any Configuration elements (e.g. User Defined Variables) as these are run from a separate thread.
- Does not make sense to use it on the Test Plan.
- The numbers are only locally unique within their ThreadGroup.

## Since
1.X

## Reference
- [Apache JMeter - __threadNum](https://jmeter.apache.org/usermanual/functions.html#__threadNum)
