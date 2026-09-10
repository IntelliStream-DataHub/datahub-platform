# Running DataHub in IntelliJ IDEA

This covers running the apps from IntelliJ's own Spring Boot run configurations.
For the Gradle + local-stack route (Docker compose, Vault seeding, `application-local.yml`),
see [GETTING_STARTED.md](GETTING_STARTED.md).

## Run configurations

Create one **Spring Boot** run configuration per app (Run > Edit Configurations > `+` > Spring Boot):

| Name              | Main class                                                   | Port |
|-------------------|--------------------------------------------------------------|------|
| API               | `ai.intellistream.datahub.api.ApiDatahubApplication`         | 8081 |
| Stateless consumer| `ai.intellistream.datahub.DatahubStatelessConsumerApplication` | none |
| Console           | `ai.intellistream.dhconsole.DatahubConsoleApplication`       | 8080 |
| File cleanup      | `ai.intellistream.datahub.DatahubFileCleanupApplication`     | none |

For each: set **Active profiles** to `dev,local`, which runs against the local Docker stack
from GETTING_STARTED.md. That is the profile to use unless you have been given something else.
Plain `dev` on its own expects a separately hosted Vault, which is a maintainer arrangement and
not part of a normal checkout.

## Set the Working directory (required for the console)

> **Required.** Set the run configuration's **Working directory** to the module folder, e.g.
> `$PROJECT_DIR$/datahub-console` (or browse to `<repo>/datahub-console`).
> `$MODULE_WORKING_DIR$` also works.

For a Gradle multi-module project, IntelliJ leaves the working directory unset and defaults
it to the **repo root**, not the module. The console's `dev` profile points Thymeleaf and the
static resource handler at the source tree with **relative** paths
(`datahub-console/src/main/resources/application-dev.properties`):

```properties
spring.thymeleaf.prefix=file:src/main/resources/templates/
spring.web.resources.static-locations=file:src/main/resources/static/,classpath:/...
```

`file:src/...` is resolved against the process working directory. From the repo root it becomes
`<repo>/src/main/resources/templates/`, which does not exist, and the app fails with:

```
Error resolving template [timeseries/index], template might not exist or might not be accessible...
```

Pointing the working directory at `datahub-console` makes both the templates and the unbundled
JS/CSS resolve. (`./gradlew :datahub-console:bootRun` already runs from the module directory, which
is why the Gradle launch in GETTING_STARTED.md does not hit this.)

Only the console uses these `file:` paths, so it is the one that breaks without the right working
directory. Setting it on every config anyway is good practice.

## Vault credentials (dev profile)

The `dev` profile reads the Vault AppRole secret id from an environment variable. Add it under
**Environment variables** in the run configuration:

```
VAULT_SECRET-ID=<your dev AppRole secret id>
```

Copy `datahub-console/src/main/resources/application-dev.properties.example` to
`application-dev.properties` (git-ignored) and set `vault.role-id` there; `vault.address` is
already filled in.

## Live reload

The console is set up for fast edit-refresh in dev:

- `spring-boot-devtools` is on the classpath and restarts on a recompile.
- `datahub.assets.unbundled=true` serves the original (unminified) JS/CSS from source instead of
  the built bundle. See [datahub-console/ASSETS.md](datahub-console/ASSETS.md).
- The `file:` template/static paths plus `spring.thymeleaf.cache=false` mean template and asset
  edits show on a browser refresh without a rebuild, **once the working directory is correct**.
- i18n messages (`I18nDevConfig`) load from `file:src/main/resources/i18n/messages` with a 1s
  cache, so translation edits also show on refresh. Like the template/static paths, this needs the
  datahub-console working directory.

To pick up Java changes, set the run config's **On frame deactivation** to *Update classes and
resources* (or trigger Build > Build Project), so switching away from the IDE hot-reloads.

## Sharing run configs

Run configurations live in `.idea/workspace.xml`, which is git-ignored and local to your machine.
To share a correct config (working directory and all) with the team, save it as a project file:
in the run configuration dialog tick **Store as project file**, which writes a
`.run/<name>.run.xml` that can be committed.
