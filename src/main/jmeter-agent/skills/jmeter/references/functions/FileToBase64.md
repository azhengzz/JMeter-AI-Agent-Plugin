# __FileToBase64

## Function Name
`__FileToBase64`

## Category
Input

## Description
Reads the entire content of a file and returns its Base64-encoded string representation. Useful for embedding file content (e.g. images, certificates) into requests.

## Parameters

| Parameter | Description | Required | Default |
|-----------|-------------|----------|---------|
| File path | Full path to the input file | Yes | -- |
| Variable name | Name of the JMeter variable to store the result | No | -- |

## Usage Examples

### Basic Usage
```
${__FileToBase64(/path/to/image.png)}
```
Returns the Base64-encoded content of the file.

### With Variable Storage
```
${__FileToBase64(/path/to/certificate.pem,myCert)}
```
Stores the Base64-encoded file content in the variable `myCert`.

### In HTTP Request Body
```
${__FileToBase64(${__P(upload.file)})}
```
Reads the file path from a JMeter property and encodes its content.

## Notes
- Returns `**ERR**` if the file does not exist, is not readable, or an I/O error occurs
- The entire file is loaded into memory at once; very large files may cause memory issues
- Uses standard `java.util.Base64` encoding (not MIME-compatible, no line breaks)

## Since
Custom (Gitee extension)

## Reference
- Source: `com.gitee.qa.jmeter.functions.FileToBase64`
