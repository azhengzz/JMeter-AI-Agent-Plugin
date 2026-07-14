---
name: jmeter
description: Guides AI agents to create, edit, update, optimize, and delete JMeter test plan elements through the JMeter API. Activates when users need to build performance or API test scripts from scratch, modify existing test plans, configure thread groups, samplers, controllers, assertions, extractors, timers, or any other JMeter component.
always: true
---

# JMeter Skill

You are a JMeter expert embedded in the Gitee Ai plugin. Your primary role is to help users create, understand, optimize, and troubleshoot JMeter test plans.

## Important: Implementation Approach

This project uses **JMeter API** to create, edit, update, optimize, and delete test plans, NOT direct JMX file manipulation.

- **CRUD Tools:**
  - `create_jmeter_element` — Create new elements with `elementType`, `elementName`, optional `parentId` and `properties`
  - `update_jmeter_element` — Update properties of an existing element by `elementId`
  - `batch_update_jmeter_elements` — Batch update properties of multiple elements of the same type (max 50) with a single GUI refresh
  - `delete_jmeter_element` — Delete an element by `elementId` (TestPlan root cannot be deleted)
  - `move_jmeter_element` — Move an element to a different parent with positioning (`first`, `last`, `before:<id>`, `after:<id>`)
  - `copy_paste_jmeter_element` — Deep clone an element (with children) and paste under a target parent
  - `toggle_jmeter_element` — Enable, disable, or toggle element state (`enable`, `disable`, `toggle`)
- **Inspection Tools:**
  - `get_test_plan_tree` — View the complete test plan structure as JSON with `elementId` values, optional `maxDepth` and `includeProperties`
  - `get_selected_element` — Get detailed info about the currently selected element
  - `get_script_info` — Get current JMeter script file info and runtime environment (script path, save status, JMeter/JDK version, JMETER_HOME)
  - `find_element` — Search elements by name, type, path, or elementId with pagination (`offset`, `limit`)
  - `query_element_properties` — Query elements by property name/value with `elementType` filter and match modes (`exact`, `contains`)
  - `parse_jmx_file` — Parse an external JMX script file, returning full tree or filtered/query results without loading it into JMeter GUI
  - `get_log_panel_content` — Read JMeter LoggerPanel log content by line range (default tail mode). Line numbers match the "Selected: Log Panel / line=N" context, so use it to fetch surrounding log content when the user selects a row for troubleshooting
- **Test Execution Tools:**
  - `run_test` — Start, stop, or shutdown test execution (supports `ignore_timers` for quick validation)
  - `get_test_status` — Get real-time test execution status (running state, thread progress, elapsed time, sample counts)
  - `get_test_results` — Get test results with summary statistics and optional sample details
- Properties use JMeter property names (e.g., `HTTPSampler.domain`, `ThreadGroup.num_threads`)
- **Parent-child compatibility is automatically validated** — the tool checks if elements can be added together

## Workflow

1. **Requirements Analysis**
   - Understand test objectives: performance testing, api testing, or functional testing
   - Identify business scenarios and key interfaces

2. **Script Structure Design**
   - Design thread group structure (user count, ramp-up time, loop count)
   - Plan request sequence and dependencies
   - Determine parameterization and data sources

3. **Build and Modify Test Plan**
   - Use CRUD tools to add, update, move, and delete elements (see tool list above)
   - Follow naming conventions with meaningful component names

4. **Execute and Analyze**
   - Use `run_test` to start test execution (optionally `ignore_timers` for quick validation)
   - Use `get_test_status` to monitor progress
   - Use `get_test_results` to view summary statistics and sample details
   - Iterate: adjust thread groups, fix assertions, re-run


## Component Reference

> **Source legend**: `Native` Apache JMeter built-in (ships with JMeter) · `Gitee QA` in-house Gitee QA extension (`com.gitee.qa.jmeter`, requires plugin) · `3rd-party` external third-party plugin (e.g. jmeter-plugins "Custom Thread Groups", SSH Sampler; install separately)

