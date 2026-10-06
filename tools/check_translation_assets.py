"""Run with Python 3; validates the shipped offline phrase translations."""
from pathlib import Path

asset = Path(__file__).resolve().parents[1] / "app/src/main/assets/translation/phrase-gloss.tsv"
phrases = {}
for number, line in enumerate(asset.read_text(encoding="utf-8").splitlines(), 1):
    if not line.strip() or line.startswith("#"):
        continue
    fields = line.split("\t")
    assert len(fields) == 4 and all(field.strip() for field in fields), (number, fields)
    assert fields[0] not in phrases, f"Duplicate phrase: {fields[0]}"
    phrases[fields[0]] = fields[1:]

assert phrases["开发"] == ["develop", "開発", "développer"]
assert phrases["我们明天去北京"] == [
    "we are going to Beijing tomorrow", "私たちは明日北京に行きます", "nous allons à Pékin demain"
]
assert "我们后天坐飞机去上海" not in phrases  # Unknown full sentences require the model, never concatenated word glosses.
print(f"PASS: {len(phrases)} complete offline translations in English, Japanese and French")
