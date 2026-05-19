# __escapeOroRegexpChars

## Function Name
`__escapeOroRegexpChars`

## Category
String

## Description
Function which escapes the ORO Regexp meta characters, it is the equivalent of `\Q` `\E` in Java Regexp Engine.

For example:

```
${__escapeOroRegexpChars([^".+?,)}
```

returns `\[\^\"\]\.\+\?`.

Uses `Perl5Compiler#quotemeta(String)` from ORO.

## Parameters

| Parameter | Description | Required | Default |
|-----------|-------------|----------|---------|
| String to escape | The string to be escaped. | Yes | - |
| Variable Name | A reference name - `refName` - for reusing the value created by this function. Stored values are of the form `${refName}`. | No | - |

## Usage Examples

### Basic Usage
```
${__escapeOroRegexpChars([^".+?,)}
```
Returns `\[\^\"\]\.\+\?` with all ORO regexp meta characters escaped.

### With Variable Name
```
${__escapeOroRegexpChars([^".+?,,escapedPattern)}
```
Stores the escaped result in the variable `escapedPattern` for later reuse.

## Notes
- This function is the equivalent of `\Q` `\E` in the Java Regexp Engine.
- Uses `Perl5Compiler#quotemeta(String)` from ORO.
- Useful when you need to use a literal string as a regular expression pattern without having meta characters interpreted.

## Since
2.9

## Reference
- [Apache JMeter - __escapeOroRegexpChars](https://jmeter.apache.org/usermanual/functions.html#__escapeOroRegexpChars)
