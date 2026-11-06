# Element Type Index

`--elementType` takes a **normalized registry key**: lowercase, with spaces and hyphens removed
(`httpsampler`, `headermanager`, `responseassertion`). It is never a Java class name and never a
JMeter display name.

## Two vocabularies — the most common round-trip mistake

`tree`, `get` and `parse` report `elementType` as the **Java class simple name**; every
`--elementType` argument wants the **registry key**:

| `tree` shows | `create`/`find` wants | Notes |
|---|---|---|
| `HTTPSamplerProxy` | `httpsampler` | also accepts `httprequest` |
| `ThreadGroup` | `threadgroup` | `setupthreadgroup` / `teardownthreadgroup` are separate keys |
| `ConfigTestElement` | `httpdefaults` | same class also backs other config elements |
| `TestAction` | `flowcontrolaction` | pause / stop / next-loop |
| `GenericController` | `simplecontroller` | |
| `LoopController` | `loopcontroller` | also the thread group's embedded controller |
| `Arguments` | `userdefinedvariables` | |
| `CookieManager` | `cookiemanager` | |
| `ResultCollector` | `summaryreport` / `aggregatereport` / `viewresultstree` | same class, distinguished by GUI class |
| `JSONPostProcessor` | `jsonpostprocessor` | |
| `RegexExtractor` | `regexextractor` | |

Copying a type from `tree` into `create --elementType` fails with `Unknown elementType`. When you
need something both vocabularies agree on, use `elementId`.

## `testplan` is not a type

The Test Plan node is not in the registry. Get its id from the root of
`jmeter-cli tree --includeProperties false --maxDepth 0`.

## Where elements hang

```
Test Plan                       ← root id from `tree --maxDepth 0`
└── Thread Group                ← threadgroup / setupthreadgroup / teardownthreadgroup
    ├── HTTP Request Defaults   ← httpdefaults   (config: applies to the whole subtree)
    ├── Header Manager          ← headermanager  (config: applies to the whole subtree)
    ├── Cookie Manager          ← cookiemanager
    ├── CSV Data Set Config     ← csvdataset     (config: applies to the whole subtree)
    ├── User Defined Variables  ← userdefinedvariables
    ├── Timer                   ← constanttimer / uniformrandomtimer (applies down the subtree)
    └── HTTP Request            ← httpsampler
        ├── Response Assertion  ← responseassertion   (CHILD of the sampler it checks)
        ├── JSON Extractor      ← jsonpostprocessor   (CHILD of the sampler it reads)
        ├── Regex Extractor     ← regexextractor      (CHILD of the sampler it reads)
        └── JSR223 PostProcessor← jsr223postprocessor
```

Rule of thumb: **scope-wide config and timers go once under the thread group; assertions and
extractors go under the sampler they act on.** Config elements and timers apply to **every sampler
in their own subtree and in all descendant paths** — so a Header Manager or CSV Data Set Config
sits at thread-group level and covers all the requests below it. Putting a second one under a
single sampler adds to the inherited set for that request only.

Assertions and extractors are the opposite: they bind to the sampler they are a child of.

**Cookies are opt-in.** Without a `cookiemanager` in the plan JMeter keeps no cookie store at all
and sends no `Cookie` header — the session cookie from a login request is discarded and every
later request is unauthenticated. If the task involves logging in, add
`create --elementType cookiemanager --parentId <tgId>`. (Clear it between virtual users with
`CookieManager.clearEachIteration` if you need per-user sessions.)

## Creatable types (the common ones)

75 of the 166 registry keys have a bundled schema, which is what `create` requires. This table
lists the ones an external agent actually reaches for; every type below is creatable. Types with a
schema that are **not** listed here still work — check the plugin's `jmeter` skill references, or
just try `create` and read the error.

