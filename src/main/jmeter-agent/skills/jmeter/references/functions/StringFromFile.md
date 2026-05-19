# __StringFromFile

## Function Name
`__StringFromFile`

## Category
Input

## Description
The StringFromFile function can be used to read strings from a text file.
This is useful for running tests that require lots of variable data.
For example when testing a banking application, 100s or 1000s of different account numbers might be required.

See also the CSV Data Set Config test element which may be easier to use. However, that does not currently support multiple input files.

Each time it is called it reads the next line from the file.
All threads share the same instance, so different threads will get different lines.
When the end of the file is reached, it will start reading again from the beginning,
unless the maximum loop count has been reached.
If there are multiple references to the function in a test script, each will open the file independently,
even if the file names are the same.
[If the value is to be used again elsewhere, use different variable names for each function call.]

Function instances are shared between threads, and the file is (re-)opened by whatever thread
happens to need the next line of input, so using the `threadNumber` as part of the file name
will result in unpredictable behaviour.

If an error occurs opening or reading the file, then the function returns the string `**ERR**`.

## Parameters

| Parameter | Description | Required | Default |
|-----------|-------------|----------|---------|
| File Name | Path to the file name. (The path can be relative to the JMeter launch directory) If using optional sequence numbers, the path name should be suitable for passing to DecimalFormat. See below for examples. | Yes | |
| Variable Name | A reference name - `refName` - for reusing the value created by this function. Stored values are of the form `${refName}`. Defaults to `StringFromFile_`. | No | `StringFromFile_` |
| Start sequence number | Initial Sequence number (if omitted, the End sequence number is treated as a loop count) | No | |
| End sequence number | Final sequence number (if omitted, sequence numbers can increase without limit) | No | |

## Usage Examples

### Basic Usage
```
${__StringFromFile(test.txt)}
```
Reads the next line from `test.txt` on each call.

### With Sequence Numbers
```
${__StringFromFile(PIN#'.'DAT,,1,2)}
```
Reads `PIN1.DAT`, `PIN2.DAT`. The `#` is replaced by the sequence number, and the dot is enclosed in single quotes to be treated literally.

### With Loop Count (No Sequence Numbers)
```
${__StringFromFile(PIN.DAT,,,2)}
```
Reads `PIN.DAT` twice. The start number is omitted, so the file name is used exactly as is, and the end number is treated as a loop count.

### Format String Examples
```
${__StringFromFile(pin#'.'dat,,,)}
```
Generates file names: `pin1.dat`, ..., `pin9.dat`, `pin10.dat`, ..., `pin9999.dat`

```
${__StringFromFile(pin000'.'dat,,,)}
```
Generates file names with leading zeros: `pin001.dat`, ..., `pin099.dat`, ..., `pin999.dat`, ..., `pin9999.dat`

```
${__StringFromFile(pin'.'dat#,,,)}
```
Appends digits without leading zeros: `pin.dat1`, ..., `pin.dat9`, ..., `pin.dat999`

## Notes
- The file name parameter is resolved when the file is opened or re-opened.
- The reference name parameter (if supplied) is resolved every time the function is executed.
- When using the optional sequence numbers, the path name is used as the format string for `java.text.DecimalFormat`. The current sequence number is passed in as the only parameter.
- Useful formatting sequences:
  - `#` - insert the number, with no leading zeros or spaces
  - `000` - insert the number packed out to three digits with leading zeros if necessary
- To prevent a formatting character from being interpreted, enclose it in single quotes. Note that `.` is a formatting character, and must be enclosed in single quotes (though `#.` and `000.` work as expected in locales where the decimal point is also `.`).
- In other locales (e.g. `fr`), the decimal point is `,` - which means that `#.` becomes `nnn,`.
- If the path name does not contain any special formatting characters, the current sequence number will be appended to the name, otherwise the number will be inserted according to the formatting instructions.
- If the start sequence number is omitted, and the end sequence number is specified, the sequence number is interpreted as a loop count, and the file will be used at most `end` times. In this case the filename is not formatted.
- Function instances are shared between threads - using `threadNumber` as part of the file name will result in unpredictable behaviour.

## Since
1.9

## Reference
- [Apache JMeter - __StringFromFile](https://jmeter.apache.org/usermanual/functions.html#__StringFromFile)
