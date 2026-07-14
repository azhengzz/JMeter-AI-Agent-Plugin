# CSV Data Set Config
> **Source**: Apache JMeter built-in component (ships with JMeter, no extra install needed)

## Description

CSV Data Set Config is used to read lines from a file, and split them into variables. It is easier to use than the `__CSVRead()` and `__StringFromFile()` functions. It is well suited to handling large numbers of variables, and is also useful for testing with "random" and unique values.

Generating unique random values at run-time is expensive in terms of CPU and memory, so just create the data in advance of the test. If necessary, the "random" data from the file can be used in conjunction with a run-time parameter to create different sets of values from each run - e.g. using concatenation - which is much cheaper than generating everything at run-time.

JMeter allows values to be quoted; this allows the value to contain a delimiter. If "allow quoted data" is enabled, a value may be enclosed in double-quotes. These are removed. To include double-quotes within a quoted field, use two double-quotes. For example:
```
1,"2,3","4""5" =>
1
2,3
4"5
```

JMeter supports CSV files which have a header line defining the column names. To enable this, leave the "Variable Names" field empty. The correct delimiter must be provided.

JMeter supports CSV files with quoted data that includes new-lines.

By default, the file is only opened once, and each thread will use a different line from the file. However the order in which lines are passed to threads depends on the order in which they execute, which may vary between iterations. Lines are read at the start of each test iteration. The file name and mode are resolved in the first iteration.

As a special case, the string `\t` (without quotes) in the delimiter field is treated as a Tab.

When the end of file (EOF) is reached, and the recycle option is `true`, reading starts again with the first line of the file.

If the recycle option is `false`, and stopThread is `false`, then all the variables are set to `<EOF>` when the end of file is reached. This value can be changed by setting the JMeter property `csvdataset.eofstring`.

If the Recycle option is `false`, and Stop Thread is `true`, then reaching EOF will cause the thread to be stopped.

## Parameters

| Property | Required | Default | Description | Example |
|----------|----------|---------|-------------|---------|
| `filename` | Yes | `""` | Name of the file to be read. Relative file names are resolved with respect to the path of the active test plan. For distributed testing, the CSV file must be stored on the server host system in the correct relative directory to where the JMeter server is started. Absolute file names are also supported, but note that they are unlikely to work in remote mode, unless the remote server has the same directory structure. | `"data/users.csv"` |
| `fileEncoding` | Yes | `"UTF-8"` | The encoding to be used to read the file, if not the platform default. | `"UTF-8"` |
| `variableNames` | Yes | `""` | List of variable names. The names must be separated by the delimiter character. They can be quoted using double-quotes. JMeter supports CSV header lines: if the variable name field is empty, then the first line of the file is read and interpreted as the list of column names. | `"username,password,email"` |
| `ignoreFirstLine` | Yes | `false` | Ignore first line of CSV file, it will only be used if Variable Names is not empty, if Variable Names is empty the first line must contain the headers. | `"true"` |
| `delimiter` | Yes | `","` | Delimiter to be used to split the records in the file. If there are fewer values on the line than there are variables the remaining variables are not updated - so they will retain their previous value (if any). The string `\t` (without quotes) is treated as a Tab. | `","` |
| `quotedData` | Yes | `false` | Should the CSV file allow values to be quoted? If enabled, then values can be enclosed in `"` - double-quote - allowing values to contain a delimiter. | `"true"` |
| `recycle` | Yes | `true` | Should the file be re-read from the beginning on reaching EOF? (default is `true`) | `"true"` |
| `stopThread` | Yes | `false` | Should the thread be stopped on EOF, if Recycle is false? (default is `false`) | `"true"` |
| `shareMode` | Yes | `"shareMode.all"` | Sharing mode between threads. See the sharing mode table below. | `"shareMode.all"` |

### Sharing Modes

| Mode | Description |
|------|-------------|
| `shareMode.all` | All threads - (the default) the file is shared between all the threads |
| `shareMode.group` | Current thread group - each file is opened once for each thread group in which the element appears |
| `shareMode.thread` | Current thread - each file is opened separately for each thread |

## Usage Examples

### Example 1: Basic CSV Reading

```
// CSV file: data/users.csv
// username,password,email
// user1,pass123,user1@example.com
// user2,pass456,user2@example.com

create_jmeter_element with:
- elementType: "csvdataset"
- elementName: "读取用户数据"
- properties:
  - filename: "data/users.csv"
  - variableNames: "username,password,email"
  - delimiter: ","
  - recycle: "true"
  - ignoreFirstLine: "true"
```

### Example 2: Unique Data Per Thread (No Recycle)

```
create_jmeter_element with:
- elementType: "csvdataset"
- elementName: "读取唯一用户"
- properties:
  - filename: "data/unique_users.csv"
  - variableNames: "user_id,user_name"
  - recycle: "false"
  - stopThread: "true"
  - shareMode: "shareMode.thread"
```

### Example 3: Tab-Delimited File

```
create_jmeter_element with:
- elementType: "csvdataset"
- elementName: "读取制表符分隔文件"
- properties:
  - filename: "data/products.tsv"
  - variableNames: "product_id,product_name,price"
  - delimiter: "\t"
```

### Example 4: Sharing Data Across Threads

```
// All threads share the same file and position
create_jmeter_element with:
- elementType: "csvdataset"
- elementName: "共享用户池"
- properties:
  - filename: "data/users.csv"
  - variableNames: "username,password"
  - shareMode: "shareMode.all"
```

## Best Practices

1. **File location**: Place CSV files in a `data/` directory relative to the test plan
2. **Encoding**: Use UTF-8 encoding for international characters
3. **Recycle vs Stop**: Use `recycle: "false"` with `stopThread: "true"` for one-time use data
4. **Share mode**: Choose appropriate sharing based on test requirements
5. **Header row**: Use `ignoreFirstLine: "true"` when file has headers, or leave `variableNames` empty to auto-detect

## Notes

- CSV Dataset variables are defined at the start of each test iteration. As this is after configuration processing is completed, they cannot be used for some configuration items - such as JDBC Config - that process their contents at configuration time
- Variables are available to all samplers after the CSV Data Set Config element
- Each thread reads independently unless shareMode is set otherwise
- EOF handling depends on recycle and stopThread settings
- If the same physical file is referenced in two different ways (e.g. `csvdata.txt` and `./csvdata.txt`), these are treated as different files
- Variable names are case-sensitive
