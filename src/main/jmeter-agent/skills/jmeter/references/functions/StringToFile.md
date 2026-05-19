# __StringToFile

## Function Name
`__StringToFile`

## Category
Input

## Description
The `__StringToFile` function can be used to write a string to a file.
Each time it is called it writes a string to file appending or overwriting.

The default return value from the function is the empty string.

## Parameters

| Parameter | Description | Required | Default |
|-----------|-------------|----------|---------|
| Path to file | Path to the file name. (The path is absolute) | Yes | |
| String to write | The string to write to the file. If you need to insert a line break in your content, use `\n` in your string. | Yes | |
| Append to file? | The way to write the string, `true` means append, `false` means overwrite. If not specified, the default append is `true`. | No | `true` |
| File encoding if not UTF-8 | The encoding to be used to write to the file. If not specified, the default encoding is `UTF-8`. | No | `UTF-8` |

## Usage Examples

### Basic Usage - Append
```
${__StringToFile(/path/to/output.txt,Hello World)}
```
Appends the string "Hello World" to `/path/to/output.txt` using UTF-8 encoding.

### With Line Breaks
```
${__StringToFile(/path/to/log.txt,Line 1\nLine 2\nLine 3)}
```
Writes three lines to the file. Use `\n` to insert line breaks in the content.

### Overwrite Mode
```
${__StringToFile(/path/to/output.txt,Fresh content,false)}
```
Overwrites the entire file with "Fresh content" instead of appending.

### With Custom Encoding
```
${__StringToFile(/path/to/output.txt,Some text,true,ISO-8859-1)}
```
Appends "Some text" to the file using ISO-8859-1 encoding.

## Notes
- The file path must be an absolute path.
- The default return value from the function is the empty string.
- Use `\n` to insert line breaks in the content string.
- By default, the function appends to the file (`true`). Set the third parameter to `false` to overwrite.
- The default file encoding is `UTF-8`.

## Since
5.2

## Reference
- [Apache JMeter - __StringToFile](https://jmeter.apache.org/usermanual/functions.html#__StringToFile)