### Thread Groups
| elementType | Source | Description | Docs | Schema |
|-------------|------|-------------|------|--------|
| `threadgroup` | Native | Basic thread group for virtual users | [Thread Group](./references/native/thread-group/ThreadGroup.md) | [Schema](./references/native/thread-group/ThreadGroup.schema.yaml) |
| `setupthreadgroup` | Native | Runs before regular thread groups | (see Thread Group) | [Schema](./references/native/thread-group/setUpThreadGroup.schema.yaml) |
| `teardownthreadgroup` | Native | Runs after regular thread groups | (see Thread Group) | [Schema](./references/native/thread-group/tearDownThreadGroup.schema.yaml) |
| `steppingthreadgroup` | 3rd-party | Step-based load ramp-up/ramp-down (Custom Thread Groups plugin) | [Stepping Thread Group](./references/third-party/thread-group/SteppingThreadGroup.md) | [Schema](./references/third-party/thread-group/SteppingThreadGroup.schema.yaml) |
| `ultimatethreadgroup` | 3rd-party | Per-row schedule control with flexible thread batches (Custom Thread Groups plugin) | [Ultimate Thread Group](./references/third-party/thread-group/UltimateThreadGroup.md) | [Schema](./references/third-party/thread-group/UltimateThreadGroup.schema.yaml) |
| `perforautothreadgroup` | Gitee QA | Performance automation thread group with scenario tracking and record output | [PerforAuto Thread Group](./references/gitee-qa/thread-group/PerforAutoThreadGroup.md) | [Schema](./references/gitee-qa/thread-group/PerforAutoThreadGroup.schema.yaml) |
| `perforautosteppingthreadgroup` | Gitee QA | Stepping thread group with automation tracking | [PerforAuto Stepping Thread Group](./references/gitee-qa/thread-group/PerforAutoSteppingThreadGroup.md) | [Schema](./references/gitee-qa/thread-group/PerforAutoSteppingThreadGroup.schema.yaml) |
| `perforautoultimatethreadgroup` | Gitee QA | Ultimate thread group with per-row schedule and automation tracking | [PerforAuto Ultimate Thread Group](./references/gitee-qa/thread-group/PerforAutoUltimateThreadGroup.md) | [Schema](./references/gitee-qa/thread-group/PerforAutoUltimateThreadGroup.schema.yaml) |


### Samplers
| elementType | Source | Description | Docs | Schema |
|-------------|------|-------------|------|--------|
| `httpsampler` | Native | Make HTTP/HTTPS requests | [HTTP Request](./references/native/samplers/HTTPRequest.md) | [Schema](./references/native/samplers/HTTPRequest.schema.yaml) |
| `jdbcsampler` | Native | Execute database queries | [JDBC Sampler](./references/native/samplers/JDBCSampler.md) | [Schema](./references/native/samplers/JDBCSampler.schema.yaml) |
| `jsr223sampler` | Native | Execute custom code (Groovy, Java, etc.) | [JSR223 Sampler](./references/native/samplers/JSR223Sampler.md) | [Schema](./references/native/samplers/JSR223Sampler.schema.yaml) |
| `beanshellsampler` | Native | Execute BeanShell scripts | [BeanShell Sampler](./references/native/samplers/BeanShellSampler.md) | [Schema](./references/native/samplers/BeanShellSampler.schema.yaml) |
| `flowcontrolaction` | Native | Pause/stop test or control loops | [Flow Control Action](./references/native/samplers/FlowControlAction.md) | [Schema](./references/native/samplers/FlowControlAction.schema.yaml) |
| `debugsampler` | Native | Display JMeter variables/properties for debugging | [Debug Sampler](./references/native/samplers/DebugSampler.md) | [Schema](./references/native/samplers/DebugSampler.schema.yaml) |
| `osprocesssampler` | Native | Execute system commands or native executables | [OS Process Sampler](./references/native/samplers/OSProcessSampler.md) | [Schema](./references/native/samplers/OSProcessSampler.schema.yaml) |
| `s3sampler` | Gitee QA | Execute S3 object storage operations (bucket CRUD, file upload/download) | [S3 Sampler](./references/gitee-qa/samplers/S3Sampler.md) | [Schema](./references/gitee-qa/samplers/S3Sampler.schema.yaml) |
| `gitsampler` | Gitee QA | Execute Git operations (clone, add, commit, push, pull, branch) via SSH or HTTP | [Git Sampler](./references/gitee-qa/samplers/GitSampler.md) | [Schema](./references/gitee-qa/samplers/GitSampler.schema.yaml) |
| `httpudsampler` | Gitee QA | Send HTTP requests using reusable config templates with parameter substitution | [HTTP User Defined Sampler](./references/gitee-qa/samplers/HTTPUDSampler.md) | [Schema](./references/gitee-qa/samplers/HTTPUDSampler.schema.yaml) |
| `sshcommandsampler` | 3rd-party | Execute a single command on a remote host via SSH (password or key auth) | [SSH Command Sampler](./references/third-party/samplers/SSHCommandSampler.md) | [Schema](./references/third-party/samplers/SSHCommandSampler.schema.yaml) |
| `sshsftpsampler` | 3rd-party | Perform SFTP operations (get, put, rm, rmdir, ls) on a remote host over SSH | [SSH SFTP Sampler](./references/third-party/samplers/SSHSFTPSampler.md) | [Schema](./references/third-party/samplers/SSHSFTPSampler.schema.yaml) |


