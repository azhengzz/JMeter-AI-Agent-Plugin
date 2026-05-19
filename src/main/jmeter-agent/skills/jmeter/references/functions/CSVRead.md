# __CSVRead

## Function Name
`__CSVRead`

## Category
Input

## Description
The CSVRead function returns a string from a CSV file (c.f. StringFromFile).

NOTE: JMeter supports multiple file names.

In most cases, the newer CSV Data Set Config element is easier to use.

When a filename is first encountered, the file is opened and read into an internal
array. If a blank line is detected, this is treated as end of file - this allows
trailing comments to be used.

All subsequent references to the same file name use the same internal array.
N.B. the filename case is significant to the function, even if the OS doesn't care,
so `CSVRead(abc.txt,0)` and `CSVRead(aBc.txt,0)` would refer to different internal arrays.

The `*ALIAS` feature allows the same file to be opened more than once,
and also allows for shorter file names.

Each thread has its own internal pointer to its current row in the file array.
When a thread first refers to the file it will be allocated the next free row in
the array, so each thread will access a different row from all other threads.
[Unless there are more threads than there are rows in the array.]

The function splits the line at every comma by default.
If you want to enter columns containing commas, then you will need
to change the delimiter to a character that does not appear in any
column data, by setting the property: `csvread.delimiter`.

## Parameters

| Parameter | Description | Required | Default |
|-----------|-------------|----------|---------|
| File Name | The file (or `*ALIAS`) to read from | Yes | |
| Column number | The column number in the file. `0` = first column, `1` = second, etc. `next` - go to next line of file. `*ALIAS` - open a file and assign it to the alias | Yes | |

## Usage Examples

### Basic Usage - Read Column
```
${__CSVRead(random.txt,0)}
```
Reads the first column (column 0) from the current row of `random.txt`.

### Read Multiple Columns from One Line
```
COL1a ${__CSVRead(random.txt,0)}
COL2a ${__CSVRead(random.txt,1)}${__CSVRead(random.txt,next)}
```
Reads two columns from one line. The `next` keyword advances to the next line after reading column 1.

### Read from Consecutive Lines
```
COL1a ${__CSVRead(random.txt,0)}
COL2a ${__CSVRead(random.txt,1)}${__CSVRead(random.txt,next)}
COL1b ${__CSVRead(random.txt,0)}
COL2b ${__CSVRead(random.txt,1)}${__CSVRead(random.txt,next)}
```
This would read two columns from one line, and two columns from the next available line.
If all the variables are defined on the same User Parameters Pre-Processor, then the lines
will be consecutive. Otherwise, a different thread may grab the next line.

### Using ALIAS
```
${__CSVRead(*ALIAS,mydata.txt)}
```
Opens `mydata.txt` and assigns it to an alias for subsequent use.

## Notes
- The function is not suitable for use with large files, as the entire file is stored in memory. For larger files, use CSV Data Set Config element or StringFromFile.
- The filename case is significant to the function, even if the OS doesn't care.
- The function splits the line at every comma by default. To use a different delimiter, set the property `csvread.delimiter`.
- If a blank line is detected, this is treated as end of file - this allows trailing comments to be used.
- Each thread has its own internal pointer to its current row in the file array.

## Since
1.9

## Reference
- [Apache JMeter - __CSVRead](https://jmeter.apache.org/usermanual/functions.html#__CSVRead)
