# __changeCase

## Function Name
`__changeCase`

## Category
String

## Description
The change case function returns a string value which case has been changed following a specific mode. Result can optionally be saved in a JMeter variable.

## Parameters

| Parameter | Description | Required | Default |
|-----------|-------------|----------|---------|
| String to change case | The String which case will be changed. | Yes | - |
| Change case mode | The mode to be used to change case, for example for `ab-CD eF`:<ul><li>`UPPER` - result as `AB-CD EF`</li><li>`LOWER` - result as `ab-cd ed`</li><li>`CAPITALIZE` - result as `Ab-CD eF`</li></ul>The `change case mode` is case insensitive. If no mode is given, `UPPER` is used as default. | No | UPPER |
| Name of variable | The name of the variable to set. | No | - |

## Usage Examples

### Upper Case
```
${__changeCase(Avaro omnia desunt\, inopi pauca\, sapienti nihil,UPPER,)}
```
Returns `AVARO OMNIA DESUNT, INOPI PAUCA, SAPIENTI NIHIL`

### Lower Case
```
${__changeCase(LABOR OMNIA VINCIT IMPROBUS,LOWER,)}
```
Returns `labor omnia vincit improbus`

### Capitalize
```
${__changeCase(omnibus viis romam pervenitur,CAPITALIZE,)}
```
Returns `Omnibus viis romam pervenitur`

## Notes
- The change case mode parameter is case insensitive.
- If no mode is specified, `UPPER` is used as the default.
- The result can optionally be stored in a JMeter variable by providing the third parameter.

## Since
4.0

## Reference
- [Apache JMeter - __changeCase](https://jmeter.apache.org/usermanual/functions.html#__changeCase)
