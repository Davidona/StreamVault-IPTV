# Phase 5 System — Task 9 resource cleanup

Date: 2026-09-04

## Decision

System presentation owns the 30 keys used only by Welcome and Downloads.
`app_name`, `nav_downloads`, and `settings_cancel` remain shared app
resources because their consumers are outside `:feature:system`.

The feature catalog preserves the app's locale fallback convention. For each
localized qualifier, translated values remain in `strings.xml` and existing
untranslated fallback values remain in `strings_missing.xml`.

## Parity audit

The 33-key Task 0 inventory was compared for the default locale and all 25
localized qualifiers (26 qualifiers total). The comparison used the app
catalog at the extraction boundary and the final feature catalog.

```text
qualifiers_checked=26
app_locales_missing_from_feature=
feature_locales_missing_from_app=
duplicate_system_entries=0
missing_system_values=0
value_mismatches=0
placeholder_mismatches=0
```

Formatting placeholders, including `%1$d` in
`sync_items_indexed_format` and `%1$s` in
`downloads_delete_confirm_msg`, match across every checked locale.

## App cleanup

Removed from app catalogs because the consumer scan found no retained
production use:

```text
download_delete download_folder_change download_folder_default download_resume
downloads_delete_confirm_delete downloads_delete_confirm_msg
downloads_delete_confirm_title downloads_deleted downloads_empty_hint
downloads_empty_title downloads_item_title downloads_loading downloads_no_thumb
downloads_resumed downloads_status_cancelled downloads_status_completed
downloads_status_downloading downloads_status_failed downloads_status_paused
downloads_status_pending sync_items_indexed_format sync_section_live
sync_section_series sync_section_vod welcome_loading_subtitle welcome_loading_title
welcome_setup_later welcome_setup_provider welcome_subtitle welcome_tagline
```

Retained shared app resources:

```text
app_name nav_downloads settings_cancel
```

The feature's filtered catalog passes `:feature:system:lintDebug`; the app
resource merge, Kotlin compilation, unit tests, and debug assembly also pass
after cleanup.
