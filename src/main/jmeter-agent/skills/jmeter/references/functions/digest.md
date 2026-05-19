# __digest

## Function Name
`__digest`

## Category
Calculation

## Description
The digest function returns an encrypted value in the specific hash algorithm with the optional salt, upper case and variable name.

## Parameters

| Parameter | Description | Required | Default |
|-----------|-------------|----------|---------|
| Algorithm | The algorithm to be used to encrypt. For possible algorithms see MessageDigest in [StandardNames](https://docs.oracle.com/javase/8/docs/technotes/guides/security/StandardNames.html). Supported algorithms include: MD2, MD5, SHA-1, SHA-224, SHA-256, SHA-384, SHA-512. | Yes | - |
| String to encode | The String that will be encrypted. | Yes | - |
| Salt to add | Salt to be added to string (after it). Spaces are taken into account. | No | - |
| Upper Case value | Result will be in lower case by default. Choose `true` to upper case results. | No | `false` |
| Name of variable | The name of the variable to set. | No | - |

## Usage Examples

### Basic Usage
```
${__digest(MD5,Errare humanum est,,,)}
```
Returns `c49f00b92667a35c63708933384dad52`.

### With Salt
```
${__digest(SHA-256,Felix qui potuit rerum cognoscere causas,mysalt,,)}
```
Returns `a3bc6900fe2b2fc5fa8a601a4a84e27a079bf2c581d485009bc5c00516729ac7`.

### Upper Case Result
```
${__digest(MD5,Errare humanum est,,true,)}
```
Returns the MD5 hash in upper case.

### With Variable Name
```
${__digest(SHA-256,my password,mysalt,true,MYVAR)}
```
Returns the SHA-256 hash of "my password" with salt "mysalt" in upper case, and stores the result in `MYVAR`.

## Notes
- Spaces are taken into account for the Salt to add and String to encode parameters.
- Result is in lower case by default; use `true` for the Upper Case value parameter to get upper case output.
- For a full list of supported algorithms, see the [Java StandardNames documentation](https://docs.oracle.com/javase/8/docs/technotes/guides/security/StandardNames.html).

## Since
4.0

## Reference
- [Apache JMeter - __digest](https://jmeter.apache.org/usermanual/functions.html#__digest)
