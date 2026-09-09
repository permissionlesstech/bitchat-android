# Design-spec icons

These standalone SVGs were extracted from the supplied 393 px Figma screen exports. The matching
Android vector resources in `res/drawable/ic_spec_*.xml` are the runtime copies used by Compose.
Paths and stroke weights remain faithful to the exports; UI tint and opacity are applied at the
call site so selected and disabled states remain theme-aware.
