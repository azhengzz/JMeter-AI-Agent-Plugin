# JMeter Component Schema Authoring Guide

> English translation of [SCHEMA-GUIDE.md](./SCHEMA-GUIDE.md) (中文版).

This guide explains the definition, allowed values, and authoring rules for each field in the `references/**/*.schema.yaml` files.

**Component metadata (testClass/guiClass) has been fully data-driven**, so adding/completing a component requires **zero Java changes** — just write YAML:

| Scenario | YAML change | Java change |
|------|----------|----------|
| AI needs to be able to **create and configure** the component (has a property schema) | ✅ Write a `.schema.yaml` (containing `testClass`/`guiClass` + `properties`) | ❌ Not required |
| The component only needs to be **recognizable** in the registry (used for compatibility reverse-lookup, not involved in AI creation) | ✅ Add one line to [`legacy-elements.yaml`](src/main/jmeter-agent/skills/jmeter/legacy-elements.yaml) | ❌ Not required |

How to decide: look under [`references/`](src/main/jmeter-agent/skills/jmeter/references/) for the component's `.schema.yaml`, or search [`legacy-elements.yaml`](src/main/jmeter-agent/skills/jmeter/legacy-elements.yaml) for the elementType. If neither exists → pick one of the two options above as needed. See [§13 Checklist](#13-adding-a-new-component-checklist).

## Table of Contents

