# Asset pipeline (datahub-console)

Bundles and minifies front-end JS at build time — JVM only, **no Node**. A bundle's contents are
listed in a *manifest* with `//= require` directives; the build (Closure Compiler) turns that into
one minified file served at `/static/...`.

## How it works

- **Declare** a bundle once in [`assets.gradle`](assets.gradle) (`name`, `manifest`, `output`).
- **Build** it: `build` / `bootRun` / `bootJar` / `assemble` all run `buildAssets`, producing the
  bundle and a small `asset-bundles.properties` registry (under `build/`, git-ignored).
- **Include** it in a template via the fragment — never a raw `<script>`:
  ```html
  <th:block th:replace="~{fragments/assets :: js('rightForm')}"></th:block>
  ```
- **Dev vs prod:** `AssetService` reads the registry. Prod serves the one minified bundle; dev
  (`datahub.assets.unbundled=true`, set for the dev profile) serves the original source files, so
  edits show on refresh with **no rebuild**.

## Add a bundle

1. Create a manifest, e.g. `static/js/foo.manifest.js`, listing sources in load order:
   ```js
   //= require foo/base.js
   //= require foo/extra.js
   ```
2. Declare it once in `assets.gradle` → `assetBundles`:
   ```groovy
   [name: 'foo', type: 'js', manifest: 'js/foo.manifest.js', output: 'js/foo.bundle.min.js'],
   ```
3. Include it in the template: `~{fragments/assets :: js('foo')}`.

`assets.gradle` is the only place bundles are declared and manifests are parsed; the Java side
never changes.

## Good to know

- **JS uses Closure `SIMPLE`, not `ADVANCED`** — form classes are referenced by global name from
  inline template scripts, which ADVANCED would rename and break.
- **CSS** works identically (`type: 'css'`, a `*.manifest.css`), but no CSS bundle is declared yet.
- The one current bundle is **right-form** (7 files → `right-form.bundle.min.js`).
  `base_form_abstract.js` is required first because the other forms extend its classes.

## Build manually

```bash
./gradlew :datahub-console:buildAssets   # build all bundles + the registry
```
