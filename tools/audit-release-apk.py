"""Audit a completed release; writes versioned package reports, never the APK.

Usage: python tools/audit-release-apk.py [final.apk] [previous.apk]
Version and expected paths come from app/build.gradle. No Android device needed.
"""
import hashlib
import gzip
import io
import json
import re
import sqlite3
import struct
import subprocess
import sys
import zipfile
from datetime import datetime, timezone
from pathlib import Path
from PIL import Image

ROOT = Path(__file__).resolve().parents[1]
gradle = (ROOT / "app/build.gradle").read_text(encoding="utf-8-sig")
version = re.search(r"versionName\s+'([^']+)'", gradle).group(1)
version_code = int(re.search(r"versionCode\s+(\d+)", gradle).group(1))
expected_apk = ROOT / "releases" / f"Qingyu-{version}.apk"
apk = Path(sys.argv[1]).resolve() if len(sys.argv) > 1 else expected_apk
previous = []
for path in (ROOT / "releases").glob("Qingyu-*.apk"):
    match = re.fullmatch(r"Qingyu-(\d+\.\d+\.\d+)\.apk", path.name)
    if match and tuple(map(int, match.group(1).split("."))) < tuple(map(int, version.split("."))):
        previous.append((tuple(map(int, match.group(1).split("."))), path))
baseline = Path(sys.argv[2]).resolve() if len(sys.argv) > 2 else max(previous)[1]
assert apk.is_file() and baseline.is_file()
assert apk == expected_apk, "Audit the final Release artifact, not a Debug/intermediate package."
out = ROOT / "releases/qa" / ("v" + version)
out.mkdir(parents=True, exist_ok=True)
sdk = ROOT / ".tools/android-sdk/build-tools/35.0.0"
lines = [f"Qingyu v{version} final static APK audit", "UTC: " + datetime.now(timezone.utc).isoformat(), "APK: " + str(apk)]

def digest(data):
    return hashlib.sha256(data).hexdigest()

def run(args):
    result = subprocess.run(args, capture_output=True, text=True, encoding="utf-8", errors="replace")
    lines.append(result.stdout + result.stderr)
    assert result.returncode == 0, (args, result.returncode)
    return result.stdout + result.stderr

raw = apk.read_bytes()
sha = digest(raw)
assert apk.with_suffix(".apk.sha256").read_text(encoding="utf-8-sig").split()[0].lower() == sha
baseline_raw = baseline.read_bytes()
baseline_sha = digest(baseline_raw)
baseline_version = baseline.stem.removeprefix("Qingyu-")
baseline_report = ROOT / "docs" / f"release-package-v{baseline_version}.json"
if baseline_report.is_file():
    previous_report = json.loads(baseline_report.read_text(encoding="utf-8-sig"))
    assert baseline_sha == previous_report["sha256"] and len(baseline_raw) == previous_report["bytes"], "Previous release changed."
saved_bytes = len(baseline_raw) - len(raw)
saved_percent = saved_bytes * 100 / len(baseline_raw)
lines += ["Bytes: " + str(len(raw)), "SHA-256: " + sha,
          f"Previous release: {baseline.name}, {len(baseline_raw)} bytes, SHA-256 {baseline_sha}",
          f"Size reduction: {saved_bytes} bytes ({saved_percent:.2f}%)"]
signer = ["cmd", "/d", "/c", str(sdk / "apksigner.bat"), "verify", "--verbose", "--print-certs"]
new_cert = re.search(r"certificate SHA-256 digest: (\w+)", run(signer + [str(apk)])).group(1)
old_cert = re.search(r"certificate SHA-256 digest: (\w+)", run(signer + [str(baseline)])).group(1)
signing_policy = json.loads((ROOT / "docs/release-signing-policy.json").read_text(encoding="utf-8-sig"))
assert new_cert == signing_policy["release_certificate_sha256"] != signing_policy["legacy_certificate_sha256"], "Release must use the independent production key."
assert old_cert in {new_cert, signing_policy["legacy_certificate_sha256"]}, "Unrecognized previous release signer."
assert signing_policy["rotation_min_sdk"] == 28 and signing_policy["legacy_max_sdk"] == 27
platform_signers = {}
for low, high, expected in [(26, 27, signing_policy["legacy_certificate_sha256"]), (28, 35, new_cert)]:
    cert = re.search(r"certificate SHA-256 digest: (\w+)", run(signer + ["--min-sdk-version", str(low), "--max-sdk-version", str(high), str(apk)])).group(1)
    assert cert == expected, "Wrong platform-specific APK signer."
    platform_signers[f"{low}-{high}"] = cert