- [1. Quick Start: add a minimal component in 5 minutes](#1-quick-start-add-a-minimal-component-in-5-minutes)
- [2. Prerequisite concepts](#2-prerequisite-concepts)
- [3. How to discover a component's properties](#3-how-to-discover-a-components-properties)
- [4. Overall file structure](#4-overall-file-structure)
- [5. The component section (component metadata)](#5-the-component-section-component-metadata)
- [6. The properties section (top-level properties)](#6-the-properties-section-top-level-properties)
- [7. Sub-fields (inside itemProperties / properties)](#7-sub-fields-inside-itemproperties--properties)
- [8. type enum values](#8-type-enum-values)
- [9. mountMode enum values](#9-mountmode-enum-values)
- [10. setterOverride and naming derivation rules](#10-setteroverride-and-naming-derivation-rules)
- [11. Template decision tree](#11-template-decision-tree)
- [12. Complete examples of the 7 templates](#12-complete-examples-of-the-7-templates)
- [13. Adding a new component checklist](#13-adding-a-new-component-checklist)
- [14. Testing and troubleshooting](#14-testing-and-troubleshooting)
- [15. Common pitfalls](#15-common-pitfalls)

---

## 1. Quick Start: add a minimal component in 5 minutes

If your component **has only simple scalar properties** (strings, integers, booleans) with no tables or nesting, just copy the skeleton below:

```yaml
component:
  type: debugsampler          # lowercase elementType, the registry primary key
  name: Debug Sampler         # display name (also used as the default display name)
  description: Samples JMeter variables, JMeter properties and System Properties for debugging purposes
  testClass: org.apache.jmeter.sampler.DebugSampler        # required: fully-qualified model class name
  guiClass: org.apache.jmeter.testbeans.gui.TestBeanGUI    # required: fully-qualified GUI class name

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

**Steps**:

1. Create `{ComponentName}.schema.yaml` under `references/{source}/{category}/` — choose `{source}` by origin: `native` (Apache JMeter built-in) / `gitee-qa` (Gitee QA extension) / `third-party` (external third-party plugin); `{category}` is one of `controllers` / `samplers` / `assertions` / `thread-group` / `timers` / `configuration` / `pre-processors` / `post-processors` / `listeners` / `test-fragments` (see [§4](#4-overall-file-structure))
2. Change `type` / `name` / `description` in the skeleton to your component
3. Change `properties` to your component's actual properties — how to discover them is in [§3](#3-how-to-discover-a-components-properties)
4. If a property contains a table / nested object / array you need to pick a template, see [§11 decision tree](#11-template-decision-tree)
5. A schema with `testClass`/`guiClass` filled in auto-registers the component, **no Java changes required**; if the component has no property schema and only needs registry recognition, switch to `legacy-elements.yaml` instead (see [§13](#13-adding-a-new-component-checklist))

In the simplest case you only use the three types `String` / `Integer` / `Boolean`, with no need for advanced fields like `mountMode` / `testClass` / `itemProperties`.

---

## 2. Prerequisite concepts

The following terms recur throughout the field tables below, so they are clarified up front.

### 2.1 TestElement

In JMeter, almost every serializable component that can be saved to a `.jmx` implements the `TestElement` interface — Sampler, Controller, Assertion, PreProcessor, ConfigElement, Listener, etc. are all TestElements. The `testClass` / `itemClass` fields in a schema hold the fully qualified name of some TestElement subclass (e.g. `org.apache.jmeter.config.Arguments`); for nested **non-TestElement** beans (such as `SampleSaveConfiguration`) the `class` field is used, corresponding to JMX's own `class` attribute (see [§6.3](#63-object-type-specific-fields)).

**A TestElement can be nested**: one TestElement can be a child property of another (e.g. `ThreadGroup` nests a `LoopController`); the mounting mechanism is decided by `mountMode`.

### 2.2 property (JMeter internal storage)

Inside a TestElement, values are stored through the `JMeterProperty` interface. Common subclasses and their schema `type` correspondence:

| JMeterProperty subclass | Meaning | schema type |
|---------------------|------|-------------|
| `StringProperty` | string | `String` |
| `IntegerProperty` / `LongProperty` | integer / long integer | `Integer` / `Long` |
| `TestElementProperty` | nested TestElement or container | `Object` (TestElement) / `Array` (container) |
| `CollectionProperty` | list / table | `Array` / `ARRAY_2D` |

When authoring a schema you **do not need to care** about these property subclasses — once you pick the right `type`, the Java code automatically selects the corresponding property type to store the value.

### 2.3 setters and the "no-arg constructor + setter" pattern

In a Java class, the methods that assign fields — such as `setName(...)` / `setValue(...)` — are called setters. Almost every JMeter TestElement has a **public no-arg constructor**, so the schema parser's workflow is:

```
Class.forName(itemClass).getDeclaredConstructor().newInstance()   // no-arg constructor
  → populate each field via its setter
```

In the vast majority of cases the setter method name can be derived from the field name (`Argument.name` → `setName`); the few irregular ones must be specified manually in the schema (see [§10 setterOverride](#10-setteroverride-and-naming-derivation-rules)).

### 2.4 container and item

In JMeter, "list / table" style data is usually composed of two classes:

- **Container class**: the "shell" of the list, providing an `addXxx(...)` method to push items in (e.g. `Arguments`, `HeaderManager`, `HTTPFileArgs`)
- **Item class**: each element in the list (e.g. `Argument`, `Header`, `HTTPFileArg`)

In a schema:
- The container class goes in `testClass` (e.g. `org.apache.jmeter.config.Arguments`)
- The item class goes in `itemClass` (e.g. `org.apache.jmeter.protocol.http.util.HTTPArgument`)
- The container's add method name goes in `containerAddMethod` (e.g. `addArgument`, `add`)

### 2.5 mountMode (mounting mechanism)

A container / nested object is **attached to the parent element** in one of three ways — see [§9](#9-mountmode-enum-values) for details:

- `TestElementProperty`: the container / object is attached as an independent TestElement
- `self`: the parent element is itself the container, so the add method is invoked directly on itself
- `ObjectProperty`: the nested object is not a TestElement (a plain Java bean, e.g. `SampleSaveConfiguration`)

---

## 3. How to discover a component's properties

Before authoring a schema you must know what properties a component has and how their names are spelled. There are three paths, listed from highest to lowest priority.

### Path A: observe the field structure in the JMeter GUI

1. Add this component in the JMeter GUI
2. Look at the property panel on the right:
   - Text field / checkbox / dropdown → simple scalar (`String` / `Integer` / `Boolean`)
   - Table → `Array` template (see [§11](#11-template-decision-tree))
   - Nested panel (an independent sub-component config area) → `Object` template
3. The GUI does not show the underlying property key; the field name must be confirmed via path B/C

### Path B: read the JMeter source setters (most accurate)

JMeter source local path: `D:\WorkHome\git\github\jmeter-5.6.3`, or the official repo `https://github.com/apache/jmeter`.

1. Find the component's model class (e.g. `DebugSampler` → `org.apache.jmeter.sampler.DebugSampler`)
2. Look at every `public setXxx(...)` method in the class — each usually corresponds to one schema property
3. **Convention for the schema field `name`**:
   - Complex components (with a namespace): `{SimpleClassName}.{fieldName}`, e.g. `Argument.name`, `HTTPSampler.protocol`, `ThreadGroup.num_threads`
   - Simple components: the namespace may be omitted, using the field name directly, e.g. `displayJMeterProperties`
   - When unsure, the real key captured by [path C](#path-c-extract-the-real-property-key-from-the-jmx-file) is authoritative
4. The setter's parameter type → schema `type` (`String` → `String`, `int` → `Integer`, `boolean` → `Boolean`)

### Path C: extract the real property key from the `.jmx` file

1. Manually configure the component in the JMeter GUI and save it as a `.jmx`
2. Open the `.jmx` in a text editor (it is essentially XML) and search for `<testelement class="...your component class...">`
3. The `name` attribute of each child tag is the value of the schema `name` field:

   | XML tag | schema type |
   |---------|-------------|
   | `<stringProp name="xxx">` | `String` |
   | `<intProp name="xxx">` | `Integer` |
   | `<longProp name="xxx">` | `Long` |
   | `<boolProp name="xxx">` | `Boolean` |
   | `<collectionProp name="xxx">` | `Array` or `ARRAY_2D` |
   | `<elementProp name="xxx">` | `Object`, or an `Array` item |

**Recommended combination**: GUI for structure + JMX for real keys + source for confirming setters — cross-validating the three is the most reliable.

---

## 4. Overall file structure

Each schema file describes one JMeter component and consists of two sections:

```yaml
component:
  type: httpsampler            # component elementType (lowercase), the registry primary key
  name: HTTP Request           # display name (also used as the default display name)
  description: Sends HTTP...   # purpose description
  aliases: [httprequest]       # optional: list of alias elementType values
  testClass: org.apache.jmeter.protocol.http.sampler.HTTPSamplerProxy   # required: fully-qualified model class name (read by the registry)
  guiClass: org.apache.jmeter.protocol.http.control.gui.HttpTestSampleGui  # required: fully-qualified GUI class name (read by the registry)

properties:
  - name: HTTPSampler.protocol
    type: String
    ...
  - name: HTTPsampler.Arguments
    type: Array
    ...
```

- `component` section: component metadata; `type`/`name`/`description`/`testClass`/`guiClass` are required
- `properties` section: the list of properties; each item is a `PropertyDefinition`

**File location convention**: `references/{source}/{category}/{ComponentName}.schema.yaml`.

- `{source}` (distinguished by component **origin**; the loader scans recursively, so which source directory it sits in does not affect loading — it only helps humans/agents tell native from third-party):

| source | Meaning | Criterion |
|--------|------|------|
| `native` | Apache JMeter built-in | `testClass` is `org.apache.jmeter.*` and actually exists in the JMeter source |
| `gitee-qa` | Gitee QA extension (built in this ecosystem) | `testClass` is `com.gitee.qa.jmeter.*` |
| `third-party` | External third-party plugin (must be installed separately) | Not native JMeter, not part of this ecosystem (e.g. jmeter-plugins `kg.apc.*`, SSH Sampler) |

- `{category}` (by component **function**):

| category | Component type |
|----------|---------|
| `controllers` | Logic controllers |
| `samplers` | Samplers |
| `assertions` | Assertions |
| `thread-group` | Thread groups |
| `timers` | Timers |
| `configuration` | Configuration elements |
| `pre-processors` | Pre-processors |
| `post-processors` | Post-processors |
| `listeners` | Listeners |
| `test-fragments` | Test fragments |

---

## 5. The component section (component metadata)

| Field | Type | Required | Description |
|------|------|------|------|
| `type` | String | ✅ | The component elementType, a lowercase unique identifier (e.g. `httpsampler`, `threadgroup`, `headermanager`). The primary index registered by `ElementRegistry` |
| `name` | String | ✅ | Display name (e.g. `HTTP Request`), also used as the default display name for this component (the data source for `getDefaultNameForElement`) |
| `description` | String | ✅ | The component's purpose, to help the AI understand it |
| `aliases` | List&lt;String&gt; | ❌ | elementType aliases, allowing the same schema to be loaded under multiple names; aliases share the same testClass/guiClass/name as the primary type |
| `testClass` | String | ✅ | Fully qualified name of the model class (a TestElement subclass) (e.g. `org.apache.jmeter.protocol.http.sampler.HTTPSamplerProxy`). Read at runtime by `ElementRegistry` to create instances; corresponds to the JMX file's `testclass` attribute |
| `guiClass` | String | ✅ | Fully qualified name of the GUI panel class (e.g. `org.apache.jmeter.protocol.http.control.gui.HttpTestSampleGui`). Corresponds to the JMX file's `guiclass` attribute. A schema missing `testClass`/`guiClass` is skipped by the registry with a warning |

**Example**:
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

## 6. The properties section (top-level properties)

Each property is a Map whose fields differ in requirements depending on `type`.

### 6.1 Common fields (available for all types)

| Field | Type | Required | Description |
|------|------|------|------|
| `name` | String | ✅ | The property name, **must be exactly identical to the key the LLM passes to the tool** (e.g. `HTTPSampler.protocol`, `HTTPsampler.Arguments`). Naming convention in [§3 path C](#path-c-extract-the-real-property-key-from-the-jmx-file) |
| `type` | String | ✅ | The property type, see [§8](#8-type-enum-values) |
| `required` | Boolean | ❌ | Whether required, default `false` |
| `default` | Any | ❌ | Default value. When the LLM does not pass it, `buildItemViaNoArgCtorAndSetters` calls the setter with this value (also effective for itemProperties sub-fields) |
| `description` | String | ❌ | Field description, to help the AI understand the parameter's meaning |
| `enum` | List&lt;String&gt; | ❌ | Enum value constraint (e.g. `["GET", "POST", "PUT"]`) |
| `min` / `max` | Number | ❌ | Numeric range constraint |
| `pattern` | String | ❌ | Regex validation |

### 6.2 Array type-specific fields

The Array type auto-routes to one of **3 sub-templates** based on the following fields ([decision tree](#11-template-decision-tree)):

| Field | Type | Used by template | Description |
|------|------|---------|------|
| `testClass` | String | container-items | Fully qualified name of the container class (TestElement) (e.g. `org.apache.jmeter.config.Arguments`), corresponding to JMX `testclass`. **Declaring this field on an Array triggers the container-items template**. Synonymous with the `testClass` of Object in [§6.3](#63-object-type-specific-fields) (both are the TestElement's class) |
| `mountMode` | String | container-items | Container mounting mechanism, see [§9](#9-mountmode-enum-values) |
| `containerAddMethod` | String | container-items | The container's add method name (e.g. `addArgument`, `add`, `addHTTPFileArg`) |
| `itemClass` | String | container-items / testbean-table | Fully qualified name of the item element class (e.g. `org.apache.jmeter.config.Argument`) |
| `itemProperties` | List | container-items / testbean-table / nested-array | List of item field definitions, see [§7](#7-sub-fields-inside-itemproperties--properties) |
| `innerItemType` | String | nested-array (ARRAY_2D) | Inner element type (currently only `String`) |

### 6.3 Object type-specific fields

The Object type routes to the **nested-object template** based on `mountMode`:

| Field | Type | Description |
|------|------|------|
| `testClass` | String | Fully qualified name of the nested **TestElement** class (e.g. `org.apache.jmeter.control.LoopController`), corresponding to JMX `testclass`, paired with `mountMode: TestElementProperty` |
| `class` | String | Fully qualified name of a nested **non-TestElement** bean (e.g. `org.apache.jmeter.samplers.SampleSaveConfiguration`), corresponding to the JMX `class` attribute, paired with `mountMode: ObjectProperty` |
| `mountMode` | String | Default `TestElementProperty` (TestElement nesting); use `ObjectProperty` for non-TestElement, see [§9](#9-mountmode-enum-values) |
| `guiClass` | String | **Only required for TestElement nesting**: fully qualified name of the GUI panel class (e.g. `org.apache.jmeter.control.gui.LoopControlPanel`). Omit for non-TestElement |
| `properties` | List | List of sub-field definitions for the nested object (note: `properties`, not `itemProperties`), see [§7](#7-sub-fields-inside-itemproperties--properties) |
| `setterOverride` | String | **Only valid with `mountMode: TestElementProperty`**: the method name the parent element uses to mount this nested object (e.g. `setSamplerController`). Under ObjectProperty this field is meaningless at the outer level (an override for a sub-field is written inside `properties`), see [§10](#10-setteroverride-and-naming-derivation-rules) |

### 6.4 ARRAY_2D type-specific fields

| Field | Type | Description |
|------|------|------|
| `innerItemType` | String | Inner element type (currently only `String`) |
| `itemProperties` | List | Optional. When declared, Map-format input from the LLM extracts field values in this order; if not declared, List-format input is required |

---

## 7. Sub-fields (inside itemProperties / properties)

Each item in `itemProperties` (Array item fields) and `properties` (Object sub-fields):

| Field | Type | Required | Description |
|------|------|------|------|
| `name` | String | ✅ | Field name. **Must match the Map key passed by the LLM** (e.g. `Argument.name`, `Cookie.expires`) |
| `type` | String | ✅ | Field type (see [§8](#8-type-enum-values)). Mainly for AI understanding; the actual reflective construction does not strictly enforce it |
| `required` | Boolean | ❌ | Whether required |
| `default` | Any | ❌ | Default value. Used to call the setter when the LLM does not pass it (e.g. the `default: ""` for `Argument.name` is used for postBodyRaw mode) |
| `description` | String | ❌ | Field description |
| `setterOverride` | String | ❌ | Override the default setter naming derivation, see [§10](#10-setteroverride-and-naming-derivation-rules) |

---

## 8. type enum values

| type value | Purpose | JMeter property type |
|---------|------|---------------------|
| `String` | string | StringProperty |
| `Integer` | 32-bit integer | IntegerProperty |
| `Long` | 64-bit long integer | LongProperty |
| `Float` | 32-bit floating point | FloatProperty |
| `Double` | 64-bit floating point | DoubleProperty |
| `Boolean` | boolean | corresponds to a boolean/Boolean parameter of the setter |
| `Number` | generic number (legacy) | DoubleProperty |
| `Object` | nested object → goes through nested-object template | TestElementProperty / ObjectProperty |
| `Array` | array → goes through one of 3 sub-templates | CollectionProperty / TestElementProperty |
| `ARRAY_2D` | two-dimensional array → goes through nested-array template | CollectionProperty (nested) |

---

## 9. mountMode enum values

mountMode determines **how** a container/object is **attached to the parent element**. The three YAML spellings are equivalent (case-insensitive; both camelCase and underscore forms are supported):

| YAML spelling | Equivalent forms | Used by | Mounting mechanism |
|-----------|---------|------|---------|
| `TestElementProperty` | `TEST_ELEMENT_PROPERTY` / `test-element-property` | container-items / nested-object | Creates an independent container instance and attaches it to the parent via `TestElementProperty(propName, container)` |
| `self` | `SELF` | container-items | **The parent element is itself the container**; `containerAddMethod` is called directly on the parent (e.g. HeaderManager.headers) |
| `ObjectProperty` | `OBJECT_PROPERTY` | nested-object (non-TestElement) | Attached via `ObjectProperty(propName, obj)`, used for plain Java beans like SampleSaveConfiguration |

**Decision rules**:
- Is the parent element **itself** a container type? (HeaderManager is HeaderManager, CookieManager is CookieManager) → `self`
- Is the container an **independent TestElement** (Arguments, HTTPFileArgs)? → `TestElementProperty`
- Is the object a **non-TestElement** Java bean (SampleSaveConfiguration)? → `ObjectProperty`

---

## 10. setterOverride and naming derivation rules

The container-items / nested-object templates build the item/object via **no-arg constructor + setter**. The setter method name is derived in the following order:

### Derivation order

1. **Check the sub-field's `setterOverride`**: an explicit override declared on the field
2. **Default derivation**: `set` + drop the namespace (everything after the last `.`) + convert underscores to camelCase + capitalize the first letter

### Default derivation examples

| Field name | Drop namespace | Underscore → camelCase | setter |
|--------|-----------|-------------|--------|
| `Argument.name` | `name` | `name` | `setName` ✅ |
| `HTTPArgument.use_equals` | `use_equals` | `useEquals` | `setUseEquals` ✅ |
| `Cookie.path_specified` | `path_specified` | `pathSpecified` | `setPathSpecified` ✅ |
| `File.path` | `path` | `path` | `setPath` ✅ |

### Cases that require an explicit override

The following names **cannot be derived by the rules** and must declare a `setterOverride` on the sub-field:

| Field name | Actual setter | Why it's irregular |
|--------|------------|-----------|
| `Argument.desc` | `setDescription` | The field name is an abbreviation (desc → Description) |
| `Argument.metadata` | `setMetaData` | Mid-word capitalization (metadata → MetaData) |
| `HTTPArgument.always_encode` | `setAlwaysEncoded` | Inflection (encode → Encoded past participle) |
| `File.paramname` | `setParamName` | All-lowercase multi-word concatenation (paramname → ParamName) |
| `File.mimetype` | `setMimeType` | All-lowercase multi-word concatenation (mimetype → MimeType) |
| `xml` | `setAsXml` | Entirely non-standard |

### setterOverride spelling

**At the sub-field level** (written under a field in itemProperties / properties):

```yaml
itemProperties:
  - name: Argument.desc
    type: String
    setterOverride: setDescription
  - name: Argument.metadata
    type: String
    setterOverride: setMetaData
```

**At the parent-property level** (written on the outer Object property, **only effective with `mountMode: TestElementProperty`**): used to declare the method name the parent element uses to mount this nested object (by default `setProperty(TestElementProperty)` is used, but something like `ThreadGroup.main_controller` needs `setSamplerController`):

```yaml
- name: ThreadGroup.main_controller
  type: Object
  mountMode: TestElementProperty
  guiClass: org.apache.jmeter.control.gui.LoopControlPanel
  setterOverride: setSamplerController
```

> The semantics are consistent across both positions: **whichever property `setterOverride` appears on, it replaces that property's default setter**. The target object is determined by structural position (nested object → parent element; sub-field → item/nested-object instance).

---

## 11. Template decision tree

`handleBySchemaType` evaluates in the following order:

```
read propDef.type
│
├── STRING / INTEGER / LONG / FLOAT / DOUBLE / BOOLEAN / NUMBER
│   └─→ handleSimpleProperty(directly setProperty)
│
├── OBJECT
│   │   read propDef.mountMode
│   ├─→ ObjectProperty  ──→  handleNonTestElementObject(non-TestElement, e.g. SampleSaveConfiguration)
│   └─→ default (TestElementProperty) ──→  handleNestedObjectProperty(TestElement nesting, e.g. LoopController)
│
├── ARRAY
│   │   read propDef.className(testClass/class) / itemClass / itemProperties
│   ├─→ className != null        ──→  handleContainerItemsProperty(container-items template)
│   ├─→ itemClass + itemProperties both ──→  handleTestBeanTableProperty(testbean-table template)
│   └─→ neither                          ──→  handleGenericCollectionProperty(string-collection template)
│
└── ARRAY_2D
    └─→ handleNestedArrayProperty(nested-array template)
```

**Translated by "what you see in the GUI"** (a simplified decision for non-author users):

| What you see in the GUI | Which template to pick | Key fields |
|-------------|-----------|---------|
| A single text field / checkbox | simple property | `type: String/Integer/Boolean` |
| A table, each row being a set of fields | container-items / testbean-table | `type: Array`, pick `mountMode` based on whether the parent is the container |
| A nested panel (independent sub-component config area) | nested-object | `type: Object`, fill in `guiClass` for TestElement nesting |
| A list of strings (no field names) | string-collection | `type: Array`, do not fill `testClass`/`itemClass` |
| A two-dimensional table (both rows and columns are values) | nested-array | `type: ARRAY_2D` |

---

## 12. Complete examples of the 7 templates

### Template 1: container-items (Arguments family, TestElementProperty mounting)

Represents: `HTTPsampler.Arguments`, `Arguments.arguments` (BackendListener), `GitSampler.Arguments`, `SystemSampler.environment`

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
      default: ""    # under postBodyRaw mode, name defaults to an empty string
    - name: Argument.value
      type: String
      required: true
    - name: HTTPArgument.use_equals
      type: Boolean
      default: true
    - name: HTTPArgument.always_encode
      type: Boolean
      default: false
      setterOverride: setAlwaysEncoded    # <- irregular naming, declared on the field
    - name: Argument.metadata
      type: String
      default: "="
      setterOverride: setMetaData         # <- irregular naming, declared on the field
```

### Template 2: container-items (self mounting, parent element is the container)

Represents: `HeaderManager.headers`, `CookieManager.cookies`, `Arguments.arguments` (UserDefinedVariables)

```yaml
- name: HeaderManager.headers
  type: Array
  testClass: org.apache.jmeter.protocol.http.control.HeaderManager
  mountMode: self           # <- key: the parent element itself is the HeaderManager container
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

### Template 3: testbean-table (TestBean table)

Represents: `ValueAssertion.valuesCheckTable`, `VariableAssertion.variablesCheckTable`

Characteristics: **no** `testClass`, but has `itemClass` + `itemProperties`; the item is built via no-arg constructor + setter and mounted as a `CollectionProperty`.

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

### Template 4: nested-object (TestElement nesting)

Represents: `ThreadGroup.main_controller` (the 8 thread-group family), and every "nested sub-controller" scenario

```yaml
- name: ThreadGroup.main_controller
  type: Object
  testClass: org.apache.jmeter.control.LoopController
  guiClass: org.apache.jmeter.control.gui.LoopControlPanel    # <- required for TestElement
  setterOverride: setSamplerController                         # <- special setter on the parent
  properties:                                                  # <- note: this is properties, not itemProperties
    - name: LoopController.loops
      type: Integer
      default: 1
    - name: LoopController.continue_forever
      type: Boolean
      default: false
```

### Template 5: nested-object (non-TestElement, ObjectProperty mounting)

Represents: `ViewResultsTree.saveConfig`, `SummaryReport.saveConfig`, `AggregateReport.saveConfig`

```yaml
- name: saveConfig
  type: Object
  mountMode: ObjectProperty                                    # <- non-TestElement
  class: org.apache.jmeter.samplers.SampleSaveConfiguration    # <- non-TestElement bean uses class (maps to the JMX class attribute, not testclass)
  properties:
    - name: time
      type: Boolean
      default: true
    - name: xml
      type: Boolean
      default: false
      setterOverride: setAsXml                                 # <- non-standard setter, declared on the field
```

> Note: `SampleSaveConfiguration.responseDataOnError` / `assertionsResultsToSave` have no setters — do **not** declare these fields in the schema, otherwise `applySetter` will warn about not finding a setter (it does not affect other fields).

### Template 6: nested-array (ARRAY_2D)

Represents: `UserParameters.thread_values`, `ultimatethreadgroupdata`

```yaml
# pure string 2D array (no field names)
- name: UserParameters.thread_values
  type: ARRAY_2D
  innerItemType: String
  required: true

# Map input is extracted in itemProperties order (replaces the old hardcoded fieldOrder)
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

### Template 7: string-collection (plain string array, fallback)

Represents: `Asserion.test_strings`, `ModuleController.node_path`, `UserParameters.names`

Characteristics: type `Array` but **no** `testClass` / `itemClass`; stored directly as a string `CollectionProperty`.

```yaml
- name: UserParameters.names
  type: Array
  required: true
  description: List of parameter names
```

---

## 13. Adding a new component checklist

The complete workflow for adding a new JMeter component.

### Step 0: check whether the component is already in the registry

Component metadata (testClass/guiClass/default name) is now loaded by [`ElementRegistry`](src/main/java/org/gitee/jmeter/ai/utils/ElementRegistry.java) from two YAML sources: **components with a schema** read the `testClass`/`guiClass` from the `.schema.yaml`, and `name` serves as the default name; **components without a schema** are read from [`legacy-elements.yaml`](src/main/jmeter-agent/skills/jmeter/legacy-elements.yaml).

First look under [`references/`](src/main/jmeter-agent/skills/jmeter/references/) for the component's `.schema.yaml`, or search [`legacy-elements.yaml`](src/main/jmeter-agent/skills/jmeter/legacy-elements.yaml) for your elementType:

- **Already has a schema** → jump to step 2 to supplement/modify properties
- **Only in legacy-elements.yaml** → it is used solely for compatibility reverse-lookup and is not involved in AI creation; if you now want the AI to be able to create and configure it, continue to step 1 to write a full schema (after writing it you may delete that line from the legacy yaml to avoid duplication)
- **In neither** → brand-new component, continue to step 1

### Step 1: declare component metadata (pure YAML, zero Java changes)

You no longer need to modify `JMeterElementManager` (the old `ELEMENT_CLASS_MAP` static block and the `getDefaultNameForElement` switch have been deleted). Depending on whether AI needs to create and configure it, pick one of two options:

**A. The AI needs to be able to create and configure it** (the vast majority of cases): write a full `.schema.yaml`, filling in `testClass`/`guiClass` in the `component` section (required; missing fields cause the registry to skip with a warning):

```yaml
component:
  type: mynewsampler
  name: My New Sampler
  description: ...
  testClass: com.example.MyNewSampler
  guiClass: com.example.gui.MyNewSamplerGui
```

How to find `testClass`/`guiClass`: open the JMeter GUI, manually add the component → save a `.jmx` → in the XML, `<testelement class="...">` is the testClass, and that node's `guiclass` attribute is the guiClass; or look it up directly in the JMeter source (the model class + the corresponding `*Gui` class).

**B. Only needs registry recognition** (for `isNodeCompatible` compatibility reverse-lookup, not letting the AI create it): append one line under `elements` in [`legacy-elements.yaml`](src/main/jmeter-agent/skills/jmeter/legacy-elements.yaml):

```yaml
  - elementType: mynewsampler
    testClass: com.example.MyNewSampler
    guiClass: com.example.gui.MyNewSamplerGui
    defaultName: "My New Sampler"
    aliases: [mynewsampler2]   # optional
```

### Step 2: determine the property types involved

For each property, answer:
- Is it a simple scalar (String/Integer/Boolean)? → use the corresponding type
- Is it a nested object? → is it a TestElement or a plain bean?
- Is it an array? → what is the item inside the array? what is the container?

How to discover properties is in [§3](#3-how-to-discover-a-components-properties).

### Step 3: pick a template per the [§11 decision tree](#11-template-decision-tree)

### Step 4: fill in the schema

Refer to [§12 examples](#12-complete-examples-of-the-7-templates).

### Step 5: verify setter naming

For each itemProperties / properties sub-field, per [§10](#10-setteroverride-and-naming-derivation-rules), decide:
- Does default derivation yield the correct setter?
- If not → declare a `setterOverride` on that field

### Step 6: update the SKILL.md component index

Append one row to the corresponding category table in [`SKILL.md`](src/main/jmeter-agent/skills/jmeter/SKILL.md) (elementType / Description / Docs link / Schema link).

### Step 7: test

See [§14 testing and troubleshooting](#14-testing-and-troubleshooting).

---

## 14. Testing and troubleshooting

### 14.1 YAML syntax validation

YAML indentation / quoting errors will cause the entire schema to fail to load. Recommended:
- Open it in an IDE with a YAML plugin (VSCode + YAML, IntelliJ) — syntax errors are flagged directly
- `mvn test -Dtest=SchemaLoaderTest` iterates over and loads every schema; any parse failure is reported by this test case

### 14.2 Schema field type validation

`mvn test -Dtest=ComponentSchemaTypeTest` validates the legality of each schema's field types.

### 14.3 Agent call-layer verification

After authoring the schema, start the JMeter GUI, have the Agent invoke the `create_jmeter_element` tool to create this component and fill in a few fields, then:
- Check the JMeter test-plan tree to see whether the component was created successfully
- Right-click the component and inspect the property panel to see whether the field values were filled in correctly
- Check the JMeter logs for any `applySetter` warn about a missing setter (if so → setter naming issue, add `setterOverride` to that field)

### 14.4 Common error reference

| Symptom | Possible cause | Where to investigate |
|------|---------|---------|
| Agent reports "component type not found" | schema missing `testClass`/`guiClass` (skipped by registry), not included in legacy-elements.yaml, or elementType spelling mismatch | Declare metadata in step 1, ensure the schema `type` matches the queried elementType (mind aliases) |
| Component created but fields not filled in | setter name derivation wrong | Add `setterOverride` to that field, watch the log warn |
| YAML parse failure | indentation / quoting error | The location flagged by the IDE |
| Table fields render incorrectly | wrong `Array` template / wrong `mountMode` | Re-read the [§11 decision tree](#11-template-decision-tree) |
| Nested object properties have no effect | `Object` template missing `guiClass` (TestElement) / missing `mountMode: ObjectProperty` (non-TestElement) | Re-read [§12 template 4/5](#12-complete-examples-of-the-7-templates) |

---

## 15. Common pitfalls

### Pitfall 1: confusing field name vs property name

The value of the `name` field is the **Map key passed by the LLM**, not the JMeter internal property name. For example:
- LLM passes `{"Argument.name": "x"}` → schema field `name: Argument.name`
- JMeter actually stores it into TestElement.name (handled by Argument.setName)

The two are usually identical, but BackendListener is a counter-example: the LLM uses `Arguments.arguments`, while JMeter actually stores it into the `arguments` property (auto-discovered by `findExistingContainer`).

### Pitfall 2: setter ordering side effects

Some setters have side effects, so the order of `itemProperties` matters:
- `HTTPArgument.setUseEquals(true)` overwrites `metadata` → `Argument.metadata` must be listed after `HTTPArgument.use_equals`
- `HTTPFileArg.setPath(path)` triggers `detectMimeType` → `File.mimetype` must be listed after `File.path` (otherwise `setMimeType` overwrites the auto-detected value)

### Pitfall 3: postBodyRaw dual mode

Under postBodyRaw mode, `HTTPsampler.Arguments` has an empty name. Solution: set `default: ""` on `Argument.name` so an empty string is used when the LLM does not pass it.

### Pitfall 4: missing no-arg constructor

The container-items / testbean-table / nested-object templates all use **no-arg constructor + setter**. If the item class has no public no-arg constructor, it will fail at runtime. All JMeter TestElements have a no-arg constructor (by convention); custom classes must be confirmed manually.

### Pitfall 5: parse-failure protection

The container-items template has "parse-failure protection": when the user passes items but all fail to parse (not a Map, wrong type), it **skips the update to preserve existing data**. An empty array `[]`, by contrast, clears the container. These two have different semantics.

### Pitfall 6: clear logic when containerMount=self

Under `mountMode: self`, the code first clears the CollectionProperty corresponding to propName on the parent element, then calls `containerAddMethod` to add. If the parent element's item storage path differs from propName, the clear may be ineffective. HeaderManager / CookieManager both follow `propName == CollectionProperty name`, so they work correctly.

---

## References

- Code implementation: [SchemaBasedPropertyHandler.java](src/main/java/org/gitee/jmeter/ai/agent/tools/jmeter/property/SchemaBasedPropertyHandler.java)
- Schema model: [ComponentSchema.java](src/main/java/org/gitee/jmeter/ai/agent/validation/ComponentSchema.java)
- Schema loading: [ComponentSchemaLoader.java](src/main/java/org/gitee/jmeter/ai/agent/validation/ComponentSchemaLoader.java)
- Component registration: [ElementRegistry.java](src/main/java/org/gitee/jmeter/ai/utils/ElementRegistry.java) (loads schema + legacy-elements.yaml together)
- Component create/delete: [JMeterElementManager.java](src/main/java/org/gitee/jmeter/ai/utils/JMeterElementManager.java)
