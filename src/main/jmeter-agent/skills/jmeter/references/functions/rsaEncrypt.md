# __rsaEncrypt

## Function Name
`__rsaEncrypt`

## Category
Encryption

## Description
Performs RSA public key encryption using an X.509 encoded public key. The function encrypts the given plaintext with the provided RSA public key and returns a Base64-encoded ciphertext string.

## Parameters

| Parameter | Description | Required | Default |
|-----------|-------------|----------|---------|
| Plain text | The string to be encrypted | Yes | -- |
| Public key | Base64-encoded RSA public key (X.509/SubjectPublicKeyInfo DER format) | Yes | -- |
| Variable name | Name of the JMeter variable to store the encrypted result | No | -- |

## Usage Examples

### Basic Usage
```
${__rsaEncrypt(${password},${rsaPublicKey})}
```
Encrypts the value of `${password}` using the provided RSA public key.

### With Variable Storage
```
${__rsaEncrypt(hello world,MIIBIjANBgkqhkiG9w0BAQE...,encryptedData)}
```
Stores the encrypted result in the variable `encryptedData`.

### Read Key from File
```
${__rsaEncrypt(${data},${__FileToString(${__P(rsa.key.path)})})}
```
Reads the RSA public key from a file and encrypts the data.

## Notes
- The public key must be in X.509 SubjectPublicKeyInfo DER format, Base64-encoded
- Returns `null` if the public key is empty or invalid
- Uses platform default charset for plaintext byte conversion

## Since
Custom (Gitee extension)

## Reference
- Source: `com.gitee.qa.jmeter.functions.RSAEncrypt`
