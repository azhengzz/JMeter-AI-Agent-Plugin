# JMeter Functions Reference

> Based on [Apache JMeter Functions and Variables](https://jmeter.apache.org/usermanual/functions.html)

## Overview

JMeter functions are special values that can populate fields of any Sampler or other element in a test tree. A function call looks like this:

```
${__functionName(var1,var2,var3)}
```

Where `__functionName` matches the name of a function. Functions that require no parameters can leave off the parentheses.

## Functions List

| Type of function | Name | Comment |
|-----------------|------|---------|
| Information | [threadNum](threadNum.md) | get thread number |
| Information | [threadGroupName](threadGroupName.md) | get thread group name |
| Information | [samplerName](samplerName.md) | get the sampler name (label) |
| Information | [machineIP](machineIP.md) | get the local machine IP address |
| Information | [machineName](machineName.md) | get the local machine name |
| Information | [time](time.md) | return current time in various formats |
| Information | [timeShift](timeShift.md) | return a date in various formats with the specified amount of seconds/minutes/hours/days added |
| Information | [log](log.md) | log (or display) a message (and return the value) |
| Information | [logn](logn.md) | log (or display) a message (empty return value) |
| Input | [StringFromFile](StringFromFile.md) | read a line from a file |
| Input | [FileToString](FileToString.md) | read an entire file |
| Input | [CSVRead](CSVRead.md) | read from CSV delimited file |
| Input | [XPath](XPath.md) | use an XPath expression to read from a file |
| Input | [StringToFile](StringToFile.md) | write a string to a file |
| Calculation | [counter](counter.md) | generate an incrementing number |
| Formatting | [dateTimeConvert](dateTimeConvert.md) | convert a date or time from source to target format |
| Calculation | [digest](digest.md) | generate a digest (SHA-1, SHA-256, MD5...) |
| Calculation | [intSum](intSum.md) | add int numbers |
| Calculation | [longSum](longSum.md) | add long numbers |
| Calculation | [Random](Random.md) | generate a random number |
| Calculation | [RandomDate](RandomDate.md) | generate random date within a specific date range |
| Calculation | [RandomFromMultipleVars](RandomFromMultipleVars.md) | extract an element from the values of a set of variables separated by `\|` |
| Calculation | [RandomString](RandomString.md) | generate a random string |
| Calculation | [UUID](UUID.md) | generate a random type 4 UUID |
| Scripting | [groovy](groovy.md) | run an Apache Groovy script |
| Scripting | [BeanShell](BeanShell.md) | run a BeanShell script |
| Scripting | [javaScript](javaScript.md) | process JavaScript (Nashorn) |
| Scripting | [jexl2](jexl2.md) | evaluate a Commons Jexl2 expression |
| Scripting | [jexl3](jexl3.md) | evaluate a Commons Jexl3 expression |
| Properties | [isPropDefined](isPropDefined.md) | test if a property exists |
| Properties | [property](property.md) | read a property |
| Properties | [P](P.md) | read a property (shorthand method) |
| Properties | [setProperty](setProperty.md) | set a JMeter property |
| Properties | [isVarDefined](isVarDefined.md) | test if a variable exists |
| Variables | [split](split.md) | split a string into variables |
| Variables | [eval](eval.md) | evaluate a variable expression |
| Variables | [evalVar](evalVar.md) | evaluate an expression stored in a variable |
| Variables | [V](V.md) | evaluate a variable name |
| String | [char](char.md) | generate Unicode char values from a list of numbers |
| String | [changeCase](changeCase.md) | change case following different modes |
| String | [escapeHtml](escapeHtml.md) | encode strings using HTML encoding |
| String | [escapeOroRegexpChars](escapeOroRegexpChars.md) | quote meta chars used by ORO regular expression |
| String | [escapeXml](escapeXml.md) | encode strings using XML encoding |
| String | [regexFunction](regexFunction.md) | parse previous response using a regular expression |
| String | [unescape](unescape.md) | process strings containing Java escapes (e.g. \n & \t) |
| String | [unescapeHtml](unescapeHtml.md) | decode HTML-encoded strings |
| String | [urldecode](urldecode.md) | decode a application/x-www-form-urlencoded string |
| String | [urlencode](urlencode.md) | encode a string to a application/x-www-form-urlencoded string |
| String | [TestPlanName](TestPlanName.md) | return name of current test plan |
| Encryption | [aesEncrypt](aesEncrypt.md) | AES/CBC/PKCS5Padding encryption with Base64 output |
| Encryption | [rsaEncrypt](rsaEncrypt.md) | RSA public key encryption with Base64 output |
| Information | [threadGroupActiveThreadNum](threadGroupActiveThreadNum.md) | get active thread count in current thread group |
| Calculation | [timePick](timePick.md) | pick a specific date by week/month/year day index |
| Calculation | [timeToTimestamp](timeToTimestamp.md) | convert formatted date string to Unix timestamp |
| Input | [FileToBase64](FileToBase64.md) | read a file and return Base64-encoded content |
| Variables | [O](O.md) | extract value from object variable using JsonPath |
| Variables | [Oe](Oe.md) | extract and escape value from object variable using JsonPath |



## Usage Notes

1. **Function Parameter Escaping**: If a function parameter contains a comma, escape it with `\`:
   ```
   ${__time(EEE\, d MMM yyyy)}
   ```

2. **Variables vs Properties**:
   - Variables are local to a thread
   - Properties are common to all threads and need to be referenced using `__P` or `__property`

3. **Undefined Functions**: If an undefined function or variable is referenced, JMeter does not report/log an error - the reference is returned unchanged.

4. **Case Sensitivity**: Variables, functions, and properties are all case-sensitive.

5. **Whitespace Trimming**: JMeter trims spaces from variable names before use.

## Reference

- [Apache JMeter Official Documentation](https://jmeter.apache.org/usermanual/functions.html)
