# HTTP User Defined Include Configuration
> **Source**: Gitee QA extension (third-party plugin `com.gitee.qa.jmeter`, requires the corresponding plugin)

## Description

This configuration element loads HTTP User Defined Element Configuration definitions from an external JMX file. It is used to share reusable HTTP request templates across multiple test plans.

When placed in a test plan, it reads the specified JMX file and extracts any HTTP User Defined Element Configuration components, making them available for HTTP User Defined Samplers in the current test plan.

**Key features:**
- Loads external JMX files containing HTTPUD config definitions
- Caches loaded files (tracks file name, size, and timestamp to avoid reloading unchanged files)
- Filters out disabled elements from the included tree
- Supports the `includecontroller.prefix` JMeter property for path prefixing

## Parameters

| Property | Required | Default | Description | Example |
|----------|----------|---------|-------------|---------|
| `HTTPUDIncludeConfig.includepath` | Yes | `""` | Path to the external JMX file containing HTTPUD config definitions. Can be absolute or relative to the test plan directory. | `"httpud-templates/api-templates.jmx"` |

## Usage Examples

### Example 1: Include API Templates

```
create_jmeter_element with:
- elementType: "httpudincludeconfig"
- elementName: "包含API模板定义"
- properties:
  - HTTPUDIncludeConfig.includepath: "httpud-templates/api-templates.jmx"
```

### Example 2: Include with Absolute Path

```
create_jmeter_element with:
- elementType: "httpudincludeconfig"
- elementName: "包含公共接口定义"
- properties:
  - HTTPUDIncludeConfig.includepath: "C:/jmeter/templates/common-httpud.jmx"
```

### Example 3: Include Multiple Template Files

```
create_jmeter_element with:
- elementType: "httpudincludeconfig"
- elementName: "包含用户API模板"
- properties:
  - HTTPUDIncludeConfig.includepath: "httpud-templates/user-api.jmx"

create_jmeter_element with:
- elementType: "httpudincludeconfig"
- elementName: "包含订单API模板"
- properties:
  - HTTPUDIncludeConfig.includepath: "httpud-templates/order-api.jmx"
```

## Best Practices

1. **Use relative paths**: Prefer relative paths for portability across environments
2. **Organize templates by service**: Create separate JMX files per service or domain (e.g., `user-api.jmx`, `order-api.jmx`)
3. **Version control template files**: Keep HTTPUD template JMX files under version control alongside test plans
4. **Test included templates independently**: Verify that included JMX files contain valid HTTPUD configurations
5. **Place at Test Plan level**: Add this configuration element at the Test Plan level for global availability

## Notes

- The included JMX file should contain HTTP User Defined Element Configuration components
- The element caches loaded files and only reloads when the file changes (name, size, or timestamp)
- Disabled elements in the included file are filtered out
- The `includecontroller.prefix` JMeter property can be used to prefix the file path
- File paths do not support JMeter variables or functions
