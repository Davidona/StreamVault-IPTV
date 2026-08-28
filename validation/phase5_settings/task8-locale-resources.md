# Phase 5 Settings — Locale Resource Extraction Evidence

Date: 2026-08-28

The Settings feature now carries the translated resource catalogs for the
existing feature-owned default catalog. The extraction was generated from the
app locale files by matching resource names against
`feature/settings/src/main/res/values/strings.xml` and
`feature/settings/src/main/res/values/plurals.xml`.

## Catalog coverage

- 24 locale directories were populated: `ar`, `cs`, `da`, `de`, `el`, `es`,
  `fi`, `fr`, `hu`, `in`, `it`, `iw`, `ja`, `ko`, `nb`, `nl`, `pl`, `pt`,
  `ro`, `ru`, `sv`, `tr`, `uk`, `vi`, and `zh`.
- Each locale contains 681 feature string resources and five feature plural
  resources.
- Existing app translations are retained verbatim where present. For keys
  without an app translation, the feature's default English value is copied so
  the runtime fallback remains unchanged.
- Locale plural files carry the existing app plural text and use a scoped
  `tools:ignore="MissingQuantity"` on the resource container. This preserves
  the app's current one/other fallback behavior for locales whose source
  catalog does not define every CLDR quantity.
- App locale files were not deleted. Their cleanup remains gated on the
  resource-usage audit and on proving that no app-owned consumer needs the
  shared keys.

## Verification

```text
gradlew.bat :feature:settings:lintDebug --no-daemon --console=plain --warning-mode=none
BUILD SUCCESSFUL in 1m 22s
```

The feature lint run completes with the complete locale set and no errors.
The existing warnings/hints are unchanged; no new baseline was added.

