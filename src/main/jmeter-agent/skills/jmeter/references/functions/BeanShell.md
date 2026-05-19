# __BeanShell

## Function Name
`__BeanShell`

## Category
Scripting

## Description
The BeanShell function evaluates the script passed to it, and returns the result.

For performance it is better to use `__groovy` function.

For full details on using BeanShell, please see the BeanShell web-site at [http://www.beanshell.org/](http://www.beanshell.org/).

Note that a different Interpreter is used for each independent occurrence of the function in a test script, but the same Interpreter is used for subsequent invocations. This means that variables persist across calls to the function.

A single instance of a function may be called from multiple threads. However the function `execute()` method is synchronised.

If the property `beanshell.function.init` is defined, it is passed to the Interpreter as the name of a sourced file. This can be used to define common methods and variables. There is a sample init file in the bin directory: `BeanShellFunction.bshrc`.

The following variables are set before the script is executed:

- `log` - the [Logger](https://www.slf4j.org/api/org/slf4j/Logger.html) for the BeanShell function (*)
- `ctx` - [JMeterContext](https://jmeter.apache.org/api/org/apache/jmeter/threads/JMeterContext.html) object
- `vars` - [JMeterVariables](https://jmeter.apache.org/api/org/apache/jmeter/threads/JMeterVariables.html) object
- `props` - JMeterProperties (class `java.util.Properties`) object
- `threadName` - the threadName (String)
- `Sampler` - the current [Sampler](https://jmeter.apache.org/api/org/apache/jmeter/samplers/Sampler.html), if any
- `SampleResult` - the current [SampleResult](https://jmeter.apache.org/api/org/apache/jmeter/samplers/SampleResult.html), if any

(*) means that this is set before the init file, if any, is processed. Other variables vary from invocation to invocation.

## Parameters

| Parameter | Description | Required | Default |
|-----------|-------------|----------|---------|
| BeanShell script | A BeanShell script (not a file name) | Yes | - |
| Name of variable | A reference name for reusing the value computed by this function. | No | - |

## Usage Examples

### Basic Usage
```
${__BeanShell(123*456)}
```
Returns `56088`.

### External Script File
```
${__BeanShell(source("function.bsh"))}
```
Processes the script in `function.bsh`.

## Notes
- Remember to include any necessary quotes for text strings and JMeter variables that represent text strings.
- For performance it is better to use `__groovy` function instead.
- A different Interpreter is used for each independent occurrence of the function in a test script, but the same Interpreter is used for subsequent invocations. Variables persist across calls.
- If the property `beanshell.function.init` is defined, it will be used as an init file.

## Since
1.X

## Reference
- [Apache JMeter - __BeanShell](https://jmeter.apache.org/usermanual/functions.html#__BeanShell)
