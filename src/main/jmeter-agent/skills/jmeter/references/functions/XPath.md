# __XPath

## Function Name
`__XPath`

## Category
Input

## Description
The XPath function reads an XML file and matches the XPath.
Each time the function is called, the next match will be returned.
At end of file, it will wrap around to the start.
If no nodes matched, then the function will return the empty string,
and a warning message will be written to the JMeter log file.

Note that the entire NodeList is held in memory.

## Parameters

| Parameter | Description | Required | Default |
|-----------|-------------|----------|---------|
| XML file to parse | An XML file to parse | Yes | |
| XPath | An XPath expression to match nodes in the XML file | Yes | |

## Usage Examples

### Basic Usage
```
${__XPath(/path/to/build.xml, //target/@name)}
```
This will match all targets in `build.xml` and return the contents of the next name attribute.

### Read XML Element Value
```
${__XPath(/path/to/data.xml, //user/name)}
```
Returns the text content of the next `name` element under `user` elements in `data.xml`.

### Use with Variable
```
${__XPath(${__P(xpath.file)},${XPATH})}
```
Uses a JMeter property for the file path and a variable for the XPath expression.

## Notes
- The entire NodeList is held in memory, so this function may not be suitable for very large XML files.
- At end of file, it will wrap around to the start.
- If no nodes matched, the function will return the empty string, and a warning message will be written to the JMeter log file.

## Since
2.0.3

## Reference
- [Apache JMeter - __XPath](https://jmeter.apache.org/usermanual/functions.html#__XPath)
