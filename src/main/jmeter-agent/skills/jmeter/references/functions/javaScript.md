# __javaScript

## Function Name
`__javaScript`

## Category
Scripting

## Description
The javaScript function executes a piece of JavaScript (not Java!) code and returns its value.

The JMeter Javascript function calls a standalone JavaScript interpreter. Javascript is used as a scripting language, so you can do calculations etc.

javaScript is not the best scripting language for performances in JMeter. If your plan requires a high number of threads it is advised to use `__jexl3` or `__groovy` functions.

For Nashorn Engine, please see [Java Platform, Standard Edition Nashorn User's Guide](https://docs.oracle.com/javase/8/docs/technotes/guides/scripting/nashorn/).
For Rhino engine, please see [Mozilla Rhino Overview](http://www.mozilla.org/rhino/overview.html).

The following variables are made available to the script:

- `log` - the [Logger](https://www.slf4j.org/api/org/slf4j/Logger.html) for the function
- `ctx` - [JMeterContext](https://jmeter.apache.org/api/org/apache/jmeter/threads/JMeterContext.html) object
- `vars` - [JMeterVariables](https://jmeter.apache.org/api/org/apache/jmeter/threads/JMeterVariables.html) object
- `threadName` - String containing the current thread name
- `sampler` - current [Sampler](https://jmeter.apache.org/api/org/apache/jmeter/samplers/Sampler.html) object (if any)
- `sampleResult` - previous [SampleResult](https://jmeter.apache.org/api/org/apache/jmeter/samplers/SampleResult.html) object (if any)
- `props` - JMeterProperties (class `java.util.Properties`) object

Rhinoscript allows access to static methods via its Packages object. See the [Scripting Java](https://wiki.openjdk.java.net/display/Nashorn/Rhino+Migration+Guide) documentation. For example one can access the JMeterContextService static methods thus:
`Java.type("org.apache.jmeter.threads.JMeterContextService").getTotalThreads()`

JMeter is not a browser, and does not interpret the JavaScript in downloaded pages.

## Parameters

| Parameter | Description | Required | Default |
|-----------|-------------|----------|---------|
| Expression | The JavaScript expression to be executed. For example: `new Date()` - return the current date and time; `Math.floor(Math.random()*(${maxRandom}+1))` - a random number between `0` and the variable `maxRandom`; `${minRandom}+Math.floor(Math.random()*(${maxRandom}-${minRandom}+1))` - a random number between the variables `minRandom` and `maxRandom`; `"${VAR}"=="abcd"` | Yes | - |
| Variable Name | A reference name for reusing the value computed by this function. | No | - |

## Usage Examples

### Basic Usage - Get Current Date
```
${__javaScript(new Date())}
```
Returns the current date and time, e.g. `Sat Jan 09 2016 16:22:15 GMT+0100 (CET)`.

### Store Result in Variable
```
${__javaScript(new Date(),MYDATE)}
```
Returns the current date and time and stores it under variable `MYDATE`.

### Random Number with Max
```
${__javaScript(Math.floor(Math.random()*(${maxRandom}+1)),MYRESULT)}
```
Uses the `maxRandom` variable, returns a random value between 0 and maxRandom and stores it in `MYRESULT`.

### Random Number in Range
```
${__javaScript(${minRandom}+Math.floor(Math.random()*(${maxRandom}-${minRandom}+1)),MYRESULT)}
```
Uses `maxRandom` and `minRandom` variables, returns a random value between `maxRandom` and `minRandom` and stores it under variable `MYRESULT`.

### String Comparison
```
${__javaScript("${VAR}"=="abcd",MYRESULT)}
```
Compares the value of `VAR` variable with `abcd`, returns `true` or `false` and stores the result in `MYRESULT`.

### Escaping Commas
```
${__javaScript('${sp}'.slice(7\,99999))}
```
The comma after `7` is escaped with `\,`.

## Notes
- Remember to include any necessary quotes for text strings and JMeter variables.
- If the expression has commas, make sure to escape them with `\,`.
- JMeter is not a browser, and does not interpret the JavaScript in downloaded pages.
- For better performance, especially with a high number of threads, use `__jexl3` or `__groovy` functions instead.

## Since
1.9

## Reference
- [Apache JMeter - __javaScript](https://jmeter.apache.org/usermanual/functions.html#__javaScript)
