# JMeter 组件 Schema 编写指南

本指南说明 `references/**/*.schema.yaml` 文件中各字段的定义、可选值与编写规则。

**组件元信息(testClass/guiClass)已全部下沉为数据**,新增/补全组件**零 Java 改动**,只需写 YAML:

| 场景 | YAML 改动 | Java 改动 |
|------|----------|----------|
| 需要 AI 能**创建并配置**该组件(有属性 schema) | ✅ 写 `.schema.yaml`(含 `testClass`/`guiClass` + `properties`) | ❌ 不需要 |
| 仅需组件在 registry 里**可被识别**(用于兼容性反查,不参与 AI 创建) | ✅ 在 [`legacy-elements.yaml`](./legacy-elements.yaml) 加一行 | ❌ 不需要 |

判断方法:在 [`references/`](./references/) 下找有没有该组件的 `.schema.yaml`,或在 [`legacy-elements.yaml`](./legacy-elements.yaml) 里搜 elementType。两者都没有 → 按需选上面一种。详见 [§13 Checklist](#13-新增组件-checklist)。

## 目录

- [1. Quick Start:5 分钟加一个最简组件](#1-quick-start5-分钟加一个最简组件)
- [2. 前置概念](#2-前置概念)
- [3. 如何发现组件的属性](#3-如何发现组件的属性)
- [4. 文件总体结构](#4-文件总体结构)
- [5. component 段(组件元信息)](#5-component-段组件元信息)
- [6. properties 段(顶层属性)](#6-properties-段顶层属性)
- [7. 子字段(itemProperties / properties 内)](#7-子字段itemproperties--properties-内)
- [8. type 枚举值](#8-type-枚举值)
- [9. mountMode 枚举值](#9-mountmode-枚举值)
- [10. setterOverride 与命名推导规则](#10-setteroverride-与命名推导规则)
- [11. 模板决策树](#11-模板决策树)
- [12. 7 个模板的完整示例](#12-7-个模板的完整示例)
- [13. 新增组件 Checklist](#13-新增组件-checklist)
- [14. 测试与排错](#14-测试与排错)
- [15. 常见陷阱](#15-常见陷阱)

---

## 1. Quick Start:5 分钟加一个最简组件

如果你的组件**只有简单标量属性**(字符串、整数、布尔),没有表格/嵌套,直接照抄下面骨架:

```yaml
component:
  type: debugsampler          # 小写 elementType,作为 registry 主索引
  name: Debug Sampler         # 显示名(同时作为默认显示名)
  description: Samples JMeter variables, JMeter properties and System Properties for debugging purposes
  testClass: org.apache.jmeter.sampler.DebugSampler        # 必填:model 类全限定名
  guiClass: org.apache.jmeter.testbeans.gui.TestBeanGUI    # 必填:GUI 类全限定名

properties:
  - name: displayJMeterProperties
    type: Boolean
    required: false
    default: true
    description: Whether to display JMeter properties
  - name: displayJMeterVariables
    type: Boolean
    required: false
    default: true
    description: Whether to display JMeter variables
```

**操作步骤**:

1. 在 `references/{source}/{category}/` 下新建 `{ComponentName}.schema.yaml` —— `{source}` 按来源选 `native`(Apache JMeter 原生) / `gitee-qa`(Gitee QA 扩展) / `third-party`(外部第三方插件);`{category}` 是 `controllers` / `samplers` / `assertions` / `thread-group` / `timers` / `configuration` / `pre-processors` / `post-processors` / `listeners` / `test-fragments` 之一(见 [§4](#4-文件总体结构))
2. 把骨架里的 `type` / `name` / `description` 改成你的组件
3. 把 `properties` 改成你组件的实际属性 —— 属性怎么发现见 [§3](#3-如何发现组件的属性)
4. 属性里有表格/嵌套对象/数组时需要选模板,见 [§11 决策树](#11-模板决策树)
5. 填了 `testClass`/`guiClass` 的 schema 会自动注册组件,**无需改任何 Java**;若组件没有属性 schema、只需 registry 识别,改投 `legacy-elements.yaml`(见 [§13](#13-新增组件-checklist))

最简情况下只用到 `String` / `Integer` / `Boolean` 三种 type,不需要 `mountMode` / `testClass` / `itemProperties` 等高级字段。

---

## 2. 前置概念

后续字段表反复出现以下术语,先讲清楚。

### 2.1 TestElement

JMeter 里几乎所有可序列化、可保存到 `.jmx` 的组件都实现 `TestElement` 接口 —— Sampler、Controller、Assertion、PreProcessor、ConfigElement、Listener 等都是。Schema 里 `testClass` / `itemClass` 字段填的就是某个 TestElement 子类的全限定名(如 `org.apache.jmeter.config.Arguments`);嵌套的**非 TestElement** bean(如 `SampleSaveConfiguration`)则用 `class`,对应 JMX 自己的 `class` 属性(见 [§6.3](#63-object-类型专用字段))。

**TestElement 可嵌套**:一个 TestElement 能作为另一个 TestElement 的子属性(如 `ThreadGroup` 嵌套 `LoopController`),挂载方式由 `mountMode` 决定。

### 2.2 property(JMeter 内部存储)

TestElement 内部用 `JMeterProperty` 接口存值。常见子类与 schema `type` 的对应:

| JMeterProperty 子类 | 含义 | schema type |
|---------------------|------|-------------|
| `StringProperty` | 字符串 | `String` |
| `IntegerProperty` / `LongProperty` | 整数 / 长整数 | `Integer` / `Long` |
| `TestElementProperty` | 嵌套 TestElement 或容器 | `Object`(TestElement) / `Array`(容器) |
| `CollectionProperty` | 列表/表格 | `Array` / `ARRAY_2D` |

写 schema 时**不用关心**这些 property 子类,选对 `type` 后 Java 代码会自动选对应 property 类型存值。

### 2.3 setter 与"无参构造 + setter"模式

Java 类用 `setName(...)` / `setValue(...)` 给字段赋值的方法叫 setter。JMeter 几乎所有 TestElement 都有 **public 无参构造**,因此 schema 解析器的工作流是:

```
Class.forName(itemClass).getDeclaredConstructor().newInstance()   // 无参构造
  → 用 setter 把每个字段塞进去
```

绝大多数 setter 方法名可从字段名推导(`Argument.name` → `setName`),少数不规则的需要在 schema 里手动指定(见 [§10 setterOverride](#10-setteroverride-与命名推导规则))。

### 2.4 容器(container)与 item

JMeter 里"列表/表格"型数据通常由两个类组成:

- **容器类**:列表的"外壳",提供 `addXxx(...)` 方法塞 item(如 `Arguments`、`HeaderManager`、`HTTPFileArgs`)
- **item 类**:列表里每个元素(如 `Argument`、`Header`、`HTTPFileArg`)

schema 里:
- 容器类填 `testClass`(如 `org.apache.jmeter.config.Arguments`)
- item 类填 `itemClass`(如 `org.apache.jmeter.protocol.http.util.HTTPArgument`)
- 容器的 add 方法名填 `containerAddMethod`(如 `addArgument`、`add`)

### 2.5 mountMode(挂载方式)

容器/嵌套对象**挂到父元素**有三种方式,详见 [§9](#9-mountmode-枚举值):

- `TestElementProperty`:容器/对象作为独立 TestElement 挂上去
- `self`:父元素本身就是容器,直接在自己身上调 add 方法
- `ObjectProperty`:嵌套对象不是 TestElement(普通 Java bean,如 `SampleSaveConfiguration`)

---

## 3. 如何发现组件的属性

写 schema 前必须知道组件有哪些属性、属性名怎么写。三条路径,优先级从高到低。

### 路径 A:JMeter GUI 观察字段结构

1. 在 JMeter GUI 里加这个组件
2. 看右侧属性面板:
   - 输入框/勾选框/下拉框 → 简单标量(`String` / `Integer` / `Boolean`)
   - 表格 → `Array` 模板(见 [§11](#11-模板决策树))
   - 嵌套面板(独立子组件配置区) → `Object` 模板
3. GUI 不显示底层 property key,字段名需配合路径 B/C 确认

### 路径 B:读 JMeter 源码 setter(最准确)

JMeter 源码本地路径:`D:\WorkHome\git\github\jmeter-5.6.3`,或官方仓库 `https://github.com/apache/jmeter`。

1. 找到组件的 model 类(如 `DebugSampler` → `org.apache.jmeter.sampler.DebugSampler`)
2. 看类里所有 `public setXxx(...)` 方法 —— 每个通常对应一个 schema 属性
3. **schema 字段 `name` 的约定**:
   - 复杂组件(有命名空间):`{简单类名}.{字段名}`,如 `Argument.name`、`HTTPSampler.protocol`、`ThreadGroup.num_threads`
   - 简单组件:可省略命名空间直接用字段名,如 `displayJMeterProperties`
   - 不确定时以 [路径 C](#路径-c从-jmx-文件抓取真实-property-key) 抓到的真实 key 为准
4. setter 参数类型 → schema `type`(`String` → `String`、`int` → `Integer`、`boolean` → `Boolean`)

### 路径 C:从 `.jmx` 文件抓取真实 property key

1. 在 JMeter GUI 里手动配好组件,保存为 `.jmx`
2. 用文本编辑器打开 `.jmx`(本质是 XML),搜索 `<testelement class="...你的组件类...">`
3. 子标签的 `name` 属性就是 schema `name` 字段的值:

   | XML 标签 | schema type |
   |---------|-------------|
   | `<stringProp name="xxx">` | `String` |
   | `<intProp name="xxx">` | `Integer` |
   | `<longProp name="xxx">` | `Long` |
   | `<boolProp name="xxx">` | `Boolean` |
   | `<collectionProp name="xxx">` | `Array` 或 `ARRAY_2D` |
   | `<elementProp name="xxx">` | `Object`,或 `Array` 的 item |

**推荐组合**:GUI 看结构 + JMX 抓真实 key + 源码确认 setter,三者交叉验证最稳。

---

## 4. 文件总体结构

每个 schema 文件描述一个 JMeter 组件,由两段组成:

```yaml
component:
  type: httpsampler            # 组件 elementType(小写),registry 主索引
  name: HTTP Request           # 显示名(同时作为默认显示名)
  description: Sends HTTP...   # 用途说明
  aliases: [httprequest]       # 可选:别名 elementType 列表
  testClass: org.apache.jmeter.protocol.http.sampler.HTTPSamplerProxy   # 必填:model 类全限定名(registry 读取)
  guiClass: org.apache.jmeter.protocol.http.control.gui.HttpTestSampleGui  # 必填:GUI 类全限定名(registry 读取)

properties:
  - name: HTTPSampler.protocol
    type: String
    ...
  - name: HTTPsampler.Arguments
    type: Array
    ...
```

- `component` 段:组件元信息,必填 `type`/`name`/`description`/`testClass`/`guiClass`
- `properties` 段:属性列表,每项是一个 `PropertyDefinition`

**文件位置约定**:`references/{source}/{category}/{ComponentName}.schema.yaml`。

- `{source}`(按组件**来源**区分,loader 递归扫描,放在哪个 source 目录不影响加载,仅用于人/Agent 区分原生与第三方):

| source | 含义 | 判定 |
|--------|------|------|
| `native` | Apache JMeter 原生 | `testClass` 为 `org.apache.jmeter.*` 且真实存在于 JMeter 源码 |
| `gitee-qa` | Gitee QA 扩展(本生态自研) | `testClass` 为 `com.gitee.qa.jmeter.*` |
| `third-party` | 外部第三方插件(需单独安装) | 非 JMeter 原生、非本生态(如 jmeter-plugins `kg.apc.*`、SSH Sampler) |

- `{category}`(按组件**功能**):

| category | 组件类型 |
|----------|---------|
| `controllers` | 逻辑控制器 |
| `samplers` | 采样器 |
| `assertions` | 断言 |
| `thread-group` | 线程组 |
| `timers` | 定时器 |
| `configuration` | 配置元件 |
| `pre-processors` | 前置处理器 |
| `post-processors` | 后置处理器 |
| `listeners` | 监听器 |
| `test-fragments` | 测试片段 |

---

## 5. component 段(组件元信息)

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `type` | String | ✅ | 组件 elementType,小写唯一标识(如 `httpsampler`、`threadgroup`、`headermanager`)。被 `ElementRegistry` 注册的主索引 |
| `name` | String | ✅ | 显示名(如 `HTTP Request`),同时作为该组件的默认显示名(`getDefaultNameForElement` 的数据源) |
| `description` | String | ✅ | 组件用途,供 AI 理解 |
| `aliases` | List&lt;String&gt; | ❌ | elementType 别名,允许通过多个名字加载同一 schema,别名与主 type 共享同一份 testClass/guiClass/name |
| `testClass` | String | ✅ | model 类(TestElement 子类)全限定名(如 `org.apache.jmeter.protocol.http.sampler.HTTPSamplerProxy`)。运行时由 `ElementRegistry` 读取用于创建实例,对应 JMX 文件的 `testclass` 属性 |
| `guiClass` | String | ✅ | GUI 面板类全限定名(如 `org.apache.jmeter.protocol.http.control.gui.HttpTestSampleGui`)。对应 JMX 文件的 `guiclass` 属性。缺失 `testClass`/`guiClass` 的 schema 会被 registry 跳过并告警 |

**示例**:
```yaml
component:
  type: httpsampler
  aliases: [httptestsample, httprequest]
  name: HTTP Request
  description: Sends HTTP/HTTPS requests to web servers
  testClass: org.apache.jmeter.protocol.http.sampler.HTTPSamplerProxy
  guiClass: org.apache.jmeter.protocol.http.control.gui.HttpTestSampleGui
```

---

## 6. properties 段(顶层属性)

每个 property 是一个 Map,字段按 `type` 不同有不同要求。

### 6.1 通用字段(所有 type 都可用)

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `name` | String | ✅ | 属性名,**必须与 LLM 传给工具的 key 完全一致**(如 `HTTPSampler.protocol`、`HTTPsampler.Arguments`)。命名约定见 [§3 路径 C](#路径-c从-jmx-文件抓取真实-property-key) |
| `type` | String | ✅ | 属性类型,见 [§8](#8-type-枚举值) |
| `required` | Boolean | ❌ | 是否必填,默认 `false` |
| `default` | Any | ❌ | 默认值。LLM 未传时,`buildItemViaNoArgCtorAndSetters` 会用此值调 setter(对 itemProperties 子字段也生效) |
| `description` | String | ❌ | 字段说明,供 AI 理解参数含义 |
| `enum` | List&lt;String&gt; | ❌ | 枚举值限制(如 `["GET", "POST", "PUT"]`) |
| `min` / `max` | Number | ❌ | 数值范围限制 |
| `pattern` | String | ❌ | 正则校验 |

### 6.2 Array 类型专用字段

Array 类型会根据以下字段自动路由到 **3 个子模板** 之一([决策树](#11-模板决策树)):

| 字段 | 类型 | 用于模板 | 说明 |
|------|------|---------|------|
| `testClass` | String | container-items | 容器类(TestElement)全限定名(如 `org.apache.jmeter.config.Arguments`),对应 JMX `testclass`。**Array 声明此字段即触发 container-items 模板**。与 [§6.3](#63-object-类型专用字段) Object 的 `testClass` 同义(都是 TestElement 的类) |
| `mountMode` | String | container-items | 容器挂载方式,见 [§9](#9-mountmode-枚举值) |
| `containerAddMethod` | String | container-items | 容器的 add 方法名(如 `addArgument`、`add`、`addHTTPFileArg`) |
| `itemClass` | String | container-items / testbean-table | item 元素类全限定名(如 `org.apache.jmeter.config.Argument`) |
| `itemProperties` | List | container-items / testbean-table / nested-array | item 字段定义列表,见 [§7](#7-子字段itemproperties--properties-内) |
| `innerItemType` | String | nested-array(ARRAY_2D) | 内层元素类型(目前仅 `String`) |

### 6.3 Object 类型专用字段

Object 类型会根据 `mountMode` 路由到 **nested-object 模板**:

| 字段 | 类型 | 说明 |
|------|------|------|
| `testClass` | String | 嵌套 **TestElement** 类全限定名(如 `org.apache.jmeter.control.LoopController`),对应 JMX `testclass`,与 `mountMode: TestElementProperty` 配套 |
| `class` | String | 嵌套**非 TestElement** bean 全限定名(如 `org.apache.jmeter.samplers.SampleSaveConfiguration`),对应 JMX `class` 属性,与 `mountMode: ObjectProperty` 配套 |
| `mountMode` | String | 默认 `TestElementProperty`(TestElement 嵌套);非 TestElement 用 `ObjectProperty`,见 [§9](#9-mountmode-枚举值) |
| `guiClass` | String | **仅 TestElement 嵌套需要**:GUI 面板类全限定名(如 `org.apache.jmeter.control.gui.LoopControlPanel`)。非 TestElement 不写 |
| `properties` | List | 嵌套对象的子字段定义列表(注意是 `properties` 不是 `itemProperties`),见 [§7](#7-子字段itemproperties--properties-内) |
| `setterOverride` | String | **仅 `mountMode: TestElementProperty` 时有效**:父元素挂载该嵌套对象用的方法名(如 `setSamplerController`)。ObjectProperty 模式下此字段在外层无意义(子字段的 override 写在 `properties` 里),见 [§10](#10-setteroverride-与命名推导规则) |

### 6.4 ARRAY_2D 类型专用字段

| 字段 | 类型 | 说明 |
|------|------|------|
| `innerItemType` | String | 内层元素类型(目前仅 `String`) |
| `itemProperties` | List | 可选。声明后,LLM 传 Map 格式输入时按此顺序提取字段值;不声明则要求 List 格式输入 |

---

## 7. 子字段(itemProperties / properties 内)

`itemProperties`(Array 的 item 字段)和 `properties`(Object 的子字段)里每一项的字段:

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `name` | String | ✅ | 字段名。**必须与 LLM 传入的 Map key 一致**(如 `Argument.name`、`Cookie.expires`) |
| `type` | String | ✅ | 字段类型(见 [§8](#8-type-枚举值))。主要用于 AI 理解,实际反射构造不强校验 |
| `required` | Boolean | ❌ | 是否必填 |
| `default` | Any | ❌ | 默认值。LLM 未传时用于调 setter(如 `Argument.name` 的 `default: ""` 用于 postBodyRaw 模式) |
| `description` | String | ❌ | 字段说明 |
| `setterOverride` | String | ❌ | 覆盖默认 setter 命名推导,见 [§10](#10-setteroverride-与命名推导规则) |

---

## 8. type 枚举值

| type 值 | 用途 | JMeter property 类型 |
|---------|------|---------------------|
| `String` | 字符串 | StringProperty |
| `Integer` | 32 位整数 | IntegerProperty |
| `Long` | 64 位长整数 | LongProperty |
| `Float` | 32 位浮点 | FloatProperty |
| `Double` | 64 位浮点 | DoubleProperty |
| `Boolean` | 布尔 | 对应 setter 的 boolean/Boolean 参数 |
| `Number` | 通用数值(legacy) | DoubleProperty |
| `Object` | 嵌套对象 → 走 nested-object 模板 | TestElementProperty / ObjectProperty |
| `Array` | 数组 → 走 3 个子模板之一 | CollectionProperty / TestElementProperty |
| `ARRAY_2D` | 二维数组 → 走 nested-array 模板 | CollectionProperty(嵌套) |

---

## 9. mountMode 枚举值

mountMode 决定容器/对象**如何挂载到父元素**。YAML 里三种写法等价(大小写、驼峰/下划线都支持):

| YAML 写法 | 等价写法 | 用于 | 挂载方式 |
|-----------|---------|------|---------|
| `TestElementProperty` | `TEST_ELEMENT_PROPERTY` / `test-element-property` | container-items / nested-object | 创建独立容器实例,用 `TestElementProperty(propName, container)` 挂到父元素 |
| `self` | `SELF` | container-items | **父元素本身就是容器**,直接在父元素上调 `containerAddMethod`(如 HeaderManager.headers) |
| `ObjectProperty` | `OBJECT_PROPERTY` | nested-object(非 TestElement) | 用 `ObjectProperty(propName, obj)` 挂载,用于 SampleSaveConfiguration 这类普通 Java bean |

**判断规则**:
- 父元素**是**容器类型?(HeaderManager 是 HeaderManager、CookieManager 是 CookieManager)→ `self`
- 容器是**独立 TestElement**(Arguments、HTTPFileArgs)?→ `TestElementProperty`
- 对象是**非 TestElement** Java bean(SampleSaveConfiguration)?→ `ObjectProperty`

---

## 10. setterOverride 与命名推导规则

container-items / nested-object 模板用**无参构造 + setter** 创建 item/对象。setter 方法名按以下顺序推导:

### 推导顺序

1. **查子字段的 `setterOverride`**:字段声明上的显式覆盖
2. **默认推导**:`set` + 去掉命名空间(最后一个 `.` 后)+ 下划线转驼峰 + 首字母大写

### 默认推导示例

| 字段名 | 去命名空间 | 下划线转驼峰 | setter |
|--------|-----------|-------------|--------|
| `Argument.name` | `name` | `name` | `setName` ✅ |
| `HTTPArgument.use_equals` | `use_equals` | `useEquals` | `setUseEquals` ✅ |
| `Cookie.path_specified` | `path_specified` | `pathSpecified` | `setPathSpecified` ✅ |
| `File.path` | `path` | `path` | `setPath` ✅ |

### 必须显式 override 的情况

以下命名**无法用规则推导**,必须在子字段上写 `setterOverride`:

| 字段名 | 实际 setter | 不规则原因 |
|--------|------------|-----------|
| `Argument.desc` | `setDescription` | 字段名是缩写(desc → Description) |
| `Argument.metadata` | `setMetaData` | 中间字母大写(metadata → MetaData) |
| `HTTPArgument.always_encode` | `setAlwaysEncoded` | 词形变化(encode → Encoded 过去分词) |
| `File.paramname` | `setParamName` | 全小写多词拼接(paramname → ParamName) |
| `File.mimetype` | `setMimeType` | 全小写多词拼接(mimetype → MimeType) |
| `xml` | `setAsXml` | 完全非标准 |

### setterOverride 写法

**子字段级**(写在 itemProperties / properties 的某个字段下):

```yaml
itemProperties:
  - name: Argument.desc
    type: String
    setterOverride: setDescription
  - name: Argument.metadata
    type: String
    setterOverride: setMetaData
```

**父属性级**(写在外层 Object 属性上,**仅 `mountMode: TestElementProperty` 模式生效**):用于声明父元素挂载该嵌套对象的方法名(默认会用 `setProperty(TestElementProperty)`,但像 `ThreadGroup.main_controller` 这种需要走 `setSamplerController`):

```yaml
- name: ThreadGroup.main_controller
  type: Object
  mountMode: TestElementProperty
  guiClass: org.apache.jmeter.control.gui.LoopControlPanel
  setterOverride: setSamplerController
```

> 两种位置的语义都一致:**`setterOverride` 出现在哪个 property 上,就替换那个 property 的默认 setter**。目标对象由结构位置决定(嵌套对象 → 父元素;子字段 → item/嵌套对象实例)。

---

## 11. 模板决策树

`handleBySchemaType` 按以下顺序判断:

```
读取 propDef.type
│
├── STRING / INTEGER / LONG / FLOAT / DOUBLE / BOOLEAN / NUMBER
│   └─→ handleSimpleProperty(直接 setProperty)
│
├── OBJECT
│   │   读取 propDef.mountMode
│   ├─→ ObjectProperty  ──→  handleNonTestElementObject(非 TestElement,如 SampleSaveConfiguration)
│   └─→ 默认(TestElementProperty) ──→ handleNestedObjectProperty(TestElement 嵌套,如 LoopController)
│
├── ARRAY
│   │   读取 propDef.className(testClass/class) / itemClass / itemProperties
│   ├─→ className != null        ──→  handleContainerItemsProperty(container-items 模板)
│   ├─→ itemClass + itemProperties 都有    ──→  handleTestBeanTableProperty(testbean-table 模板)
│   └─→ 都没有                             ──→  handleGenericCollectionProperty(string-collection 模板)
│
└── ARRAY_2D
    └─→ handleNestedArrayProperty(nested-array 模板)
```

**按"GUI 看到的样子"翻译**(给非作者用户的简化决策):

| GUI 里看到的 | 选哪个模板 | 关键字段 |
|-------------|-----------|---------|
| 单个输入框/勾选框 | 简单属性 | `type: String/Integer/Boolean` |
| 表格,每行一组字段 | container-items / testbean-table | `type: Array`,看父是否容器选 `mountMode` |
| 嵌套面板(独立子组件配置区) | nested-object | `type: Object`,TestElement 嵌套要填 `guiClass` |
| 字符串列表(没有字段名) | string-collection | `type: Array`,不填 `testClass`/`itemClass` |
| 二维表格(行/列都是值) | nested-array | `type: ARRAY_2D` |

---

## 12. 7 个模板的完整示例

### 模板 1:container-items(Arguments 族,TestElementProperty 挂载)

代表:`HTTPsampler.Arguments`、`Arguments.arguments`(BackendListener)、`GitSampler.Arguments`、`SystemSampler.environment`

```yaml
- name: HTTPsampler.Arguments
  type: Array
  testClass: org.apache.jmeter.config.Arguments
  mountMode: TestElementProperty
  containerAddMethod: addArgument
  itemClass: org.apache.jmeter.protocol.http.util.HTTPArgument
  itemProperties:
    - name: Argument.name
      type: String
      default: ""    # postBodyRaw 模式下 name 默认空串
    - name: Argument.value
      type: String
      required: true
    - name: HTTPArgument.use_equals
      type: Boolean
      default: true
    - name: HTTPArgument.always_encode
      type: Boolean
      default: false
      setterOverride: setAlwaysEncoded    # ← 命名不规则,在字段上声明
    - name: Argument.metadata
      type: String
      default: "="
      setterOverride: setMetaData         # ← 命名不规则,在字段上声明
```

### 模板 2:container-items(self 挂载,父元素即容器)

代表:`HeaderManager.headers`、`CookieManager.cookies`、`Arguments.arguments`(UserDefinedVariables)

```yaml
- name: HeaderManager.headers
  type: Array
  testClass: org.apache.jmeter.protocol.http.control.HeaderManager
  mountMode: self           # ← 关键:父元素本身就是 HeaderManager 容器
  containerAddMethod: add   # ← HeaderManager.add(Header)
  itemClass: org.apache.jmeter.protocol.http.control.Header
  itemProperties:
    - name: Header.name
      type: String
      required: true
    - name: Header.value
      type: String
      required: true
```

### 模板 3:testbean-table(TestBean 表格)

代表:`ValueAssertion.valuesCheckTable`、`VariableAssertion.variablesCheckTable`

特征:**无** `testClass`,但有 `itemClass` + `itemProperties`,item 用无参构造 + setter,挂载为 `CollectionProperty`。

```yaml
- name: valuesCheckTable
  type: Array
  itemClass: com.gitee.qa.jmeter.assertions.ValueAssertionTableElement
  itemProperties:
    - name: value
      type: String
    - name: operator
      type: String
```

### 模板 4:nested-object(TestElement 嵌套)

代表:`ThreadGroup.main_controller`(8 个线程组系列)、所有"嵌套子控制器"场景

```yaml
- name: ThreadGroup.main_controller
  type: Object
  testClass: org.apache.jmeter.control.LoopController
  guiClass: org.apache.jmeter.control.gui.LoopControlPanel    # ← TestElement 必填
  setterOverride: setSamplerController                         # ← parent 上的特殊 setter
  properties:                                                  # ← 注意是 properties 不是 itemProperties
    - name: LoopController.loops
      type: Integer
      default: 1
    - name: LoopController.continue_forever
      type: Boolean
      default: false
```

### 模板 5:nested-object(非 TestElement,ObjectProperty 挂载)

代表:`ViewResultsTree.saveConfig`、`SummaryReport.saveConfig`、`AggregateReport.saveConfig`

```yaml
- name: saveConfig
  type: Object
  mountMode: ObjectProperty                                    # ← 非 TestElement
  class: org.apache.jmeter.samplers.SampleSaveConfiguration    # ← 非 TestElement bean 用 class(对应 JMX class 属性,非 testclass)
  properties:
    - name: time
      type: Boolean
      default: true
    - name: xml
      type: Boolean
      default: false
      setterOverride: setAsXml                                 # ← 非标准 setter,声明在字段上
```

> 注:`SampleSaveConfiguration.responseDataOnError` / `assertionsResultsToSave` 没有 setter,schema 里**不要声明**这些字段,否则 `applySetter` 会因找不到 setter 报 warn(不影响其他字段)。

### 模板 6:nested-array(ARRAY_2D)

代表:`UserParameters.thread_values`、`ultimatethreadgroupdata`

```yaml
# 纯字符串二维数组(无字段名)
- name: UserParameters.thread_values
  type: ARRAY_2D
  innerItemType: String
  required: true

# Map 输入按 itemProperties 顺序提取(替代旧的硬编码 fieldOrder)
- name: ultimatethreadgroupdata
  type: ARRAY_2D
  required: true
  itemProperties:
    - name: Start Threads Count
      type: String
    - name: Initial Delay
      type: String
    - name: Startup Time
      type: String
    - name: Hold Load For
      type: String
    - name: Shutdown Time
      type: String
```

### 模板 7:string-collection(纯字符串数组,兜底)

代表:`Asserion.test_strings`、`ModuleController.node_path`、`UserParameters.names`

特征:`Array` 类型但**无** `testClass` / `itemClass`,直接存为字符串 `CollectionProperty`。

```yaml
- name: UserParameters.names
  type: Array
  required: true
  description: List of parameter names
```

---

## 13. 新增组件 Checklist

新增一个 JMeter 组件,完整流程。

### 第 0 步:判断组件当前在不在 registry

组件元信息(testClass/guiClass/默认名)现在由 [`ElementRegistry`](../../../src/main/java/org/gitee/jmeter/ai/utils/ElementRegistry.java) 从两处 YAML 加载:**有 schema 的组件**读 `.schema.yaml` 的 `testClass`/`guiClass`,`name` 作默认名;**无 schema 的组件**读 [`legacy-elements.yaml`](./legacy-elements.yaml)。

先在 [`references/`](./references/) 下找有没有该组件的 `.schema.yaml`,或在 [`legacy-elements.yaml`](./legacy-elements.yaml) 里搜你的 elementType:

- **已有 schema** → 跳到第 2 步补/改属性
- **只在 legacy-elements.yaml 里** → 它只用于兼容性反查、不参与 AI 创建;若现在要 AI 能创建配置它,继续第 1 步写完整 schema(写好后可从 legacy yaml 删除该行,避免重复)
- **两处都没有** → 全新组件,继续第 1 步

### 第 1 步:声明组件元信息(纯 YAML,零 Java 改动)

不需要再改 `JMeterElementManager`(原来的 `ELEMENT_CLASS_MAP` 静态块和 `getDefaultNameForElement` switch 已删除)。根据是否需要 AI 创建配置,二选一:

**A. 需要 AI 能创建并配置**(绝大多数情况):写完整 `.schema.yaml`,在 `component` 段填 `testClass`/`guiClass`(必填,缺失会被 registry 跳过并告警):

```yaml
component:
  type: mynewsampler
  name: My New Sampler
  description: ...
  testClass: com.example.MyNewSampler
  guiClass: com.example.gui.MyNewSamplerGui
```

`testClass`/`guiClass` 怎么找:打开 JMeter GUI 手动加该组件 → 保存 `.jmx` → XML 里 `<testelement class="...">` 即 testClass,该节点 `guiclass` 属性即 guiClass;或直接查 JMeter 源码(model 类 + 对应 `*Gui` 类)。

**B. 只需 registry 识别**(用于 `isNodeCompatible` 兼容性反查,不让 AI 创建):在 [`legacy-elements.yaml`](./legacy-elements.yaml) 的 `elements` 下追加一行:

```yaml
  - elementType: mynewsampler
    testClass: com.example.MyNewSampler
    guiClass: com.example.gui.MyNewSamplerGui
    defaultName: "My New Sampler"
    aliases: [mynewsampler2]   # 可选
```

### 第 2 步:判断涉及的属性类型

对每个属性,回答:
- 是简单标量(String/Integer/Boolean)?→ 用对应 type
- 是嵌套对象?→ 是 TestElement 还是普通 bean?
- 是数组?→ 数组里的 item 是什么?容器是什么?

属性怎么发现见 [§3](#3-如何发现组件的属性)。

### 第 3 步:按 [§11 决策树](#11-模板决策树) 选模板

### 第 4 步:填写 schema

参考 [§12 示例](#12-7-个模板的完整示例)。

### 第 5 步:验证 setter 命名

对每个 itemProperties / properties 子字段,按 [§10](#10-setteroverride-与命名推导规则) 判断:
- 默认推导能得到正确 setter 吗?
- 不能 → 在该字段上写 `setterOverride`

### 第 6 步:更新 SKILL.md 组件索引

在 [`SKILL.md`](SKILL.md) 对应类别表格追加一行(elementType / Description / Docs 链接 / Schema 链接)。

### 第 7 步:测试

见 [§14 测试与排错](#14-测试与排错)。

---

## 14. 测试与排错

### 14.1 YAML 语法校验

YAML 缩进/引号错误会导致整个 schema 加载失败。推荐:
- 用带 YAML 插件的 IDE(VSCode + YAML、IntelliJ)打开,语法错误直接标红
- `mvn test -Dtest=SchemaLoaderTest` 遍历加载所有 schema,任意一个解析失败会在此用例报错

### 14.2 schema 字段类型校验

`mvn test -Dtest=ComponentSchemaTypeTest` 校验每个 schema 的字段类型合法性。

### 14.3 Agent 调用层验证

写完 schema 后,启动 JMeter GUI,让 Agent 调用 `create_jmeter_element` 工具创建这个组件并填几个字段,然后:
- 查看 JMeter 测试计划树,组件是否成功创建
- 右键组件看属性面板,字段值是否正确填入
- 看 JMeter 日志,有没有 `applySetter` 找不到 setter 的 warn(若有 → setter 命名问题,在该字段上加 `setterOverride`)

### 14.4 常见错误对照

| 现象 | 可能原因 | 排查方向 |
|------|---------|---------|
| Agent 报"找不到组件类型" | schema 缺 `testClass`/`guiClass`(被 registry 跳过)、legacy-elements.yaml 未收录,或 elementType 拼写不一致 | 第 1 步声明元信息,确保 schema `type` 与查询的 elementType 一致(注意别名) |
| 组件创建了但字段没填上 | setter 名推导错误 | 在该字段上加 `setterOverride`,看日志 warn |
| YAML 解析失败 | 缩进/引号错误 | IDE 标红位置 |
| 表格字段渲染异常 | `Array` 模板选错 / `mountMode` 不对 | 重看 [§11 决策树](#11-模板决策树) |
| 嵌套对象属性不生效 | `Object` 模板缺 `guiClass`(TestElement)/ 缺 `mountMode: ObjectProperty`(非 TestElement) | 重看 [§12 模板 4/5](#12-7-个模板的完整示例) |

---

## 15. 常见陷阱

### 陷阱 1:字段名 vs property 名混淆

`name` 字段的值是 **LLM 传入的 Map key**,不是 JMeter 内部 property 名。例如:
- LLM 传 `{"Argument.name": "x"}` → schema 字段 `name: Argument.name`
- JMeter 实际存到 TestElement.name(由 Argument.setName 处理)

两者通常一致,但 BackendListener 是反例:LLM 用 `Arguments.arguments`,JMeter 实际存到 `arguments` property(由 `findExistingContainer` 自动发现)。

### 陷阱 2:setter 顺序副作用

某些 setter 有副作用,`itemProperties` 顺序很重要:
- `HTTPArgument.setUseEquals(true)` 会覆盖 `metadata` → `Argument.metadata` 要列在 `HTTPArgument.use_equals` 之后
- `HTTPFileArg.setPath(path)` 会触发 `detectMimeType` → `File.mimetype` 要列在 `File.path` 之后(或 `setMimeType` 会覆盖自动检测值)

### 陷阱 3:postBodyRaw 双模式

`HTTPsampler.Arguments` 在 postBodyRaw 模式下,name 为空。解决:给 `Argument.name` 设 `default: ""`,LLM 不传时用空串。

### 陷阱 4:无参构造缺失

container-items / testbean-table / nested-object 模板都用**无参构造 + setter**。如果 item 类没有 public 无参构造,运行时会失败。所有 JMeter TestElement 都有无参构造(约定),自定义类需自行确认。

### 陷阱 5:解析失败保护

container-items 模板有"解析失败保护":用户传了 items 但全部解析失败(非 Map、类型错)时,**跳过更新以保留现有数据**。空数组 `[]` 则清空容器。两者语义不同。

### 陷阱 6:containerMount=self 时清空逻辑

`mountMode: self` 下,代码会先清空父元素上 propName 对应的 CollectionProperty,再调 `containerAddMethod` 添加。如果父元素的 item 存储路径与 propName 不一致,清空可能无效。HeaderManager / CookieManager 都遵循 `propName == CollectionProperty 名`,正常工作。

---

## 参考

- 代码实现:[SchemaBasedPropertyHandler.java](../../../src/main/java/org/gitee/jmeter/ai/agent/tools/jmeter/property/SchemaBasedPropertyHandler.java)
- schema 模型:[ComponentSchema.java](../../../src/main/java/org/gitee/jmeter/ai/agent/validation/ComponentSchema.java)
- schema 加载:[ComponentSchemaLoader.java](../../../src/main/java/org/gitee/jmeter/ai/agent/validation/ComponentSchemaLoader.java)
- 组件注册:[ElementRegistry.java](../../../src/main/java/org/gitee/jmeter/ai/utils/ElementRegistry.java)(schema + legacy-elements.yaml 合并加载)
- 组件创建/增删:[JMeterElementManager.java](../../../src/main/java/org/gitee/jmeter/ai/utils/JMeterElementManager.java)
