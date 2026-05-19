# __FileToString

## Function Name
`__FileToString`

## Category
Input

## Description
The FileToString function can be used to read an entire file.
Each time it is called it reads the entire file.

If an error occurs opening or reading the file, then the function returns the string `**ERR**`.

## Parameters

| Parameter | Description | Required | Default |
|-----------|-------------|----------|---------|
| File Name | Path to the file name. (The path can be relative to the JMeter launch directory) | Yes | |
| File encoding if not the platform default | The encoding to be used to read the file. If not specified, the platform default is used. | No | Platform default |
| Variable Name | A reference name - `refName` - for reusing the value created by this function. Stored values are of the form `${refName}`. | No | |

## Usage Examples

### Basic Usage
```
${__FileToString(test.txt)}
```
Reads the entire content of `test.txt` using the platform default encoding.

### With Encoding
```
${__FileToString(test.txt,UTF-8)}
```
Reads the entire content of `test.txt` using UTF-8 encoding.

### With Variable Name
```
${__FileToString(test.txt,UTF-8,fileContent)}
```
Reads the entire content of `test.txt` using UTF-8 encoding and stores the result in the variable `${fileContent}`.

## Notes
- The file name, encoding and reference name parameters are resolved every time the function is executed.
- If an error occurs opening or reading the file, the function returns the string `**ERR**`.

## Since
2.4

## Reference
- [Apache JMeter - __FileToString](https://jmeter.apache.org/usermanual/functions.html#__FileToString)