lineage_text = run(["cmd", "/d", "/c", str(sdk / "apksigner.bat"), "lineage", "--in", str(apk), "--print-certs", "-v"])
assert new_cert in lineage_text and signing_policy["legacy_certificate_sha256"] in lineage_text, "Missing proof-of-rotation."
assert len(re.findall(r"Has installed data capability:\s*true", lineage_text)) == 2
assert len(re.findall(r"Has rollback capability\s*:\s*false", lineage_text)) == 2
run([str(sdk / "zipalign.exe"), "-c", "-P", "16", "4", str(apk)])
badging = run([str(sdk / "aapt2.exe"), "dump", "badging", str(apk)])
assert f"versionCode='{version_code}'" in badging and f"versionName='{version}'" in badging
assert re.search(r"(?m)^(?:minSdkVersion|sdkVersion):'26'", badging) and "targetSdkVersion:'35'" in badging
assert "application-debuggable" not in badging
manifest = run([str(sdk / "aapt2.exe"), "dump", "xmltree", str(apk), "--file", "AndroidManifest.xml"])
for marker in ["BIND_INPUT_METHOD", "QingyuImeService", "android.view.InputMethod", "android.view.im", "REQUEST_INSTALL_PACKAGES", "com.qingyu.ime.updates", "grantUriPermissions"]:
    assert marker in manifest, marker

metadata = {"apk": apk.relative_to(ROOT).as_posix(), "variant": "release", "version_code": version_code, "version_name": version, "bytes": len(raw), "sha256": sha,
            "min_sdk": 26, "target_sdk": 35, "debuggable": False,
            "certificate_sha256": new_cert, "platform_signers": platform_signers, "signing_rotation": signing_policy,
            "baseline": baseline.relative_to(ROOT).as_posix(), "zipalign_16k": True,
            "baseline_bytes": len(baseline_raw), "baseline_sha256": baseline_sha,
            "size_reduction_bytes": saved_bytes, "size_reduction_percent": saved_percent,
            "native": {}, "assets": {}, "badges": {}, "upstream_limitations": []}
