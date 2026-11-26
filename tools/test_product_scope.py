"""Structural regression checks for intentionally removed product features.
Not Android UI instrumentation; prevents accidental re-exposure of removed commands."""
from pathlib import Path
base=Path("app/src/main")
java=(base/"java/br/app/koma/Reader.java").read_text()
js=(base/"assets/paginas/reader.js").read_text()
css=(base/"assets/paginas/pages.css").read_text()
for token in ("offlineStart","offlineChunk","offlineMessage(","confirmDownload(","autoEnd","toggleControls"):
    assert token not in java and token not in js, token
assert "koma-doubletap" not in css and "koma-doubletap" not in js
assert "startImages(false)" in js
assert "Downloads antigos" in java
assert 'String[] labels={"Estante","Fontes","Histórico","Ajustes"}' in java
assert "frame.addView(bar" not in java
assert "if(!manga.forSource(s).isEmpty())" in java
assert "width>=500" in java
print("Escopo 7.2: comandos retirados, navegação e preservação de obras conferidos.")
