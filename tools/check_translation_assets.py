"""Run with Python 3; validates the shipped offline phrase translations."""
from pathlib import Path

assets = Path(__file__).resolve().parents[1] / "app/src/main/assets"
asset = assets / "translation/phrase-gloss.tsv"
phrases = {}
for number, line in enumerate(asset.read_text(encoding="utf-8").splitlines(), 1):
    if not line.strip() or line.startswith("#"):
        continue
    fields = line.split("\t")
    assert len(fields) == 2 and all(field.strip() for field in fields), (number, fields)
    assert fields[0] not in phrases, f"Duplicate phrase: {fields[0]}"
    phrases[fields[0]] = fields[1:]

assert phrases["开发"] == ["develop"]
assert phrases["我们明天去北京"] == [
    "we are going to Beijing tomorrow"
]
assert "我们后天坐飞机去上海" not in phrases  # Unknown full sentences require the model, never concatenated word glosses.
assert all(not any("\u3040" <= character <= "\u30ff" for character in field) for row in phrases.values() for field in row)
# Keep the default resources bounded: optional language dictionaries/models never enter the source asset tree.
assert sorted(path.name for path in (assets / "translation").rglob("*") if path.is_file()) == [
    "phrase-gloss.tsv", "zh-en-details.db", "zh-en.db"
]
assert all(not any(part in {"de", "ru", "es", "ja", "fr", "en_de", "de_en", "en_ru", "en_es", "en_ja", "en_fr"}
                   for part in path.relative_to(assets).parts) for path in assets.rglob("*"))
print(f"PASS: {len(phrases)} complete Chinese-English offline translations; ja/fr/de/ru/es require a downloaded model; no optional-language assets")