with zipfile.ZipFile(apk) as archive:
    names = set(archive.namelist())
    libs = [entry for entry in archive.infolist() if entry.filename.startswith("lib/") and entry.filename.endswith(".so")]
    assert {entry.filename.split("/")[1] for entry in libs} == {"arm64-v8a", "armeabi-v7a", "x86_64"}
    assert len(libs) == 6
    for entry in libs:
        name = entry.filename
        blob = archive.read(name)
        assert blob[:4] == b"\x7fELF" and blob[5] == 1
        bits = blob[4]
        if bits == 2:
            phoff = struct.unpack_from("<Q", blob, 32)[0]
            size, count = struct.unpack_from("<HH", blob, 54)
            header = [struct.unpack_from("<IIQQQQQQ", blob, phoff + i * size) for i in range(count)]
            segments = [(p[0], p[2], p[3], p[6], p[7], p[1]) for p in header]
        else:
            phoff = struct.unpack_from("<I", blob, 28)[0]
            size, count = struct.unpack_from("<HH", blob, 42)
            header = [struct.unpack_from("<IIIIIIII", blob, phoff + i * size) for i in range(count)]
            segments = [(p[0], p[1], p[2], p[5], p[7], p[6]) for p in header]
        loads = [p for p in segments if p[0] == 1]
        relro_ends = [p[2] + p[3] for p in segments if p[0] == 0x6474e552]
        lengths = struct.unpack_from("<HH", raw, entry.header_offset + 26)
        data_offset = entry.header_offset + 30 + sum(lengths)
        assert entry.compress_type == zipfile.ZIP_STORED and data_offset % 16384 == 0, name
        if bits == 2 or name.endswith("libqingyu_pinyin.so"):
            assert all(p[4] >= 16384 and (p[1] - p[2]) % 16384 == 0 for p in loads), name
        if name.endswith("libqingyu_pinyin.so"):
            assert relro_ends and all(end % 16384 == 0 for end in relro_ends), name
        # The linker protects whole pages. Padding is safe only if those pages
        # contain no executable or writable LOAD bytes outside the RELRO.
        if bits == 2:
            for relro in (p for p in segments if p[0] == 0x6474e552):
                start, end = relro[2], relro[2] + relro[3]
                protected_start, protected_end = start // 16384 * 16384, (end + 16383) // 16384 * 16384
                assert any(load[2] <= start and load[2] + load[3] >= end for load in loads), name
                for load in loads:
                    low, high = max(protected_start, load[2]), min(protected_end, load[2] + load[3])
                    assert low >= high or not (load[5] & 1 or load[5] & 2 and (low < start or high > end)), name
        detail = {"bytes": len(blob), "sha256": digest(blob), "zip_data_offset": data_offset,
                  "load_alignment": [p[4] for p in loads], "relro_ends": relro_ends,
                  "relro_page_protection_safe": bits == 2 or name.endswith("libqingyu_pinyin.so")}
        metadata["native"][name] = detail
        lines.append(name + " " + json.dumps(detail))
    source_assets = set()
    for source in sorted((ROOT / "app/src/main/assets").rglob("*")):
        if not source.is_file():
            continue
        name = "assets/" + source.relative_to(ROOT / "app/src/main/assets").as_posix()
        expected = source.read_bytes()
        if source.suffix == ".gz":
            name = name[:-3]
            expected = gzip.decompress(expected)  # AAPT expands gzip source assets.
        source_assets.add(name)
        blob = archive.read(name)
        assert blob == expected, name
        metadata["assets"][name] = {"bytes": len(blob), "sha256": digest(blob)}
    # AGP's compiled ART startup profiles are build output, not language models.
    metadata["build_generated_assets"] = {}
    for name, folder in [("baseline.prof", "binary_art_profile"), ("baseline.profm", "binary_art_profile_metadata")]:
        packed_name = "assets/dexopt/" + name
        compiled = ROOT / "app/build/intermediates" / folder / "release/compileReleaseArtProfile" / name
        blob = archive.read(packed_name)
        assert blob == compiled.read_bytes(), packed_name
        source_assets.add(packed_name)
        metadata["assets"][packed_name] = {"bytes": len(blob), "sha256": digest(blob)}
        metadata["build_generated_assets"][packed_name] = compiled.relative_to(ROOT).as_posix()
    assert {name for name in names if name.startswith("assets/") and not name.endswith("/")} == source_assets, "Unaccounted bundled assets."
    phrase_rows = [line.split("\t") for line in archive.read("assets/translation/phrase-gloss.tsv").decode("utf-8-sig").splitlines() if line.strip() and not line.startswith("#")]
    assert phrase_rows and all(len(row) == 2 and all(row) for row in phrase_rows), "Only Chinese/English phrase columns belong in default resources."
    assert dict(phrase_rows)["开发"] == "develop"
    assert "assets/input/input-v2.db" not in names and "assets/input/input-v3.db" in names
    assert not any(re.search(r"(?:^|/)(?:ja|fr|de|ru|es|japanese|french|german|russian|spanish)(?:[./_-]|$)", name, re.I) for name in source_assets)
    metadata["default_language_resources"] = {"languages": ["zh", "en"], "chinese_english_phrases": len(phrase_rows),
        "optional_model_files_bundled": False, "note": "Japanese/French/German/Russian/Spanish files download on demand; the shared SDK library supports multiple languages."}
    assert archive.read("assets/licenses/GPL-3.0.txt") == (ROOT / "LICENSE").read_bytes()
    resource_dump = subprocess.check_output([str(sdk / "aapt2.exe"), "dump", "resources", str(apk)], text=True, encoding="utf-8", errors="replace")
    for name in ["google_translate_badge.png", "google_translate_badge_dark.png"]:
        packed = re.search(r"drawable/" + re.escape(Path(name).stem) + r"\s+\(nodpi\) \(file\) (\S+)", resource_dump).group(1)
        source = (ROOT / "app/src/main/res/drawable-nodpi" / name).read_bytes()
        optimized = archive.read(packed)
        original_image, packed_image = Image.open(io.BytesIO(source)).convert("RGBA"), Image.open(io.BytesIO(optimized)).convert("RGBA")
        assert original_image.size == packed_image.size == (528, 48) and original_image.tobytes() == packed_image.tobytes()
        detail = {"packed_path": packed, "source_sha256": digest(source), "packed_sha256": digest(optimized),
                  "source_byte_match": source == optimized, "rgba_pixel_match": True, "dimensions": [528, 48]}
        metadata["badges"][name] = detail
        lines.append("Official badge " + name + " " + json.dumps(detail) + " (AAPT lossless PNG optimization)")
    input_manifest = json.loads((ROOT / "third_party/input-data/manifest.json").read_text(encoding="utf-8-sig"))
    for name, expected in input_manifest["outputs"].items():
        assert metadata["assets"]["assets/input/" + name] == expected, name
    with sqlite3.connect((ROOT / "app/src/main/assets/input/input-v3.db").as_uri() + "?mode=ro", uri=True) as database:
        assert database.execute("PRAGMA user_version").fetchone()[0] == 3
        chinese_count = database.execute("SELECT count(*) FROM chinese").fetchone()[0]
        tagged_count = database.execute("SELECT count(*) FROM chinese WHERE pos<>''").fetchone()[0]
        tag_counts = dict(database.execute("SELECT pos,count(*) FROM chinese WHERE pos<>'' GROUP BY pos"))
        assert chinese_count == input_manifest["chinese_words"]
        assert tagged_count == input_manifest["chinese_words_with_pos"]
        assert tag_counts == input_manifest["pos_tags"]
        assert {word: tag for word, tag in database.execute("SELECT text,pos FROM chinese WHERE text IN ('项目','开发','美丽','非常','的')")} == {"项目":"n","开发":"v","美丽":"ns","非常":"d","的":"uj"}
    metadata["input_data"] = {"schema": 3, "chinese_words": chinese_count, "chinese_words_with_pos": tagged_count, "part_of_speech_counts": tag_counts}
    pinyin_manifest = json.loads((ROOT / "third_party/pinyinime/model-v2-manifest.json").read_text(encoding="utf-8-sig"))
    for item in [pinyin_manifest["output"], pinyin_manifest["lexicon"]]:
        packed_name = "assets/" + item["path"].removeprefix("app/src/main/assets/")
        assert metadata["assets"][packed_name] == {"bytes": item["bytes"], "sha256": item["sha256"]}, packed_name
    assert archive.read("assets/licenses/Rime-ice-LICENSE") == (ROOT / "third_party/pinyinime/Rime-ice-LICENSE").read_bytes()
    with sqlite3.connect((ROOT / "app/src/main/assets/pinyin/lexicon-v2.db").as_uri() + "?mode=ro", uri=True) as lexicon:
        assert lexicon.execute("PRAGMA integrity_check").fetchone()[0] == "ok"
        assert lexicon.execute("SELECT count(*) FROM words").fetchone()[0] == pinyin_manifest["lexicon"]["readings"]
        for code, word in [("anzhuo", "安卓"), ("dangang", "单杠")]:
            assert lexicon.execute("SELECT 1 FROM words WHERE raw=? AND text=?", (code, word)).fetchone(), (code, word)
        for field, code in [("raw", "dangang"), ("initials", "kd")]:
            plan = " ".join(str(row[-1]) for row in lexicon.execute(f"EXPLAIN QUERY PLAN SELECT text,pinyin,weight FROM words WHERE {field}=? ORDER BY weight DESC LIMIT 64", (code,)))
            assert "USING INDEX" in plan and "TEMP B-TREE" not in plan, plan
    metadata["chinese_pinyin"] = pinyin_manifest
    t9_manifest = json.loads((ROOT / "third_party/pinyinime/t9-v1-manifest.json").read_text(encoding="utf-8-sig"))
    t9_source = (ROOT / t9_manifest["path"]).read_bytes()
    assert {"bytes": len(t9_source), "sha256": digest(t9_source)} == {"bytes": t9_manifest["bytes"], "sha256": t9_manifest["sha256"]}
    assert t9_manifest["source_sha256"] == pinyin_manifest["lexicon"]["sha256"]
    t9_binary = gzip.decompress(t9_source)
    magic, schema, nodes, readings, syllables = struct.unpack_from(">5i", t9_binary)
    assert magic == 0x51595439 and schema == 1
    assert readings == t9_manifest["readings"] == pinyin_manifest["lexicon"]["readings"]
    assert nodes == t9_manifest["nodes"] and syllables == t9_manifest["syllables"]
    offset = 20
    for _ in range(syllables):
        size = struct.unpack_from(">H", t9_binary, offset)[0]
        assert 1 <= size <= 6 and re.fullmatch(b"[a-z]+", t9_binary[offset+2:offset+2+size])
        offset += 2 + size
    assert len(t9_binary) - offset == t9_manifest["primitive_array_bytes"] == 14 * nodes + 8 + 8 * readings
    assert metadata["assets"]["assets/pinyin/t9-v1.bin"] == {"bytes": len(t9_binary), "sha256": digest(t9_binary)}
    metadata["chinese_t9"] = t9_manifest
    context_manifest = json.loads((ROOT / "third_party/input-data/chinese-context/manifest.json").read_text(encoding="utf-8-sig"))
    context_source = (ROOT / "app/src/main/assets/input/chinese-context-v1.bin.gz").read_bytes()
    assert {"bytes": len(context_source), "sha256": digest(context_source)} == {"bytes": context_manifest["model_bytes"], "sha256": context_manifest["model_sha256"]}
    context_binary = gzip.decompress(context_source)
    assert metadata["assets"]["assets/input/chinese-context-v1.bin"] == {"bytes": len(context_binary), "sha256": digest(context_binary)}
    assert context_manifest["training_split"] == "train only" and context_manifest["source_license"] == "CC BY-SA 4.0"
    assert archive.read("assets/licenses/UD-GSDSimp-LICENSE.txt") == (ROOT / "third_party/input-data/chinese-context/UD-GSDSimp-LICENSE.txt").read_bytes()
    metadata["chinese_context"] = context_manifest
    for manifest_name, asset in [("manifest.json", "zh-en.db"), ("details-manifest.json", "zh-en-details.db")]:
        provenance = json.loads((ROOT / "third_party/cedict" / manifest_name).read_text(encoding="utf-8-sig"))
        assert metadata["assets"]["assets/translation/" + asset]["sha256"] == provenance["database_sha256"], asset
    lines.append("Input provenance manifest present; source/packed bytes compared for all " + str(len(metadata["assets"])) + " assets.")

    # DEX tables verify native method flags/names, not merely unrelated string matches.
    native_methods = set()
    for name in sorted(names):
        if not re.fullmatch(r"classes\d*\.dex", name):
            continue
        dex = archive.read(name)
        assert dex.startswith(b"dex\n")
        def uleb(offset):
            value = shift = 0
            while True:
                byte = dex[offset]; offset += 1
                value |= (byte & 127) << shift
                if byte < 128:
                    return value, offset
                shift += 7
                assert shift < 35
        count, offset = struct.unpack_from("<II", dex, 56)
        strings = []
        for index in range(count):
            item = struct.unpack_from("<I", dex, offset + index * 4)[0]
            _, start = uleb(item)
            strings.append(dex[start:dex.index(0, start)].decode("utf-8", errors="replace"))
        count, offset = struct.unpack_from("<II", dex, 64)
        types = [strings[struct.unpack_from("<I", dex, offset + index * 4)[0]] for index in range(count)]
        methods_count, methods_offset = struct.unpack_from("<II", dex, 88)
        class_count, class_offset = struct.unpack_from("<II", dex, 96)
        for index in range(class_count):
            fields = struct.unpack_from("<IIIIIIII", dex, class_offset + index * 32)
            if types[fields[0]] != "Lcom/qingyu/core/NativeDecoder;":
                continue
            at = fields[6]
            sizes = []
            for _ in range(4):
                size, at = uleb(at); sizes.append(size)
            for _ in range(sizes[0] + sizes[1]):
                _, at = uleb(at); _, at = uleb(at)
            for size in sizes[2:]:
                method_index = 0
                for _ in range(size):
                    delta, at = uleb(at); flags, at = uleb(at); _, at = uleb(at)
                    method_index += delta
                    assert method_index < methods_count
                    class_index, _, string_index = struct.unpack_from("<HHI", dex, methods_offset + method_index * 8)
                    assert types[class_index] == "Lcom/qingyu/core/NativeDecoder;"
                    if flags & 0x100:
                        native_methods.add(strings[string_index])
    source = (ROOT / "core/src/main/java/com/qingyu/core/NativeDecoder.java").read_text(encoding="utf-8-sig")
    expected_native = set(re.findall(r"\bnative\s+[\w\[\]]+\s+(\w+)\(", source))
    assert native_methods == expected_native, (native_methods, expected_native)
    mapping_path = ROOT / "app/build/outputs/mapping/release/mapping.txt"
    mapping = mapping_path.read_text(encoding="utf-8-sig")
    block = re.search(r"(?m)^com\.qingyu\.core\.NativeDecoder -> ([^:]+):\n((?:[ #].*\n)*)", mapping)
    assert block and block.group(1) == "com.qingyu.core.NativeDecoder", "R8 renamed the JNI class."
    for method in expected_native:
        # R8 omits unchanged native methods without Java code/line mappings.
        # DEX native flags/names above are definitive; any mapping entry must agree.
        mapped = re.findall(r"\b" + re.escape(method) + r"\([^\n]*\) -> (\S+)", block.group(2))
        assert all(name == method for name in mapped), (method, mapped)
        symbol = ("Java_com_qingyu_core_NativeDecoder_" + method).encode("ascii")
        for abi in ("arm64-v8a", "armeabi-v7a", "x86_64"):
            assert symbol + b"\0" in archive.read(f"lib/{abi}/libqingyu_pinyin.so"), (abi, method)
    metadata["r8_jni"] = {"mapping_sha256": digest(mapping_path.read_bytes()), "class_name": "com.qingyu.core.NativeDecoder", "native_methods": sorted(native_methods), "actual_dex_native_flags_verified": True, "three_abi_jni_symbols_verified": True}
    lines.append("R8 mapping, actual DEX native flags/names, and all three native symbol tables preserve " + str(len(native_methods)) + " JNI methods.")

assert digest(baseline.read_bytes()) == baseline_sha, "Previous release changed during audit."
lines += ["STATIC_PACKAGE_CHECKS_PASS", f"Independent Release signer with authenticated rotation from the v0.4 signer on API 28+; API 26-27 retain the compatibility signer; previous release preserved; all self-built native libraries align LOAD and RELRO to 16KiB.",
          "64-bit model libraries pass LOAD and rounded-RELRO safety checks; no ARM64 16KiB physical-device runtime is claimed.",
          "This report does not assert UI/model/real-device test success."]
(out / "final-package-audit.txt").write_text("\n".join(lines), encoding="utf-8")
(out / "release-package.json").write_text(json.dumps(metadata, indent=2, ensure_ascii=False), encoding="utf-8")
(ROOT / "docs" / f"release-package-v{version}.json").write_text(json.dumps(metadata, indent=2, ensure_ascii=False), encoding="utf-8")
print("STATIC_PACKAGE_CHECKS_PASS", sha, len(raw), new_cert)
print(f"SIZE_REDUCTION {saved_bytes} bytes ({saved_percent:.2f}%) versus {baseline.name}")
print(str(out / "final-package-audit.txt"))
