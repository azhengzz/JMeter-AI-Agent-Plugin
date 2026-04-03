# JMeter Built-in Functions

## Overview

JMeter functions are special values that can populate fields of any Sampler or other element in a test plan. They have the syntax: `${__functionName(var1,var2)}`.

## Common Functions

### Variable and Property Functions

#### `${__V(variable)}`
Evaluate a variable name from a variable.

**Example:**
```
# If userId=123 and user_123=Alice
${__V(user_${userId})} → Returns "Alice"
```

#### `${__P(property, default)}`
Read a property (global across thread groups).

**Example:**
```
${__P(base_url,http://localhost)} → Returns property value or default
```

#### `${__setProperty(property, value, scope)}`
Set a property value.

**Example:**
```
${__setProperty(token, ${token},)} → Store variable as property
```

#### `${__property(property, default)}`
Read a property value.

**Example:**
```
${__property(base_url,)} → Returns property value
```

### Time Functions

#### `${__time(format, variable)}`
Return current time in various formats.

**Examples:**
```
${__time()} → 1699123456789 (milliseconds since epoch)
${__time(yyyy-MM-dd)} → 2024-11-04
${__time(HH:mm:ss)} → 14:30:45
${__time(,timestamp)} → Stores in variable 'timestamp'
```

#### `${__timeShift(format, date, shift, locale, variable)}`
Shift a time by a given amount.

**Examples:**
```
${__timeShift(yyyy-MM-dd,,P1D)} → Tomorrow's date
${__timeShift(yyyy-MM-dd,,P-1D)} → Yesterday's date
${__timeShift(yyyy-MM-dd HH:mm:ss,,PT2H)} → 2 hours from now
```

### Random Functions

#### `${__Random(min, max, variable)}`
Generate a random number.

**Examples:**
```
${__Random(1,100)} → Random number between 1 and 100
${__Random(0,10000,orderId)} → Stores in variable 'orderId'
```

#### `${__RandomString(length, characters, variable)}`
Generate a random string.

**Examples:**
```
${__RandomString(10)} → Random 10-character string
${__RandomString(5,abcdefghijklmnopqrstuvwxyz,userName)} → Random lowercase name
${__RandomString(8,0123456789,pinCode)} → Random 8-digit PIN
```

#### `${__UUID()}`
Generate a random UUID.

**Example:**
```
${__UUID()} → 550e8400-e29b-41d4-a716-446655440000
```

### Data Input Functions

#### `${__CSVRead(file, alias)}`
Read from CSV file.

**Example:**
```
${__CSVRead(users.csv,0)} → First column from current row
${__CSVRead(users.csv,1)} → Second column from current row
${__CSVRead(users.csv,next)} → Move to next row
```

*Note: Prefer CSV Data Set Config over this function for better performance.*

#### `${__StringFromFile(file, variable, start, end)}`
Read from a file.

**Example:**
```
${__StringFromFile(data.txt)} → Read line from file
```

### Information Functions

#### `${__threadNum()}`
Return the current thread number.

**Example:**
```
Thread-${__threadNum()} → Thread-1, Thread-2, etc.
```

#### `${__machineName()}`
Return the local machine name.

#### `${__machineIP()}`
Return the local IP address.

### Calculation Functions

#### `${__intSum(val1,val2,variable)}`
Sum integers.

**Example:**
```
${__intSum(1,2)} → 3
${__intSum(${counter},1,counter)} → Increment counter
```

#### `${__longSum(val1,val2,variable)}`
Sum long integers.

#### `${__eval(var expression)}`
Evaluate a variable expression.

**Example:**
```
# If userId=123
${__eval(user_${userId})} → Returns value of user_123
```

### Encoding Functions

#### `${__urlencode(string)}`
URL encode a string.

**Example:**
```
${__urlencode(hello world)} → hello+world
```

#### `${__urldecode(string)}`
URL decode a string.

#### `${__escapeHtml(string)}`
Escape HTML characters.

#### `${__unescapeHtml(string)}`
Unescape HTML characters.

### Script Functions

#### `${__groovy(expression, variable)}`
Evaluate Groovy expression.

**Example:**
```
${__groovy(${__time(yyyy-MM-dd)})} → Execute Groovy code
```

#### `${__javaScript(expression, variable)}`
Evaluate JavaScript expression.

## Best Practices

1. **Use CSV Data Set Config** instead of `${__CSVRead}` for better performance
2. **Cache random values** in variables if used multiple times
3. **Use properties** for cross-thread group data sharing
4. **Test functions** in View Results Tree before using in production
5. **Document custom variables** with clear names

## Function Helper

JMeter provides a Function Helper dialog (Tools → Function Helper Dialog) to:
- Browse available functions
- Test function output
- Generate function syntax