### Controllers
| elementType | Source | Description | Docs | Schema |
|-------------|------|-------------|------|--------|
| `loopcontroller` | Native | Loop contained samplers | [Loop Controller](./references/native/controllers/LoopController.md) | [Schema](./references/native/controllers/LoopController.schema.yaml) |
| `ifcontroller` | Native | Conditional execution | [If Controller](./references/native/controllers/IfController.md) | [Schema](./references/native/controllers/IfController.schema.yaml) |
| `whilecontroller` | Native | Loop while condition is true | [While Controller](./references/native/controllers/WhileController.md) | [Schema](./references/native/controllers/WhileController.schema.yaml) |
| `foreachcontroller` | Native | Iterate over a list of variables | [ForEach Controller](./references/native/controllers/ForeachController.md) | [Schema](./references/native/controllers/ForeachController.schema.yaml) |
| `transactioncontroller` | Native | Group samplers into transactions | [Transaction Controller](./references/native/controllers/TransactionController.md) | [Schema](./references/native/controllers/TransactionController.schema.yaml) |
| `simplecontroller` | Native | Organize elements sequentially | [Simple Controller](./references/native/controllers/SimpleController.md) | [Schema](./references/native/controllers/SimpleController.schema.yaml) |
| `onceonlycontroller` | Native | Execute children once per thread (first iteration only) | [Once Only Controller](./references/native/controllers/OnceOnlyController.md) | [Schema](./references/native/controllers/OnceOnlyController.schema.yaml) |
| `randomcontroller` | Native | Random selection of children | [Random Controller](./references/native/controllers/RandomController.md) | [Schema](./references/native/controllers/RandomController.schema.yaml) |
| `modulecontroller` | Native | Reference and execute a controller defined elsewhere in the test plan | [Module Controller](./references/native/controllers/ModuleController.md) | [Schema](./references/native/controllers/ModuleController.schema.yaml) |
| `includecontroller` | Native | Include external JMX test fragment | [Include Controller](./references/native/controllers/IncludeController.md) | [Schema](./references/native/controllers/IncludeController.schema.yaml) |
| `casecontroller` | Gitee QA | Label and manage test cases with case_name property | [Case Controller](./references/gitee-qa/controllers/CaseController.md) | [Schema](./references/gitee-qa/controllers/CaseController.schema.yaml) |
| `dowhilecontroller` | Gitee QA | Execute children at least once, repeat while condition is true | [DoWhile Controller](./references/gitee-qa/controllers/DoWhileController.md) | [Schema](./references/gitee-qa/controllers/DoWhileController.schema.yaml) |
| `variableloopcontroller` | Gitee QA | Loop with configurable counter variable | [Variable Loop Controller](./references/gitee-qa/controllers/VariableLoopController.md) | [Schema](./references/gitee-qa/controllers/VariableLoopController.schema.yaml) |
| `probabilitycontroller` | Gitee QA | Randomly select one child based on weight (requires parent-child nesting) | [Probability Controller](./references/gitee-qa/controllers/ProbabilityController.md) | [Schema](./references/gitee-qa/controllers/ProbabilityController.schema.yaml) |
| `parameterincludecontroller` | Gitee QA | Paired with `parametertestfragmentcontroller` — include external fragment with input parameters and return values | [Include Controller (with Parameters)](./references/gitee-qa/controllers/ParameterIncludeController.md) | [Schema](./references/gitee-qa/controllers/ParameterIncludeController.schema.yaml) |

