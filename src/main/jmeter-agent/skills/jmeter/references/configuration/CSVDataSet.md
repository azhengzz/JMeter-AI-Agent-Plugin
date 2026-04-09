# CSV Data Set Config

## Description

CSV Data Set Config reads data from CSV files and splits it into variables. Each virtual user can read different rows, enabling data parameterization and realistic testing with diverse test data.

## Parameters

| Property | Required | Description | Example |
|----------|----------|-------------|---------|
| `filename` | Yes | Path to CSV file (relative or absolute) | `data/users.csv` |
| `fileEncoding` | No | File encoding format | `UTF-8` |
| `variableNames` | Yes | Comma-separated variable names | `username,password,email` |
| `delimiter` | No | Field delimiter | `,` (default) or `\t` for tab |
| `allowQuotedData` | No | Allow quoted data (with quotes) | `true` or `false` (default: `true`) |
| `recycle` | No | Rewind file when EOF is reached | `true` or `false` (default: `true`) |
| `stopThread` | No | Stop thread when EOF is reached (requires recycle=false) | `true` or `false` (default: `false`) |
| `shareMode` | No | Sharing mode between threads | `shareMode.all`, `shareMode.group`, `shareMode.thread`, `shareMode.all` (default) |
| `ignoreFirstLine` | No | Treat first line as header (skip it) | `true` or `false` (default: `false`) |

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

// Use variables in HTTP Request
create_jmeter_element with:
- elementType: "httpsampler"
- elementName: "POST_用户登录"
- properties:
  - HTTPSampler.path: "/api/login"
  - HTTPSampler.method: "POST"
  - HTTPsampler.Arguments:
    - {"Argument.name": "", "Argument.value": "{\"username\":\"${username}\",\"password\":\"${password}\"}", "HTTPArgument.always_encode": false}
  - HTTPSampler.postBodyRaw: "true"
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

## Share Modes

| Mode | Description |
|------|-------------|
| `shareMode.all` | All threads share the same file (global cursor) |
| `shareMode.group` | Threads in same thread group share the file |
| `shareMode.thread` | Each thread has its own independent cursor |
| `shareMode.all` | Default: all threads share |

## CSV File Format

### Simple CSV
```
username,password,email
user1,pass123,user1@example.com
user2,pass456,user2@example.com
```

### With Quotes
```
name,description,address
"Product A","A great product","123 Main St, New York"
"Product B","Another product","456 Oak Ave, Los Angeles"
```

## Best Practices

1. **File location**: Place CSV files in `data/` directory
2. **Encoding**: Use UTF-8 encoding for international characters
3. **Recycle vs Stop**: Use `recycle: false` with `stopThread: true` for one-time use
4. **Share mode**: Choose appropriate sharing based on test requirements
5. **Header row**: Use `ignoreFirstLine: true` when file has headers

## Common Patterns

### Pattern 1: Login with Multiple Users
```
// Each thread gets unique credentials
CSV: recycle=false, shareMode=thread
Result: Each user logs in with different account
```

### Pattern 2: Product Catalog
```
// All threads cycle through same products
CSV: recycle=true, shareMode=all
Result: Random access to product catalog
```

### Pattern 3: Sequential Test Data
```
// Each thread processes data sequentially
CSV: recycle=false, stopThread=true, shareMode=thread
Result: Each thread processes unique set of data
```

## Tips

1. **Use Debug Sampler**: Verify variable values with Debug Sampler
2. **Absolute paths**: For relative paths fail, use absolute paths
3. **Empty lines**: Skip empty lines in CSV file
4. **Special characters**: Use quotes for fields with commas
5. **Performance**: Large files may impact startup time

## Example: Debugging Variables

```
create_jmeter_element with:
- elementType: "debugsampler"
- elementName: "调试变量"
- properties:
  - displayJMeterProperties: "false"
  - displayJMeterVariables: "true"
  - displaySystemProperties: "false"
```

## Notes

- Variables are available to all samplers after the CSV Data Set Config
- Each thread reads independently unless shareMode is set
- EOF handling depends on recycle and stopThread settings
- Use CSVRead function for more control over file reading
- Variable names are case-sensitive
