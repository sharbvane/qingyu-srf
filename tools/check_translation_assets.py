"""Run with Python 3; validates the shipped offline phrase translations."""
from pathlib import Path

asset = Path(__file__).resolve().parents[1] / "app/src/main/assets/translation/phrase-gloss.tsv"
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
print(f"PASS: {len(phrases)} complete Chinese-English offline translations; other languages require a downloaded model")