| Key | Purpose | Required properties |
|---|---|---|
| `threadgroup` / `setupthreadgroup` / `teardownthreadgroup` | virtual users; loop count lives in the embedded `ThreadGroup.main_controller` | — |
| `httpsampler` | HTTP request | — (see `property-recipes.md` for bodies) |
| `httpdefaults` | shared protocol/host/port/path | — |
| `headermanager` | shared headers | — (items need `Header.name`, `Header.value`) |
| `cookiemanager` | cookie handling | — |
| `csvdataset` | CSV parameterization | **all nine** — see `property-recipes.md` |
| `userdefinedvariables` | plan variables | — (items need all four `Argument.*` keys) |
| `responseassertion` | status/body assertions | `Assertion.test_field`, `Assertion.test_type` |
| `jsonpathassertion` | JSONPath assertions | `JSON_PATH` |
| `jsonautoassertion` | Gitee-QA JSON assertion | `jsonStr` |
| `jsonpostprocessor` | JSON extraction | `JSONPostProcessor.referenceNames`, `.jsonPathExprs` |
| `regexextractor` | regex extraction | `RegexExtractor.refname`, `.regex`, `.template` |
| `htmlextractor` / `xpathassertion` / `xmlassertion` | HTML/XML extraction and assertions | see schemas |
| `jsr223sampler` / `jsr223preprocessor` / `jsr223postprocessor` / `jsr223assertion` | Groovy scripting | `scriptLanguage` |
| `constanttimer` | fixed think time | `ConstantTimer.delay` (a **string**) |
| `uniformrandomtimer` | random think time | `ConstantTimer.delay` (string), `RandomTimer.range` (number) |
| `constantthroughputtimer` / `precisethroughputtimer` | pacing | see schemas |
| `loopcontroller` / `ifcontroller` / `whilecontroller` / `foreachcontroller` / `dowhilecontroller` | control flow | `LoopController.loops` / `IfController.condition` / `WhileController.condition` / `ForeachController.inputVal`, `.returnVal` |
| `transactioncontroller` / `simplecontroller` / `onceonlycontroller` / `randomcontroller` / `casecontroller` / `includecontroller` / `modulecontroller` / `testfragmentcontroller` | structure and reuse | — |
| `flowcontrolaction` | pause / stop / next loop | `ActionProcessor.action` |
| `debugsampler` / `debugpostprocessor` | debugging | — |
| `summaryreport` / `aggregatereport` / `viewresultstree` | listeners | — |
| `backendlistener` | stream metrics to a backend | `classname` (enum of four FQCNs), `arguments` (array) |
| `steppingthreadgroup` / `ultimatethreadgroup` | stepped / per-row load ramp (3rd-party Custom Thread Groups) | see schemas |
| `jdbcsampler` / `jdbcdatasource` / `osprocesssampler` / `s3sampler` / `gitsampler` / `sshsftpsampler` / `sshcommandsampler` | protocol samplers | see schemas |

## Types that CANNOT be created (91 keys)

These keys are **registered** (so `find`/`query`/`parse` accept them) but have no bundled schema,
so `create` rejects them with `Unknown elementType: '...'. No schema definition found.` — which
does **not** mean JMeter lacks the type.

Common ones:

`durationassertion`, `sizeassertion`, `htmlassertion`, `md5assertion`, `jmespathassert`,
`xmlschemaassertion`, `xpathextractor`, `boundaryextractor`, `jmespathextractor`,
`switchcontroller`, `throughputcontroller`, `randomordercontroller`, `interleavecontroller`,
`runtimecontroller`, `criticalsectioncontroller`, `recordingcontroller`, `counterconfig`,
`randomvariable`, `authmanager`, `httpauthorizationmanager`, `cachemanager`, `dnscachemanager`,
`sampletimeout`, `synctimer`, `jsr223timer`, `jsr223listener`, `beanshelllistener`,
`resultaction`, `ftprequest`, `tcpsampler`, `smtpsampler`, `mailreadersampler`,
`javarequest`, `junitrequest`, `boltrequest`, `mongodbscript`, `ajpsampler`,
`graphqlhttprequest`, `ldaprequest`, `jmspublisher`, `jmssubscriber`, `accesslogsampler`,
and the visualizer / statistics-listener families.

Note: the JSON extractor is `jsonpostprocessor` (creatable). There is **no** `jsonpathextractor` key
at all — that name is neither registered nor creatable; use `jsonpostprocessor`.

Workarounds, in order of preference:

1. `copy` a functionally similar element and `update` its properties.
2. Ask the human to add it in the GUI — the plan is live and GUI edits are picked up immediately.
3. `jmeter-cli agent "add a Duration Assertion of 2000 ms to the sampler named X"`.

## Full property lists

If the plugin's own `jmeter` skill is installed alongside this one, each creatable type has a
`references/{native,gitee-qa,third-party}/<category>/<Name>.schema.yaml` with every property, its
type, enum, default and required flag. Otherwise inspect a live element of that type with
`get --elementId <id> --includeProperties true`, or `copy` one that already works.
