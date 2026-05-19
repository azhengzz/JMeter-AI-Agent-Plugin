# __regexFunction

## Function Name
`__regexFunction`

## Category
String

## Description
The Regex Function is used to parse the previous response (or the value of a variable) using any regular expression (provided by user). The function returns the template string with variable values filled in.

The `__regexFunction` can also store values for future use. In the sixth parameter, you can specify a reference name. After this function executes, the same values can be retrieved at later times using the syntax for user-defined values. For instance, if you enter `refName` as the sixth parameter you will be able to use:

- `${refName}` to refer to the computed result of the second parameter ("Template for the replacement string") parsed by this function
- `${refName_g0}` to refer to the entire match parsed by this function
- `${refName_g1}` to refer to the first group parsed by this function
- `${refName_g#}` to refer to the nth group parsed by this function
- `${refName_matchNr}` to refer to the number of groups found by this function

If using distributed testing, ensure you switch mode (see `jmeter.properties`) so that it's not a stripping one, see [Bug 56376](https://bz.apache.org/bugzilla/show_bug.cgi?id=56376).

## Parameters

| Parameter | Description | Required | Default |
|-----------|-------------|----------|---------|
| First argument | The first argument is the regular expression to be applied to the response data. It will grab all matches. Any parts of this expression that you wish to use in your template string, be sure to surround in parentheses. Example: `<a href="(.*)">`. This will grab the value of the link and store it as the first group (there is only 1 group). Another example: `<input type="hidden" name=(.*)" value="(.*)">`. This will grab the name as the first group, and the value as the second group. These values can be used in your template string. | Yes | - |
| Second argument | This is the template string that will replace the function at run-time. To refer to a group captured in the regular expression, use the syntax: `$[group_number]$`. I.e.: `$1$`, or `$2$`. Your template can be any string. | Yes | - |
| Third argument | The third argument tells JMeter which match to use. Your regular expression might find numerous matches. You have four choices:<ul><li>An integer - Tells JMeter to use that match. `1` for the first found match, `2` for the second, and so on.</li><li>`RAND` - Tells JMeter to choose a match at random.</li><li>`ALL` - Tells JMeter to use all matches, and create a template string for each one and then append them all together. This option is little used.</li><li>A float number between 0 and 1 - tells JMeter to find the Xth match using the formula: (number_of_matches_found * float_number) rounded to nearest integer.</li></ul> | No | 1 |
| Fourth argument | If `ALL` was selected for the above argument value, then this argument will be inserted between each appended copy of the template value. | No | - |
| Fifth argument | Default value returned if no match is found. | No | - |
| Sixth argument | A reference name for reusing the values parsed by this function. Stored values are `${refName}` (the replacement template string) and `${refName_g#}` where `#` is the group number from the regular expression (`0` can be used to refer to the entire match). | No | - |
| Seventh argument | Input variable name. If specified, then the value of the variable is used as the input instead of using the previous sample result. | No | - |

## Usage Examples

### Basic Usage
```
${__regexFunction(<a href="(.*)">,$1$)}
```
Applies the regular expression `<a href="(.*)">` to the previous response and returns the first captured group (the URL).

### Extract Hidden Form Field
```
${__regexFunction(<input type="hidden" name="(.*)" value="(.*)">,$1$=$2$,1)}
```
Extracts the name and value from a hidden form field and returns them as `name=value`.

### Store Result in Variable
```
${__regexFunction(<title>(.*?)</title>,$1$,1,,titleVar)}
```
Extracts the page title and stores it in the variable `titleVar`. You can later reference `${titleVar}` for the result, `${titleVar_g0}` for the entire match, and `${titleVar_g1}` for the first group.

### Use All Matches
```
${__regexFunction(<a href="(.*?)">,$1$,ALL,,linkVar)}
```
Collects all link URLs from the response and appends them together.

### Use Input Variable Instead of Previous Response
```
${__regexFunction(<tag>(.*?)</tag>,$1$,1,,,inputVar)}
```
Parses the content of the variable `inputVar` instead of the previous sample result.

## Notes
- The `__regexFunction` can store values for future use via the reference name (sixth parameter).
- After execution with a reference name, you can access: `${refName}` (template result), `${refName_g0}` (entire match), `${refName_g1}` through `${refName_g#}` (groups), and `${refName_matchNr}` (number of matches found).
- If using distributed testing, ensure you switch mode (see `jmeter.properties`) so that it's not a stripping one, see [Bug 56376](https://bz.apache.org/bugzilla/show_bug.cgi?id=56376).
- Groups in the regular expression are captured using parentheses and referenced in the template using `$[group_number]$` syntax (e.g., `$1$`, `$2$`).
- The seventh parameter allows parsing a variable's value instead of the previous sample result.

## Since
1.X

## Reference
- [Apache JMeter - __regexFunction](https://jmeter.apache.org/usermanual/functions.html#__regexFunction)
