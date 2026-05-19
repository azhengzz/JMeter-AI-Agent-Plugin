# __O

## Function Name
`__O`

## Category
Variables

## Description
Extracts a value from a JMeter object variable using a JsonPath expression. Supports reading from `String`, `List`, or `Map` objects stored via `JMeterVariables.putObject()`.

The first parameter uses dot notation to combine the object variable name and JsonPath: `objectName.jsonPathExpression`. A `$` prefix is automatically prepended to the JsonPath internally.

## Parameters

| Parameter | Description | Required | Default |
|-----------|-------------|----------|---------|
| Object dot JsonPath | Object variable name followed by JsonPath, e.g. `myObj.address.city` | Yes | -- |
| Variable name | Name of the JMeter variable to store the extracted result | No | -- |

## Usage Examples

### Extract Nested Field
```
${__O(responseBody.data.user.name)}
```
Extracts `responseBody.data.user.name` from the object variable `responseBody`.

### Extract Array Element
```
${__O(responseBody.items[0].id)}
```
Extracts the `id` field from the first element of the `items` array.

### Get Full Object as JSON
```
${__O(myObj)}
```
Returns the complete JSON string representation of the object variable `myObj` (no JsonPath after the variable name).

### With Variable Storage
```
${__O(responseBody.token,authToken)}
```
Stores the extracted token value in the variable `authToken`.

## Notes
- Object variables must be stored using `JMeterVariables.putObject()`, not the standard `put()` method
- Supported object types: `String` (parsed as JSON), `List`, `Map`; other types return `null`
- If no JsonPath is provided (just the variable name), returns the full JSON string of the object
- Returns `null` if the object is not found, JSON is invalid, or JsonPath is invalid
- Uses Jayway JsonPath library for parsing

## Since
Custom (Gitee extension)

## Reference
- Source: `com.gitee.qa.jmeter.functions.ObjectVariable`
- See also: [__Oe](Oe.md) (escaped version)
