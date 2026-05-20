# Excel Data Set Config

## Description

Excel Data Set Config reads data from Excel files (.xlsx, .xls) and splits each row into JMeter variables. It is designed as a drop-in alternative to CSV Data Set Config for test data stored in spreadsheets.

Key features:

- **Automatic header detection** — if Variable Names is empty, the first row of the Excel file is used as column headers
- **Ignore first line** — optionally skip the header row when Variable Names is explicitly set
- **Thread sharing modes** — control how the file pointer is shared between threads (all threads, per group, or per thread)
- **Recycle and stop** — configure EOF behavior to either loop back or stop the thread

This component is useful when test data originates from spreadsheet-based test case management tools and avoids the need to convert Excel files to CSV.

## Parameters

| Property | Required | Default | Description | Example |
|----------|----------|---------|-------------|---------|
| `filename` | Yes | `""` | Path to the Excel file. Relative paths are resolved against the test plan directory. | `"data/testdata.xlsx"` |
| `variableNames` | No | `""` | Comma-separated list of variable names matching Excel columns. Leave empty to auto-detect from the first row. | `"username,password,email"` |
| `ignoreFirstLine` | No | `false` | Ignore the first line of the Excel file. Only effective when Variable Names is not empty. | `true` |
| `recycle` | No | `true` | Re-read the file from the start when EOF is reached. | `false` |
| `stopThread` | No | `false` | Stop the thread when EOF is reached (requires `recycle=false`). | `true` |
| `shareMode` | No | `"shareMode.all"` | Sharing mode between threads. | `"shareMode.group"` |

### Sharing Modes

| Mode | Description |
|------|-------------|
| `shareMode.all` | All threads share the same file pointer (default) |
| `shareMode.group` | Each thread group gets its own file pointer |
| `shareMode.thread` | Each thread gets its own file pointer |

## Usage Examples

### Example 1: Auto-Detect Column Headers from Excel

```
// Excel file: data/users.xlsx
// Row 1 (header): username | password | email
// Row 2:         user1     | pass1    | user1@test.com
// Row 3:         user2     | pass2    | user2@test.com

create_jmeter_element with:
- elementType: "exceldataconfig"
- elementName: "读取用户数据"
- properties:
  - filename: "data/users.xlsx"
  - variableNames: ""
  - recycle: "true"
```

### Example 2: Explicit Variable Names with Header Skip

```
create_jmeter_element with:
- elementType: "exceldataconfig"
- elementName: "读取测试用例"
- properties:
  - filename: "data/testcases.xlsx"
  - variableNames: "case_id,api_url,method,expected_code"
  - ignoreFirstLine: "true"
  - recycle: "true"
```

### Example 3: Unique Data Per Thread (Stop on EOF)

```
create_jmeter_element with:
- elementType: "exceldataconfig"
- elementName: "唯一用户数据"
- properties:
  - filename: "data/unique_users.xlsx"
  - variableNames: "user_id,token"
  - ignoreFirstLine: "true"
  - recycle: "false"
  - stopThread: "true"
  - shareMode: "shareMode.thread"
```

## Best Practices

1. **File location** — place Excel files in a `data/` directory relative to the test plan
2. **Auto-detect headers** — leave `variableNames` empty when the first row contains column names
3. **Recycle vs Stop** — use `recycle: false` with `stopThread: true` for one-time use data
4. **Share mode** — use `shareMode.thread` when each thread needs its own independent data stream

## Notes

- This is a custom component extending JMeter's ConfigTestElement
- Excel data is read on the first iteration and cached in memory for the rest of the test
- When `variableNames` is empty and `ignoreFirstLine` is false, the first row is treated as column headers
- When `variableNames` is empty and `ignoreFirstLine` is true, the header row is read from the Excel file and used as variable names (data starts from row 2)
- If EOF is reached with `recycle=false` and `stopThread=false`, all variables are set to `<EOF>`
- The file pointer index is cleared when the test ends
