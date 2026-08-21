# datahub-api-model

The **lean, framework-free wire-contract** module. It owns the request/response DTOs,
form models and envelopes that make up the `datahub-api` REST contract — the types
referenced by the Feign `DatahubApi` interface — and nothing else.

It is the new **bottom of the build graph**, below `datahub-commons`:

```
datahub-api-model
      ▲
datahub-commons ──► datahub-infra / datahub-api / datahub-console / consumers
```

`datahub-commons` depends on it with `api`, so it re-exposes these types transitively.
Every existing `import ai.intellistream.datahub.…` across the platform keeps resolving
unchanged — the extraction moved files between modules but kept their package names.

The point of the split: a Java SDK can depend on this module to share the exact server
types **without** inheriting the server stack (Spring, OpenFeign, Vault, Pulsar, JPA).

## What's inside

- **API envelopes** — `api/responses/`: `DataWrapper`, `GraphDataWrapper`,
  `DataCollection`, `DataRetriever`.
- **Domain DTOs** — `models/`, `resource/`, `timeseries/`, `label/`: `Resource`,
  `EdgeProxy`, `Timeseries`, `DataSetModel`, `Policy`/`PolicyType`, `EventModel`,
  stream/namespace/topic forms, `UnitModel`, `TenantFeatures`, …
- **Request & update forms** — `*Form` / `*Retreiver` / `*Search` request models, the
  partial-update field abstractions in `helpers/updates/` and the `*Fields` holders.
- **Custom validators** — Jakarta Bean Validation constraints in `validation/` and
  `models/validation/` (`AtLeastOneNotNull`, `OneIdNotNull`, `RelationshipTypeNotNull`,
  `AllowedAggregates`, …).
- **Self-contained helpers** the DTOs need — `helpers/datetime/DateTimeHandler`,
  `helpers/text/` and the Jackson (de)serializers in `json/` and `validation/`.

## Dependency policy

The produced artifact is **framework-free**. Allowed: only zero-/tiny-transitive
contract jars.

| Dependency | Why | Scope |
|---|---|---|
| `com.fasterxml.jackson.core:jackson-databind` (Jackson 2) | JSON mapping, custom (de)serializers | `api` |
| `tools.jackson.dataformat:jackson-dataformat-xml` (Jackson 3) | one `@JacksonXmlElementWrapper` on `DataWrapper` | `compileOnly` |
| `jakarta.validation:jakarta.validation-api` | constraint annotations + custom validators | `api` |
| `io.swagger.core.v3:swagger-annotations-jakarta` | `@Schema` on DTOs | `api` |
| `net.openhft:zero-allocation-hashing` | xxHash on `Resource`/`IdCollection`/`PolicyType` | `api` |
| `org.slf4j:slf4j-api` | one `@Slf4j` helper | `api` |
| Lombok | accessors/builders | compile-time only |

Explicitly **absent**: Spring, OpenFeign, Vault, Pulsar, JPA, the JDK-HttpClient
wrappers. The Spring Boot plugins in `build.gradle` are applied **only** for
dependency-version management (the shared BOM, as in `datahub-commons`); they add no
Spring code to the artifact, and `bootJar` is disabled.

### XML is server-only

Only `DataWrapper` carries a single Jackson-3 XML annotation, used by the web apps that
serve `application/xml`. It is `compileOnly` here so consumers (a future SDK) don't drag
in woodstox; `datahub-commons` keeps `jackson-dataformat-xml` at runtime for the server.

### Two decouplings vs. the pre-extraction code

To keep the module framework-free, two server-side couplings were removed
(behaviour-preserving):
- `org.springframework.validation.ObjectError` → `FieldValidationError`, a tiny
  dependency-free holder with the same `getObjectName()` / `getDefaultMessage()` surface
  used by the `*Fields` validators (the discarded `codes`/`arguments` were never read).
- `PolicyType.getExternalId()` inlines `LongHashFunction.xx3().hashChars(…)` instead of
  calling `IdGenerator.xxHash(…)`, which is exactly the same computation — so the module
  needs neither `IdGenerator` nor its UUID/commons-codec dependencies.
