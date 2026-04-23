# ForEach Controller

## Description

A ForEach controller loops through the values of a set of related variables. When you add samplers (or controllers) to a ForEach controller, every sample (or controller) is executed one or more times, where during every loop the variable has a new value. The input should consist of several variables, each extended with an underscore and a number. Each such variable must have a value.

So for example when the input variable has the name `inputVar`, the following variables should have been defined:

- `inputVar_1 = wendy`
- `inputVar_2 = charles`
- `inputVar_3 = peter`
- `inputVar_4 = john`

When the return variable is given as `returnVar`, the collection of samplers and controllers under the ForEach controller will be executed 4 consecutive times, with the return variable having the respective above values, which can then be used in the samplers.

It is especially suited for running with the regular expression post-processor. This can "create" the necessary input variables out of the result data of a previous request.

JMeter will expose the looping index as a variable named `__jm__<Name of your element>__idx`. So for example, if your Loop Controller is named FEC, then you can access the looping index through `${__jm__FEC__idx}`. Index starts at 0.

## Parameters

| Property | Required | Default | Description | Example |
|----------|----------|---------|-------------|---------|
| `ForeachController.inputVal` | Yes | — | Prefix for the variable names to be used as input. Defaults to an empty string as prefix. | `inputVar` |
| `ForeachController.returnVal` | Yes | — | Name of the variable to store the current value. For each iteration, the value from the current input variable is stored in this variable. | `returnVar` |
| `ForeachController.startIndex` | No | `""` | Start index (exclusive) for loop over variables (first element is at start index + 1) | `0` |
| `ForeachController.endIndex` | No | `""` | The ending index for the loop. Leave empty to iterate through all available variables. The loop stops when a variable with the current index cannot be found. | `10` |
| `ForeachController.useSeparator` | No | `true` | If true, uses "_" separator between variable name and index (e.g., var_1). If false, no separator is used (e.g., var1). | `true` |

## Usage Examples

### Example 1: Basic ForEach Loop

```
create_jmeter_element with:
- elementType: "foreachcontroller"
- elementName: "遍历用户变量"
- properties:
  - ForeachController.inputVal: "username"
  - ForeachController.returnVal: "currentUser"
  - ForeachController.useSeparator: "true"
```

### Example 2: ForEach with Regex Extractor Results

```
create_jmeter_element with:
- elementType: "foreachcontroller"
- elementName: "遍历提取的链接"
- properties:
  - ForeachController.inputVal: "link"
  - ForeachController.returnVal: "currentLink"
  - ForeachController.startIndex: ""
  - ForeachController.endIndex: ""
  - ForeachController.useSeparator: "true"
```

### Example 3: ForEach Without Separator

```
create_jmeter_element with:
- elementType: "foreachcontroller"
- elementName: "遍历正则分组"
- properties:
  - ForeachController.inputVal: "match_g"
  - ForeachController.returnVal: "currentGroup"
  - ForeachController.useSeparator: "false"
```

### Example 4: ForEach with Index Range

```
create_jmeter_element with:
- elementType: "foreachcontroller"
- elementName: "遍历指定范围"
- properties:
  - ForeachController.inputVal: "item"
  - ForeachController.returnVal: "currentItem"
  - ForeachController.startIndex: "0"
  - ForeachController.endIndex: "5"
  - ForeachController.useSeparator: "true"
```

## Best Practices

1. **Use with Regex Extractor**: Pair with Regular Expression Extractor to create input variables from response data
2. **Set meaningful return variable**: Use descriptive names like `currentUser`, `currentLink` for the output variable
3. **Check variable existence**: The ForEach Controller does not run any samples if `inputVar_1` is null
4. **Use separator appropriately**: Enable separator for standard variables (var_1, var_2), disable for group references (varg1, varg2)
5. **Omit index range**: Leave start and end index empty to automatically iterate through all matching variables

## Notes

- The "_" separator is now optional; control it with `ForeachController.useSeparator`
- The ForEach Controller does not run any samples if `inputVar_1` is null (e.g., when Regular Expression returned no matches)
- By omitting the "_" separator, the ForEach Controller can loop through groups using input variable `refName_g`, or through all groups in all matches using `refName_${C}_g` where `C` is a counter variable
- JMeter exposes the looping index as `${__jm__<Name of your element>__idx}` starting at 0
