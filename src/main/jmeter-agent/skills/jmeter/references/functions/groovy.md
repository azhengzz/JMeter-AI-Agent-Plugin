# __groovy

## Function Name
`__groovy`

## Category
Scripting

## Description
The `__groovy` function evaluates [Apache Groovy](http://groovy-lang.org/) scripts passed to it, and returns the result.

If the property `groovy.utilities` is defined, it will be loaded by the ScriptEngine. This can be used to define common methods and variables. There is a sample init file in the `bin` directory: `utility.groovy`.

The following variables are set before the script is executed:

- `log` - the [Logger](https://www.slf4j.org/api/org/slf4j/Logger.html) for the groovy function (*)
- `ctx` - [JMeterContext](https://jmeter.apache.org/api/org/apache/jmeter/threads/JMeterContext.html) object
- `vars` - [JMeterVariables](https://jmeter.apache.org/api/org/apache/jmeter/threads/JMeterVariables.html) object
- `props` - JMeterProperties (class `java.util.Properties`) object
- `threadName` - the threadName (String)
- `sampler` - the current [Sampler](https://jmeter.apache.org/api/org/apache/jmeter/samplers/Sampler.html), if any
- `prev` - the previous [SampleResult](https://jmeter.apache.org/api/org/apache/jmeter/samplers/SampleResult.html), if any
- `OUT` - System.out

(*) means that this is set before the init file, if any, is processed. Other variables vary from invocation to invocation.

When using this function please use the variables defined above rather than using string replacement to access a variable in your script. Following this pattern will ensure that your tests are performant by ensuring that the Groovy can be cached.

For instance **don't** do the following:

```
${__groovy("${myVar}".substring(0\,2))}
```

Imagine that the variable myVar changes with each transaction, the Groovy above cannot be cached as the script changes each time.

Instead do the following, which can be cached:

```
${__groovy(vars.get("myVar").substring(0\,2))}
```

## Parameters

| Parameter | Description | Required | Default |
|-----------|-------------|----------|---------|
| Expression to evaluate | An Apache Groovy script (not a file name). Argument values that themselves contain commas should be escaped as necessary. If you need to include a comma in your parameter value, escape it like this: `\,` | Yes | - |
| Name of variable | A reference name for reusing the value computed by this function. | No | - |

## Usage Examples

### Basic Usage
```
${__groovy(123*456)}
```
Returns `56088`.

### Using Variables (Recommended)
```
${__groovy(vars.get("myVar").substring(0\,2))}
```
If the variable's value is `JMeter`, it will return `JM` as it runs `String.substring(0,2)`. Note that `,` has been escaped to `\,`.

## Notes
- Remember to include any necessary quotes for text strings and JMeter variables that represent text strings.
- Always use `vars.get()` instead of string substitution to access JMeter variables in your script. This ensures the Groovy script can be cached for better performance.
- If the property `groovy.utilities` is defined, it will be loaded by the ScriptEngine as an init file.
- Commas in parameter values must be escaped with `\,`.

## Since
3.1

## Reference
- [Apache JMeter - __groovy](https://jmeter.apache.org/usermanual/functions.html#__groovy)
