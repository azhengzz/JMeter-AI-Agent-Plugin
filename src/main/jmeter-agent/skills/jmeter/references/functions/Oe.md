# __Oe

## Function Name
`__Oe`

## Category
Variables

## Description
Same as `__O` but the result is escaped for safe embedding in JSON strings: backslashes (`\`) become `\\` and double quotes (`"`) become `\"`. Useful when injecting a JSON string value into a larger JSON payload.

## Parameters

| Parameter | Description | Required | Default |
|-----------|-------------|----------|---------|
| Object dot JsonPath | Object variable name followed by JsonPath, e.g. `myObj.address.city` | Yes | -- |
| Variable name | Name of the JMeter variable to store the extracted result | No | -- |

## Usage Examples

### Extract and Escape for JSON Embedding
```
${__Oe(responseBody.data.description)}
```
Returns the value with backslashes and quotes escaped, suitable for embedding in a JSON string literal.

### In JSON Body
```
{"comment": "${__Oe(responseBody.data.text)}"}
```
Safely embeds the extracted value as a JSON string value.

## Notes
- Only escapes `\` and `"` characters; does not escape newlines, tabs, or other control characters
- Internally delegates to `__O` for the actual extraction, then applies escaping
- Returns `null` if the underlying `__O` extraction fails

## Since
Custom (Gitee extension)

## Reference
- Source: `com.gitee.qa.jmeter.functions.ObjectVariableEscape`
- See also: [__O](O.md) (unescaped version)
