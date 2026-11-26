# Supplemental binary inventory

This file records deterministic inventory facts extracted from the APK container and DEX class descriptor tables. It does not claim full bytecode decompilation.

## Archive verification

- Outer ZIP contains `1` APK file: `Claude.apk`.
- APK bytes match the extracted inspection copy: **yes**.
- SHA-256 of APK: `a969a66c8f3421437685bc7221e30a7eb1ed1ecc18e8e200491b22e290eb13f8`.

## APK archive entries

- Total entries: **1228**.
- Uncompressed aggregate size: **67,907,537 bytes**.
- Compressed aggregate size inside APK: **65,266,499 bytes**.

| Top-level path | Entry count |
|---|---:|
| `(root)` | 41 |
| `META-INF` | 23 |
| `assets` | 48 |
| `org` | 1 |
| `res` | 1115 |

Full path, uncompressed/compressed size, compression method, and directory flag are listed in `APK_FILE_INVENTORY.csv`.

## DEX class descriptor inventory

- DEX files: **6**.
- Class definitions across DEX files: **65,147**.
- Unique class descriptors under `com.anthropic.claude`: **1,388**.
- Full app-owned descriptor list: `ANTHROPIC_CLASS_INDEX.txt`.

Third-party classes are intentionally not expanded into the project source. DEX identifier strings and class descriptors are metadata, not Kotlin/Java source. Method bodies, control flow, Compose UI functions, and API contracts remain outside what this inventory can prove.
