# JMeter Script Development Standards

This document defines standard specifications and best practices for writing high-quality JMeter test scripts.

## 1. Naming Conventions

### 1.1 Component Naming Principles

All components MUST use **clear, meaningful, descriptive** names. Avoid default names.

| Component Type | Bad Naming | Good Naming |
|----------------|------------|-------------|
| Thread Group | `线程组` | `用户登录场景-100并发` |
| HTTP Request | `HTTP请求` | `POST_用户登录` |
| JSR223 Sampler | `JSR223 Sampler` | `提取Token并存入变量` |
| CSV Data Set Config | `CSV Data Set Config` | `读取用户数据` |
| JSON Extractor | `JSON Extractor` | `提取_响应Token` |
| Response Assertion | `Response Assertion` | `断言_状态码为200` |

### 1.2 Naming Format Recommendations

- **Action prefix**: `GET_`, `POST_`, `PUT_`, `DELETE_`
- **Functional description**: Brief description of component purpose
- **Parameter identifier**: Include key parameters if needed

**Examples:**
```
POST_创建订单
GET_查询订单详情_${orderId}
提取响应JSON中的userId
断言_响应状态码为200
断言_包含success字段
```

## 2. Script Language Selection

### 2.1 Prioritize JSR223 + Groovy

For scripting scenarios, **MUST** use JSR223 elements with **Groovy** language.

**Comparison:**

| Feature | JSR223 + Groovy | BeanShell |
|---------|-----------------|-----------|
| Performance | Fast (script compiled and cached) | Slow (interpreted each time) |
| Thread Safety | Safe | Security risks exist |
| Syntax | Modern Java-like syntax | Outdated syntax |
| Maintenance | Community active recommendation | Being phased out |

### 2.2 JSR223 Element Selection

| Element | Purpose |
|---------|---------|
| JSR223 Sampler | Write request logic, data processing |
| JSR223 PreProcessor | Pre-request processing (parameter calculation, signing) |
| JSR223 PostProcessor | Post-response processing (data extraction, assertion) |
| JSR223 Listener | Custom result collection |

### 2.3 Groovy Script Examples

```groovy
// JSR223 PreProcessor - Generate timestamp
import java.time.Instant

long timestamp = Instant.now().toEpochMilli()
vars.put("timestamp", timestamp.toString())

// JSR223 PostProcessor - Extract JSON data
import groovy.json.JsonSlurper

def response = new JsonSlurper().parseText(prev.getResponseDataAsString())
vars.put("userId", response.data.user.id.toString())
```

## 3. Module Reuse

### 3.1 Use Test Fragment for Repeated Modules

For business flows or logic modules **repeatedly used** across multiple test plans, encapsulate as Test Fragment.

**Applicable scenarios:**
- User login/logout flows
- Common request header settings
- Public parameter extraction logic
- Reusable business operation combinations

### 3.2 Test Fragment + Module Controller Pattern

```
Test Plan
├── Test Fragment: Login Module
│   ├── HTTP Request: Login API
│   ├── JSON Extractor: Extract Token
│   └── Cookie Manager
├── Thread Group: Business Scenario A
│   ├── Module Controller: Reference Login Module
│   └── HTTP Request: Business API A
└── Thread Group: Business Scenario B
    ├── Module Controller: Reference Login Module
    └── HTTP Request: Business API B
```

**Advantages:**
- Avoid duplicate configuration
- Unified maintenance, one change affects all
- Improve script readability and maintainability

## 4. Other Best Practices

### 4.1 Parameterization

- Use **CSV Data Set Config** for data parameterization
- Variable names use lowercase with underscores: `${user_name}`, `${order_id}`
- Place CSV files in `data/` directory

### 4.2 Assertions

- Add assertions to all key requests
- Prioritize **JSON Assertion** or **Response Assertion**
- Use clear assertion names: `断言_状态码200`, `断言_包含success字段`

### 4.3 Correlation

- Use **JSON Extractor** or **Regular Expression Extractor** to extract dynamic data
- Use clear extractor names: `提取_订单ID`, `提取_Token`
- Set appropriate variable scope (main thread/sub-thread)

### 4.4 Think Time

- Simulate real user behavior, add appropriate think time
- Use **Flow Control Action** or **Uniform Random Timer**

### 4.5 Result Collection

- Disable graphical result listeners during production load testing
- Keep only necessary **Summary Report** or **Aggregate Report**
- Use `-l` parameter to output result files, execute in non-GUI mode