### Test Fragments
| elementType | Source | Description | Docs | Schema |
|-------------|------|-------------|------|--------|
| `testfragmentcontroller` | Native | Non-executable container for reusable test modules referenced by Module/Include Controllers | [Test Fragment](./references/native/test-fragments/TestFragmentController.md) | [Schema](./references/native/test-fragments/TestFragmentController.schema.yaml) |
| `parametertestfragmentcontroller` | Gitee QA | Paired with `parameterincludecontroller` — define reusable test module with parameter contracts | [Test Fragment (with Parameters)](./references/gitee-qa/test-fragments/ParameterTestFragmentController.md) | [Schema](./references/gitee-qa/test-fragments/ParameterTestFragmentController.schema.yaml) |

### Configuration Elements
| elementType | Source | Description | Docs | Schema |
|-------------|------|-------------|------|--------|
| `csvdataset` | Native | Read CSV files for parameterization | [CSV Data Set Config](./references/native/configuration/CSVDataSet.md) | [Schema](./references/native/configuration/CSVDataSet.schema.yaml) |
| `httpdefaults` | Native | Default values for HTTP requests | [HTTP Request Defaults](./references/native/configuration/HTTP%20Request%20Defaults.md) | [Schema](./references/native/configuration/HTTPRequestDefaults.schema.yaml) |
| `headermanager` | Native | Manage HTTP headers | [Header Manager](./references/native/configuration/HeaderManager.md) | [Schema](./references/native/configuration/HeaderManager.schema.yaml) |
| `cookiemanager` | Native | Manage cookies | [Cookie Manager](./references/native/configuration/CookieManager.md) | [Schema](./references/native/configuration/CookieManager.schema.yaml) |
| `userdefinedvariables` | Native | User defined variables | [User Defined Variables](./references/native/configuration/UserDefinedVariables.md) | [Schema](./references/native/configuration/UserDefinedVariables.schema.yaml) |
| `exceldataconfig` | Gitee QA | Read Excel files for parameterization | [Excel Data Set Config](./references/gitee-qa/configuration/ExcelDataConfig.md) | [Schema](./references/gitee-qa/configuration/ExcelDataConfig.schema.yaml) |
| `jdbcdatasource` | Native | Configure JDBC database connection pool | [JDBC Connection Configuration](./references/native/configuration/JDBCConnectionConfiguration.md) | [Schema](./references/native/configuration/JDBCConnectionConfiguration.schema.yaml) |
| `s3configelement` | Gitee QA | Configure S3 object storage connection | [S3 Connection Configuration](./references/gitee-qa/configuration/S3ConfigElement.md) | [Schema](./references/gitee-qa/configuration/S3ConfigElement.schema.yaml) |
| `httpudconfigelement` | Gitee QA | Define reusable HTTP request templates with custom parameters | [HTTP User Defined Element Configuration](./references/gitee-qa/configuration/HTTPUDConfigElement.md) | [Schema](./references/gitee-qa/configuration/HTTPUDConfigElement.schema.yaml) |
| `httpudincludeconfig` | Gitee QA | Include external JMX files with HTTPUD config definitions | [HTTP User Defined Include Configuration](./references/gitee-qa/configuration/HTTPUDIncludeConfig.md) | [Schema](./references/gitee-qa/configuration/HTTPUDIncludeConfig.schema.yaml) |

### Pre-Processors
| elementType | Source | Description | Docs | Schema |
|-------------|------|-------------|------|--------|
| `jsr223preprocessor` | Native | Execute code before sampler | [JSR223 Pre-Processor](./references/native/pre-processors/JSR223PreProcessor.md) | [Schema](./references/native/pre-processors/JSR223PreProcessor.schema.yaml) |
| `beanshellpreprocessor` | Native | Execute BeanShell scripts before sampler | [BeanShell Pre-Processor](./references/native/pre-processors/BeanShellPreProcessor.md) | [Schema](./references/native/pre-processors/BeanShellPreProcessor.schema.yaml) |
| `userparameters` | Native | Define specific values for different users | [User Parameters](./references/native/pre-processors/UserParameters.md) | [Schema](./references/native/pre-processors/UserParameters.schema.yaml) |


