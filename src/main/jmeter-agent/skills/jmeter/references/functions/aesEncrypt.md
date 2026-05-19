# __aesEncrypt

## Function Name
`__aesEncrypt`

## Category
Encryption

## Description
Performs AES encryption using AES/CBC/PKCS5Padding algorithm with BouncyCastle as the security provider. The function encrypts the given plaintext and returns a Base64-encoded ciphertext string.

## Parameters

| Parameter | Description | Required | Default |
|-----------|-------------|----------|---------|
| Plain text | The string to be encrypted | Yes | -- |
| Key | AES key, must be 16, 24, or 32 bytes | No | `ABCDEFGHIJKL_key` (16 bytes) |
| IV | Initialization vector, must be 16 bytes | No | `ABCDEFGHIJKLM_iv` (16 bytes) |
| Variable name | Name of the JMeter variable to store the encrypted result | No | -- |

## Usage Examples

### Basic Usage (with default key and IV)
```
${__aesEncrypt(hello world)}
```
Encrypts "hello world" using the default key and IV, returns Base64-encoded ciphertext.

### With Custom Key and IV
```
${__aesEncrypt(${password},mySecretKey12345,myIVvector1234567)}
```
Encrypts the value of `${password}` using the specified key and IV.

### With Variable Storage
```
${__aesEncrypt(${data},0123456789abcdef,abcdef0123456789,encryptedData)}
```
Stores the encrypted result in the variable `encryptedData`.

## Notes
- Algorithm: AES/CBC/PKCS5Padding
- Key sizes: 16 bytes (AES-128), 24 bytes (AES-192), or 32 bytes (AES-256)
- If key is empty, defaults to `ABCDEFGHIJKL_key`
- If IV is empty, defaults to `ABCDEFGHIJKLM_iv`
- Returns `null` if the key length is invalid or IV is not 16 bytes

## Since
Custom (Gitee extension)

## Reference
- Source: `com.gitee.qa.jmeter.functions.AESEncrypt`
