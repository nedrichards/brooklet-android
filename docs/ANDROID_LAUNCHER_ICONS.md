# Android launcher icon assets

Phone and Wear use matching adaptive launcher layers generated from the
project-owned SVG artwork in `artwork/launcher-icon/`. Android applies each
device's mask and provides wallpaper-derived colours for the monochrome layer.
The layers use a 108 × 108 dp canvas and keep their artwork within the central
66 dp safe circle.

`foreground.svg`, `background.svg`, and `dev-badge.svg` are the editable source
art. The ordinary monochrome SVG contains the two page paths; the debug variant
also retains a cutout code badge so debug builds remain identifiable with
themed icons.

From the repository root, regenerate the Android VectorDrawable/adaptive XML,
phone bitmap fallbacks, monochrome SVGs, Play listing image, and labelled review
sheet with:

```sh
python3 scripts/generate_launcher_icons.py
python3 scripts/generate_launcher_icons.py --check
```

The generator uses Python 3's standard library and ImageMagick 7 with SVG and
WebP support. It intentionally accepts only plain filled SVG paths. Phone and
Wear share generated launcher resources; the phone's five density-specific
bitmap fallbacks are kept in sync with the adaptive artwork. The separate Play
asset is an opaque, full-square 512 × 512 PNG; it has no launcher mask baked in.

Both application manifests use the adaptive layers for the launcher and other
system application-icon surfaces. Android 12 and later use the application
icon as the default system splash icon. The Wear Tile preview remains a separate
representation of inbox content. Neither app currently posts notifications.

The generator can also create an annotated vector review sheet in
`artwork/launcher-icon/preview.png`. It is illustrative, not a device capture.
Device-specific launcher, system, and watch rendering still needs physical
device review.

See [asset provenance](ASSET_PROVENANCE.md) for the artwork's origin and
licence.