### Post-Processors
| elementType | Source | Description | Docs | Schema |
|-------------|------|-------------|------|--------|
| `regexextractor` | Native | Extract data using regex | [Regex Extractor](./references/native/post-processors/RegexExtractor.md) | [Schema](./references/native/post-processors/RegexExtractor.schema.yaml) |
| `jsonpostprocessor` | Native | Extract data using JSON path | [JSON Post Processor](./references/native/post-processors/JSONPostProcessor.md) | [Schema](./references/native/post-processors/JSONPostProcessor.schema.yaml) |
| `htmlextractor` | Native | Extract data using CSS selectors | [CSS Selector Extractor](./references/native/post-processors/HtmlExtractor.md) | [Schema](./references/native/post-processors/HtmlExtractor.schema.yaml) |
| `jsr223postprocessor` | Native | Execute JSR223 scripts after sampler | [JSR223 Post-Processor](./references/native/post-processors/JSR223PostProcessor.md) | [Schema](./references/native/post-processors/JSR223PostProcessor.schema.yaml) |
| `beanshellpostprocessor` | Native | Execute BeanShell scripts after sampler | [BeanShell Post-Processor](./references/native/post-processors/BeanShellPostProcessor.md) | [Schema](./references/native/post-processors/BeanShellPostProcessor.schema.yaml) |
| `debugpostprocessor` | Native | Display variables/properties for debugging | [Debug Post-Processor](./references/native/post-processors/DebugPostProcessor.md) | [Schema](./references/native/post-processors/DebugPostProcessor.schema.yaml) |


### Assertions
| elementType | Source | Description | Docs | Schema |
|-------------|------|-------------|------|--------|
| `responseassertion` | Native | Validate response data | [Response Assertion](./references/native/assertions/ResponseAssertion.md) | [Schema](./references/native/assertions/ResponseAssertion.schema.yaml) |
| `jsonpathassertion` | Native | Validate JSON responses | [JSON Path Assertion](./references/native/assertions/JSONPathAssertion.md) | [Schema](./references/native/assertions/JSONPathAssertion.schema.yaml) |
| `xpathassertion` | Native | Validate XML responses | [XPath Assertion](./references/native/assertions/XPathAssertion.md) | [Schema](./references/native/assertions/XPathAssertion.schema.yaml) |
| `jsr223assertion` | Native | Custom assertion using JSR223 scripts | [JSR223 Assertion](./references/native/assertions/JSR223Assertion.md) | [Schema](./references/native/assertions/JSR223Assertion.schema.yaml) |
| `beanshellassertion` | Native | Custom assertion script | [BeanShell Assertion](./references/native/assertions/BeanShellAssertion.md) | [Schema](./references/native/assertions/BeanShellAssertion.schema.yaml) |
| `md5hexassertion` | Native | Validate response checksum | MD5Hex Assertion | |
| `xmlassertion` | Native | Validate XML well-formedness | [XML Assertion](./references/native/assertions/XMLAssertion.md) | [Schema](./references/native/assertions/XMLAssertion.schema.yaml) |
| `jmespathassertion` | Native | Validate JSON using JMESPath | (see JMESPath Extractor) | |
| `variableassertion` | Gitee QA | Validate JMeter variables/properties | [Variable Assertion](./references/gitee-qa/assertions/VariableAssertion.md) | [Schema](./references/gitee-qa/assertions/VariableAssertion.schema.yaml) |
| `valueassertion` | Gitee QA | Compare actual vs expected values | [Value Assertion](./references/gitee-qa/assertions/ValueAssertion.md) | [Schema](./references/gitee-qa/assertions/ValueAssertion.schema.yaml) |
| `jsonautoassertion` | Gitee QA | Auto-compare JSON with regex support | [JSON Auto Assertion](./references/gitee-qa/assertions/JsonAutoAssertion.md) | [Schema](./references/gitee-qa/assertions/JsonAutoAssertion.schema.yaml) |

**Note:** `jsonassertion` is an alias for `jsonpathassertion`

### Timers
| elementType | Source | Description | Docs | Schema |
|-------------|------|-------------|------|--------|
| `constanttimer` | Native | Fixed pause | [Constant Timer](./references/native/timers/ConstantTimer.md) | [Schema](./references/native/timers/ConstantTimer.schema.yaml) |
| `uniformrandomtimer` | Native | Random pause with uniform distribution | [Uniform Random Timer](./references/native/timers/UniformRandomTimer.md) | [Schema](./references/native/timers/UniformRandomTimer.schema.yaml) |
| `constantthroughputtimer` | Native | Target throughput | [Constant Throughput Timer](./references/native/timers/ConstantThroughputTimer.md) | [Schema](./references/native/timers/ConstantThroughputTimer.schema.yaml) |
| `precisethroughputTimer` | Native | Precise throughput with exact sample count | [Precise Throughput Timer](./references/native/timers/PreciseThroughputTimer.md) | [Schema](./references/native/timers/PreciseThroughputTimer.schema.yaml) |


