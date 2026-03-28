# Tool Usage Notes

JMeter AI tools help you interact with JMeter test plans. This file documents important usage patterns.

## JMeter Tools

### get_jmeter_element
- Retrieves information about the currently selected JMeter element
- No parameters required - uses current selection from JMeter tree

### optimize_jmeter_element
- Analyzes and suggests optimizations for the selected element
- Provides specific recommendations for performance improvements

### lint_jmeter_elements
- Renames test plan elements with descriptive names
- Supports undo/redo functionality
- Helps organize complex test plans

### wrap_samplers
- Groups HTTP request samplers under Transaction Controllers
- Automatically detects sequential HTTP requests
- Creates logical groupings for better reporting

## File System Tools (if enabled)

- **read_file** - Read file contents
- **write_file** - Write new file content
- **edit_file** - Edit existing file
- **list_dir** - List directory contents

## Safety Notes

- Tools that modify JMeter test plans will update the currently open test plan
- Changes are applied to the JMeter GUI tree structure
- Always review suggested changes before applying
