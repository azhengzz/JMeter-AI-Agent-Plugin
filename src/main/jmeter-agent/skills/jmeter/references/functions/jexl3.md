# __jexl3

## Function Name
`__jexl3`

## Category
Scripting

## Description
The jexl function returns the result of evaluating a [Commons JEXL expression](http://commons.apache.org/proper/commons-jexl/). See links below for more information on JEXL expressions.

The `__jexl3` function uses Commons JEXL 3.

- [JEXL syntax description](http://commons.apache.org/proper/commons-jexl/reference/syntax.html)
- [JEXL examples](http://commons.apache.org/proper/commons-jexl/reference/examples.html#Example_Expressions)

The following variables are made available to the script:

- `log` - the [Logger](https://www.slf4j.org/api/org/slf4j/Logger.html) for the function
- `ctx` - [JMeterContext](https://jmeter.apache.org/api/org/apache/jmeter/threads/JMeterContext.html) object
- `vars` - [JMeterVariables](https://jmeter.apache.org/api/org/apache/jmeter/threads/JMeterVariables.html) object
- `props` - JMeterProperties (class `java.util.Properties`) object
- `threadName` - String containing the current thread name
- `sampler` - current [Sampler](https://jmeter.apache.org/api/org/apache/jmeter/samplers/Sampler.html) object (if any)
- `sampleResult` - previous [SampleResult](https://jmeter.apache.org/api/org/apache/jmeter/samplers/SampleResult.html) object (if any)
- `OUT` - System.out - e.g. `OUT.println("message")`

## Parameters

| Parameter | Description | Required | Default |
|-----------|-------------|----------|---------|
| Expression | The expression to be evaluated. For example, `6*(5+2)` | Yes | - |
| Name of variable | The name of the variable to set. | No | - |

## Usage Examples

### Basic Usage
```
${__jexl3(6*(5+2))}
```
Returns `42`.

### Creating Classes and Calling Methods
```
Systemclass=log.class.forName("java.lang.System");
now=Systemclass.currentTimeMillis();
```
Jexl can create classes and call methods on them.

### Integer Division
```
i= 5 / 2;
i.intValue(); // or use i.longValue()
```
Note that the Jexl documentation on the web-site wrongly suggests that `div` does integer division. In fact `div` and `/` both perform normal division. Use `.intValue()` or `.longValue()` to get integer results.

## Notes
- JMeter allows the expression to contain multiple statements.
- Note that the Jexl documentation on the web-site wrongly suggests that `div` does integer division. In fact `div` and `/` both perform normal division. Use `.intValue()` or `.longValue()` to get integer results.
- Jexl can create classes and call methods on them.
- This function uses Commons JEXL 3, which is the newer version compared to `__jexl2`.

## Since
jexl3 (3.0)

## Reference
- [Apache JMeter - __jexl3](https://jmeter.apache.org/usermanual/functions.html#__jexl3)
