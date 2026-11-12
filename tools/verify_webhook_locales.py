"""Verify playback webhook resources for every advertised Android locale."""

from pathlib import Path
import re, xml.etree.ElementTree as ET

root = Path(__file__).resolve().parents[1]
res = root / "feature/settings/src/main/res"


def values(path):
    result = {}
    for file in path.glob("*.xml"):
        for node in ET.parse(file).getroot():
            if node.tag == "string" and node.attrib.get("name", "").startswith(
                "webhook_"
            ):
                key = node.attrib["name"]
                assert key not in result, (path, key, "duplicate")
                result[key] = "".join(node.itertext())
    return result


base = values(res / "values")
assert base
locales = ET.parse(root / "app/src/main/res/xml/locales_config.xml").getroot()
for node in locales:
    locale = node.attrib["{http://schemas.android.com/apk/res/android}name"]
    folder = (
        "values" if locale == "en" else "values-" + locale.replace("zh-TW", "zh-rTW")
    )
    translated = values(res / folder)
    assert translated.keys() == base.keys(), (
        locale,
        base.keys() - translated.keys(),
        translated.keys() - base.keys(),
    )
    for key, text in translated.items():
        assert text.strip(), (locale, key, "empty")
        assert "ZXQ" not in text, (locale, key, "untranslated marker")
        assert sorted(re.findall(r"%\d+\$[ds]", text)) == sorted(
            re.findall(r"%\d+\$[ds]", base[key])
        ), (locale, key, "placeholder")
print(f"{len(base)} webhook keys verified in {len(locales)} advertised locales")
