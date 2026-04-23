# XML Assertion

## Description

The XML Assertion tests that the response data consists of a formally correct XML document. It does not validate the XML based on a DTD or schema or do any further validation.

## Parameters

This component has no configurable parameters beyond the element name.

| Property | Required | Default | Description | Example |
|----------|----------|---------|-------------|---------|
| *(none)* | — | — | XML Assertion has no additional parameters; it simply checks that the response is well-formed XML. | — |

## Usage Examples

### Example 1: Validate XML Response

```
create_jmeter_element with:
- elementType: "xmlassertion"
- elementName: "断言_XML格式正确"
```

### Example 2: Validate SOAP Response

```
create_jmeter_element with:
- elementType: "xmlassertion"
- elementName: "断言_SOAP响应为有效XML"
```

## Best Practices

1. **Place after XML-returning samplers**: XML Assertion is useful for SOAP/XML-RPC requests or any sampler returning XML.
2. **Use alongside XPath Assertion**: Combine with XPath Assertion for both well-formedness and content validation.
3. **Lightweight validation**: Use this for a quick structural check before applying more specific assertions.

## Notes

- The assertion only checks that the response is well-formed XML; it does not validate against DTD or schema.
- If the response is not valid XML, the assertion will fail and mark the sample as failed.
- This assertion has no configurable parameters.
