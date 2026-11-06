# Property Recipes

Property names are JMeter's own, **verbatim and case-sensitive**. Both `create` and `update`
validate them against the component schema, so a misspelling is rejected with
`Unknown parameter(s) [...] ... Available parameters: [...]` — read that list, do not guess again.
`batch` behaves the same way.

Case trap: there are **two** containers spelled `HTTPsampler.*` with a lower-case `s` —
`HTTPsampler.Arguments` and `HTTPsampler.Files`. Every other HTTP property is `HTTPSampler.*`
(`domain`, `path`, `method`, `postBodyRaw`, `DO_MULTIPART_POST`). Writing `HTTPSampler.Files`
rejects the whole call, so a file upload silently never gets configured.

**Value types** (schema-driven, and strict — there is no numeric/string coercion):

| Field kind | Accepts | Rejects |
|---|---|---|
| Integer / Long | JSON number `5`, or a `${...}` reference | `"5"`, and `5` for a Double field |
| Double / Float | JSON number **with a decimal point** (`600.0`, `1500.0`), or `${...}` | the bare integer `600` / `1500` |
| Boolean | JSON `true` / `false` | `"true"` |
| Enum | one of the literals listed by the schema | anything else |
| String | a JSON **string** only | a bare number: `"ConstantTimer.delay": 500` is rejected, use `"500"` |

The Double row bites hardest: `RandomTimer.range` and `throughput` are Double, and JSON `800`
deserializes as an Integer, which the validator rejects. Always write `800.0`.

String-typed numeric fields exist — e.g. `ConstantTimer.delay` and
`JSONPostProcessor.match_numbers` are **String** — so `"500"` is correct there and `500` is
rejected. When in doubt let the error message tell you.

After any write, read it back: `jmeter-cli get --elementId <id> --includeProperties true --maxDepth 0`.

## Thread group

```json
{"ThreadGroup.num_threads":5,
 "ThreadGroup.ramp_time":10,
 "ThreadGroup.main_controller":{"LoopController.loops":10}}
```

Loop count is **not** a thread-group property — it lives in the embedded LoopController, passed as
a nested object. `-1` = forever. For a time-boxed run add `"ThreadGroup.scheduler":true` plus
`"ThreadGroup.duration":600`.

## HTTP requests

`create` auto-initializes an empty `HTTPsampler.Arguments` for `httpsampler` and `httpdefaults`;
supply it only when you actually send parameters.

**GET / query string**

```json
{"HTTPSampler.method":"GET","HTTPSampler.path":"/api/items",
 "HTTPsampler.Arguments":[
   {"Argument.name":"page","Argument.value":"1"},
   {"Argument.name":"size","Argument.value":"20"}]}
```

**POST form (`application/x-www-form-urlencoded`)** — same argument array, `method` = POST.

```json
{"HTTPSampler.method":"POST","HTTPSampler.path":"/api/login",
 "HTTPsampler.Arguments":[
   {"Argument.name":"username","Argument.value":"admin"},
   {"Argument.name":"password","Argument.value":"secret"}]}
```

**POST raw JSON body** — four things must agree: `postBodyRaw`, an **empty** `Argument.name`,
`always_encode:false`, and a **`Content-Type` header**. JMeter does not add one for a raw body
(`http.post_add_content_type_if_missing` is false by default), so without the header the request
goes out as `text/plain` and media-type-checking servers answer 415/400. Put the header on a
`headermanager` under the thread group:

```json
{"HeaderManager.headers":[{"Header.name":"Content-Type","Header.value":"application/json"}]}
```

```json
{"HTTPSampler.method":"POST","HTTPSampler.path":"/api/orders",
 "HTTPSampler.postBodyRaw":true,
 "HTTPsampler.Arguments":[
   {"Argument.name":"",
    "Argument.value":"{\"sku\":\"A-1\",\"qty\":2}",
    "HTTPArgument.always_encode":false}]}
```

If only some requests send JSON, put the `headermanager` **under the sampler** instead of under
the thread group — config elements apply to their own subtree plus every ancestor, so a sampler-
level manager overrides the shared one for that request only.

**Multipart file upload**

```json
{"HTTPSampler.method":"POST","HTTPSampler.path":"/api/upload",
 "HTTPSampler.DO_MULTIPART_POST":true,
 "HTTPsampler.Files":[
   {"File.path":"/abs/path/to/test.txt","File.paramname":"file",
    "File.mimetype":"text/plain"}],
 "HTTPsampler.Arguments":[{"Argument.name":"tenant","Argument.value":"default"}]}
```

`File.path` and `File.paramname` are required per item; `File.mimetype` defaults to
`application/octet-stream`. Every `HTTPsampler.Arguments` item requires `Argument.value` — omitting
it fails with `Missing required parameter in array item 0`.

## Shared defaults and headers

```json
{"HTTPSampler.protocol":"https","HTTPSampler.domain":"api.example.com",
 "HTTPSampler.port":443,"HTTPSampler.connect_timeout":5000,"HTTPSampler.response_timeout":10000}
```

(`httpdefaults`, placed once under the thread group.)

```json
{"HeaderManager.headers":[
  {"Header.name":"Content-Type","Header.value":"application/json"},
  {"Header.name":"Authorization","Header.value":"Bearer ${token}"}]}
```

(`headermanager` — an **array of objects**, not a map. Both item keys are required.)

## Assertions

**Response Assertion** — `Assertion.test_field` and `Assertion.test_type` are both required.

