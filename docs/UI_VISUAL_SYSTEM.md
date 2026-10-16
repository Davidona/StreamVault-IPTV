# Browse visual system

Browse UI uses `BrowseVisualStyle`, `BrowseColors`, and `BrowseShapes` from `core:ui`. The original `AppPalette` remains the source of theme identity. Classic Blue, M3 Purple, and Light keep their existing accents and theme IDs; browsing derives canvas depth, surface tones, focus color, and readable foregrounds from each palette.

The application navigation root enables this style for browsing. Settings, parental settings, and the player root retain the original theme. Live-only player chrome opts into the browse style separately. Keep the composition tree stable when switching styles so remembered navigation and focus state survive.

| Element | Treatment |
| --- | --- |
| Controls and artwork | 10 dp corners |
| Panels and navigation bar | 16 dp corners, subtle outline |
| Hero surfaces | 20 dp corners, restrained theme tint |
| Remote focus | 2 dp accent ring, 1.035 scale where scaling is appropriate |
| Media artwork | Crop to fill; themed monogram fallback |
| Solid accent actions | `browsePrimaryButtonColors()` or `contentOnColor()` |
| Metadata | Theme-derived ink tested against emphasized surfaces at 4.5:1 contrast |
| Video preview placeholders | White ink on the black video canvas in every theme |

Keep navigation order, category ordering, screen structure, card dimensions, and callbacks with their existing owners. Style changes should not move features or change focus routing.

Use `browseBackdrop()` for ambient screen backgrounds and `browsePanel()` for layered panels. Browse text and surfaces should read from `BrowseColors`; compatibility imports under `design.browse` expose the older color names. Shared widgets use `LocalBrowseVisuals` to retain their original rendering in settings and VOD controls. Avoid changing the global `AppColors` palette to restyle a single screen.

Add future theme presets through `AppTheme` and `AppPalette`. The browse system derives its styling without a separate widget implementation for each preset. Primary ink adapts to the accent luminance; translucent selection surfaces use the theme's normal text colors instead.