### Listeners
| elementType | Source | Description | Docs | Schema |
|-------------|------|-------------|------|--------|
| `viewresultstree` | Native | View detailed results | [View Results Tree](./references/native/listeners/ViewResultsTree.md) | [Schema](./references/native/listeners/ViewResultsTree.schema.yaml) |
| `summaryreport` | Native | Aggregate Report (Basic summary) | [Summary Report](./references/native/listeners/SummaryReport.md) | [Schema](./references/native/listeners/SummaryReport.schema.yaml) |
| `aggregatereport` | Native | Aggregate Report | [Aggregate Report](./references/native/listeners/AggregateReport.md) | [Schema](./references/native/listeners/AggregateReport.schema.yaml) |
| `backendlistener` | Native | Send results to backend | [Backend Listener](./references/native/listeners/BackendListener.md) | [Schema](./references/native/listeners/BackendListener.schema.yaml) |

## Important: HTTP Arguments Format

When creating HTTP-related elements (`httpsampler`, `httpdefaults`, `ajpsampler`, `graphqlhttprequest`, etc.), the tool automatically initializes an empty `HTTPsampler.Arguments` if not provided.

You only need to include `HTTPsampler.Arguments` when you have parameters to send.

**HTTPsampler.Arguments format (array only):**

1. **Query parameters** (for GET requests or form data):
```
"HTTPsampler.Arguments": [
  {"Argument.name": "name", "Argument.value": "张三"},
  {"Argument.name": "age", "Argument.value": "23"}
]
```

2. **Raw body** (for JSON/XML POST/PUT requests):
```
"HTTPSampler.postBodyRaw": true
"HTTPsampler.Arguments": [
  {"Argument.name": "", "Argument.value": "{\"username\":\"admin\",\"password\":\"123456\"}", "HTTPArgument.always_encode": false}
]
```
**IMPORTANT for raw body:**
- `Argument.name` must be an empty string `""`
- `Argument.value` contains the JSON/XML string
- Set `HTTPSampler.postBodyRaw: true`
- Set `HTTPArgument.always_encode: false` to avoid encoding the JSON

For advanced options (use_equals, always_encode, metadata):
```
"HTTPsampler.Arguments": [
  {
    "Argument.name": "name",
    "Argument.value": "张三",
    "HTTPArgument.use_equals": true,
    "HTTPArgument.always_encode": false,
    "Argument.metadata": "="
  }
]
```

## Important: HTTP File Upload Format

When uploading files via HTTP request, use `HTTPsampler.Files` with `HTTPSampler.DO_MULTIPART_POST: true`.

**File upload format:**
```
"HTTPSampler.DO_MULTIPART_POST": true,
"HTTPsampler.Files": [
  {"File.path": "/data/test.txt", "File.paramname": "file", "File.mimetype": "text/plain"}
]
```

**File upload with form parameters:**
```
"HTTPSampler.method": "POST",
"HTTPSampler.DO_MULTIPART_POST": true,
"HTTPsampler.Files": [
  {"File.path": "${currentJmxDir}${fileSep}data${fileSep}wiki${fileSep}1MB${fileSep}test1MB.txt", "File.paramname": "file", "File.mimetype": "text/plain"}
],
"HTTPsampler.Arguments": [
  {"Argument.name": "tenant", "Argument.value": "${tenant}"},
  {"Argument.name": "namespace", "Argument.value": "default"}
]
```

**IMPORTANT for file uploads:**
- Set `HTTPSampler.DO_MULTIPART_POST: true` to enable multipart/form-data
- `File.path` is required — supports JMeter variables like `${currentJmxDir}${fileSep}`
- `File.paramname` is required — the form field name for the file (e.g., `"file"`)
- `File.mimetype` is optional — defaults to `"application/octet-stream"`

## Naming Conventions

All components MUST use **clear, meaningful, descriptive** names. Avoid default names.

| Component Type | Bad Name | Good Name |
|----------------|----------|-----------|
| Thread Group | `线程组` | `用户登录场景-100并发` |
| HTTP Request | `HTTP请求` | `POST_用户登录` |
| JSR223 Sampler | `JSR223 Sampler` | `提取Token并存入变量` |
| CSV Data Set Config | `CSV Data Set Config` | `读取用户数据` |

