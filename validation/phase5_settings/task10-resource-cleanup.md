# Phase 5 Settings — App Resource Cleanup Evidence

Date: 2026-08-28

The app resource cleanup used the feature's default resource names as the
candidate set: 681 strings plus five plurals (686 names total). A source scan
covered app Kotlin, Java, manifest/layout XML, and non-catalog resource XML
references. Forty candidate names remain consumed by app code (shared
navigation/dashboard/dialog labels and platform error labels); those entries
remain app-owned. The other 646 candidate names had no app consumer and were
removed from the app default catalog, all 24 translated `strings.xml` catalogs,
and their supplemental `strings_missing.xml` catalogs.

The feature catalogs retain the exact app translations, including values from
supplemental locale files. The feature and app default catalogs now match for
all 686 candidate resources; translated-resource comparison also reports zero
mismatches.

## Verification

```text
gradlew.bat :feature:settings:lintDebug :feature:settings:testDebugUnitTest :app:compileDebugKotlin :app:testDebugUnitTest --no-daemon --console=plain --warning-mode=none
BUILD SUCCESSFUL in 2m 41s
```

The post-cleanup app resource merge and both unit-test suites pass. The
configuration-cache warning for the boundary task and existing Kotlin compiler
warnings remain unrelated to this cleanup.

DAO/concrete dependency cleanup remains a separate open gate in the
transitional ledger.

