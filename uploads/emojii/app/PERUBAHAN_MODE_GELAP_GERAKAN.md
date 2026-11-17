
## Pemecahan MainActivity (langkah 2)
`decl()` dan deklarasi alat bawaan dipindah ke ToolDeclarations.kt (`decl`, `buildBaseTools(desk)`).
MainActivity.baseTools kini `by lazy { buildBaseTools(desk) }`; `tools` (gabungan dengan alat buatan agen) tetap di MainActivity.
ToolGuidanceTest membaca ToolDeclarations.kt. MainActivity.kt: 936 -> 846 baris.