- `Assertion.test_field`: `Assertion.response_code` | `.response_data` | `.response_message` |
  `.response_headers` | `.request_headers` | `.request_data` | `.sample_label`
- `Assertion.test_type` is a **bitmask**: `1` regex whole-match · `2` contains-regex ·
  `8` equals (literal) · `16` substring (literal) · `4` Not · `32` Or. Combine by OR. Multiple
  patterns are AND-ed unless `32` is set.
- Patterns live in `Asserion.test_strings` (JMeter's historical missing `t`). The correct spelling
  `Assertion.test_strings` is also accepted (normalized at validation and write time) — either works.

```json
{"Assertion.test_field":"Assertion.response_code","Assertion.test_type":8,
 "Asserion.test_strings":["200"]}
```

Any-of statuses: `test_type` 34 (contains + Or) or a single regex `200|201` with `2`.

**JSON Path Assertion** — `JSON_PATH` required.

```json
{"JSON_PATH":"$.status","JSONVALIDATION":true,"EXPECTED_VALUE":"ok"}
```

## Extractors

**JSON** (child of the sampler)

```json
{"JSONPostProcessor.referenceNames":"orderId",
 "JSONPostProcessor.jsonPathExprs":"$.data.id",
 "JSONPostProcessor.match_numbers":"1",
 "JSONPostProcessor.defaultValues":"NOT_FOUND"}
```

**Regex** (child of the sampler) — `template` required. `match_number` is an **Integer**, not a
string (unlike `JSONPostProcessor.match_numbers`).

```json
{"RegexExtractor.refname":"sessionId","RegexExtractor.regex":"JSESSIONID=(.+?);",
 "RegexExtractor.template":"$1$","RegexExtractor.match_number":1,
 "RegexExtractor.default":"NOT_FOUND"}
```

**Tokens in response headers** (e.g. `X-Auth-Token`, `Set-Cookie`) are not in the body — set
`RegexExtractor.useHeaders:"true"` and match against the header text. Without it the regex never
matches and the variable silently keeps its `default`.

**Extraction scope** — `Sample.scope`: `parent` (the main sample, the default), `children`,
`all`, or `variable`. Only change it when the value lives in a sub-sample.

A no-match leaves the configured default in place (`NOT_FOUND` above), so a wrong correlation
surfaces as a literal `Bearer NOT_FOUND` rather than an empty value — check for that string when
a request fails auth.

Reference the result later as `${orderId}` / `${sessionId}` — **only inside the same thread group**
(see "Cross-thread-group values" below to share it).

## CSV Data Set Config — all nine properties are required

```json
{"filename":"/abs/path/to/users.csv","fileEncoding":"UTF-8",
 "variableNames":"username,password","ignoreFirstLine":true,"delimiter":",",
 "quotedData":false,"recycle":true,"stopThread":false,"shareMode":"shareMode.all"}
```

Omitting any one fails with `Missing required parameter '<name>'`. `shareMode` enum:
`shareMode.all` | `shareMode.group` | `shareMode.thread`.

## User Defined Variables — all four item keys are required

```json
{"Arguments.arguments":[
  {"Argument.name":"base_url","Argument.value":"https://api.example.com",
   "Argument.desc":"","Argument.metadata":"="}]}
```

## Timers

```json
{"ConstantTimer.delay":"500"}
```
```json
{"ConstantTimer.delay":"200","RandomTimer.range":800.0}
```

Timers inherit down the subtree: put **one** under the thread group, not one per sampler.

## Scripting (JSR223 + Groovy only)

```json
{"scriptLanguage":"groovy","script":"vars.put(\"ts\", String.valueOf(System.currentTimeMillis()))",
 "cacheKey":"false"}
```

`scriptLanguage` is required. Never use BeanShell elements — they are slower and effectively
deprecated, and the corresponding `beanshell*` keys exist only for legacy plans.

## Flow control / think time action

```json
{"ActionProcessor.action":1,"ActionProcessor.duration":"1000"}
```

`action`: 0 Stop · 1 Pause · 2 Stop Now · 3 Start Next Thread Loop · 4 Next Iteration · 5 Break.

## Cross-thread-group values

JMeter **variables** are scoped to their thread group; a value extracted in group A renders as the
literal `${token}` in group B. Promote it to a property:

- In group A, a JSR223 PostProcessor: `props.put("token", vars.get("token"))`
- In group B, read it as **`${__P(token,)}`** — the comma matters. `${__P(name)}` with **no
  default returns the literal string `1`**, not empty, so a missing value silently becomes
  `Authorization: Bearer 1`.

**Thread groups run concurrently by default.** The second group usually starts before the first
one finishes, so a value set in group A is often not yet set when group B reads it. If group B
depends on group A, set `TestPlan.serialize_threadgroups=true` on the Test Plan (or use
`setupthreadgroup` for the producer), or the correlation is a race you will not see in a passing
smoke run.

`run --properties '{"p_env":"test"}'` injections are readable the same way: `${__P(p_env,)}`.

## Assertion patterns worth copying

- **Any 2xx**: `test_field=Assertion.response_code`, `test_type=2` (contains **regex**), patterns
  `["2\\d\\d"]`. Do NOT use `test_type=16` with `["20"]` — `16` is literal *substring*, so `20`
  matches 200–209 only and fails 250/299.
- Body contains (literal substring): `test_field=Assertion.response_data`, `test_type=16`,
  patterns `["\"success\":true"]`.
- JSON structure: prefer `jsonpathassertion` with `JSON_PATH=$.data.id` over a regex on the body.