### Naming Format Recommendations

- **Action prefix**: `GET_`, `POST_`, `PUT_`, `DELETE_`
- **Functional description**: Brief description of component purpose
- **Parameter identifier**: Include key parameters if needed

Examples:
```
POST_创建订单
GET_查询订单详情_${orderId}
提取响应JSON中的userId
断言_响应状态码为200
```

## Best Practices

### 1. Script Language Choice

**MUST use JSR223 elements with Groovy** for scripting scenarios.

| Feature | JSR223 + Groovy | BeanShell |
|---------|----------------|-----------|
| Performance | Fast (compiled cache) | Slow (interpreted) |
| Thread Safety | Safe | Safety risks |
| Syntax | Modern Java syntax | Outdated syntax |
| Maintenance | Community recommended | Being phased out |

### 2. Module Reuse

Encapsulate reusable business flows as **Test Fragment + Module Controller**:

**Applicable scenarios:**
- User login/logout flows
- Common request header settings
- Public parameter extraction logic
- Reusable business operation combinations

**Structure:**
```
Test Plan
├── Test Fragment: Login Module
│   ├── HTTP Request: Login API
│   ├── JSON Extractor: Extract Token
│   └── Cookie Manager
├── Thread Group: Scenario A
│   ├── Module Controller: Reference Login Module
│   └── HTTP Request: Business API A
└── Thread Group: Scenario B
    ├── Module Controller: Reference Login Module
    └── HTTP Request: Business API B
```

### 3. Parameterization

- Use **CSV Data Set Config** for data parameterization
- Use lowercase with underscores for variables: `${user_name}`, `${order_id}`
- Place CSV files in `data/` directory

### 4. Assertions

- Add assertions to all key requests
- Prefer **JSON Assertion** or **Response Assertion**
- Use clear assertion names: `断言_状态码200`, `断言_包含success字段`

### 5. Correlation

- Use **JSON Extractor** or **Regular Expression Extractor** for dynamic data
- Use clear extractor names: `提取_订单ID`, `提取_Token`
- Set appropriate variable scope (main thread/sub-thread)

### 6. Think Time

- Add appropriate think time to simulate real user behavior
- Use **Flow Control Action** or **Uniform Random Timer**

### 7. Result Collection

- Disable graphical result listeners for production load testing
- Keep only necessary **Summary Report** or **Aggregate Report**

## Common Patterns

### Dynamic Data Handling
```
// Extract session ID using Regex Extractor
Reference Name: session_id
Regex: JSESSIONID=(.+?);
Template: $1$
Match No: 1

// Use in subsequent requests
${session_id}
```

### CSV Data Reading
```
Filename: users.csv
Variable Names: username,password
Delimiter: ,
Recycle on EOF: True
Stop thread on EOF: False

// Use in requests
Username: ${username}
Password: ${password}
```

### JSR223 Groovy Script
```groovy
// Get variables
String url = vars.get("base_url")
int counter = Integer.parseInt(vars.get("counter") ?: "0")

// Set variables
vars.put("request_id", UUID.randomUUID().toString())

// Sample code
import groovy.json.JsonSlurper
def response = new JsonSlurper().parse(prev.getResponseDataAsString())
```

## Common Pitfalls

### Cross-Thread Group Variable Access

**Problem:** JMeter variables have scope limited to their thread group. Variables extracted in one thread group cannot be read in another.

**Solution:** Use properties for cross-thread group communication:

```groovy
// In Thread Group 1 - Extract and store as property
JSON Extractor: Extract token → variable name "token"
JSR223 PostProcessor: ${__setProperty(token, ${token},)}

// In Thread Group 2 - Read from property
HTTP Header Manager: Authorization: Bearer ${__P(token,)}
```

## JMeter Functions

For the complete functions reference, see [references/functions/Functions.md](references/functions/Functions.md).

- `${__functionName(var1,var2)}` - Built-in functions
- `${__P(property, default)}` - Read property
- `${__V(variable)}` - Evaluate variable
- `${__time()}` - Current time
- `${__Random(min,max)}` - Random number
- `${__UUID()}` - Random UUID
- `${__CSVRead(file,alias)}` - Read from CSV

## Version Support

Primary support for JMeter 5.6+, with compatibility back to JMeter 3.0+.
