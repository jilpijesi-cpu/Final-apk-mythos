#!/bin/sh
# Uji PdfText dengan JDK saja (tanpa Gradle/Kotlin): kompilasi PdfText.java lalu jalankan PdfTextCheck.
# Pemakaian: sh tools/uji-pdf/run.sh   (dari akar proyek). Perlu javac (JDK), bukan hanya JRE.
set -e
cd "$(dirname "$0")/../.."
OUT="${TMPDIR:-/tmp}/pdftext-uji"
rm -rf "$OUT"; mkdir -p "$OUT"
javac -d "$OUT" app/src/main/java/com/build/mythos/cc/PdfText.java tools/uji-pdf/PdfTextCheck.java
java -Dfile.encoding=UTF-8 -cp "$OUT" PdfTextCheck
