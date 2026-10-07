#!/usr/bin/env python3
from pathlib import Path
import re
import sys

ROOT = Path(sys.argv[1] if len(sys.argv) > 1 else "decoded")
FEMALE = ["baya", "kseniya", "xenia"]

def find_one(name):
    hits = list(ROOT.rglob(name))
    if not hits:
        raise SystemExit(f"{name} not found")
    return hits[0]

def female_list_body(signature):
    return f'''.method {signature}
    .locals 3

    const/4 v0, 0x3
    new-array v0, v0, [Ljava/lang/String;

    const/4 v1, 0x0
    const-string v2, "baya"
    aput-object v2, v0, v1

    const/4 v1, 0x1
    const-string v2, "kseniya"
    aput-object v2, v0, v1

    const/4 v1, 0x2
    const-string v2, "xenia"
    aput-object v2, v0, v1

    invoke-static {{v0}}, Ljava/util/Arrays;->asList([Ljava/lang/Object;)Ljava/util/List;
    move-result-object v0
    return-object v0
.end method'''

# 1) Baya is the default both in Speaker.DEFAULT and fresh SharedPreferences.
for filename in ("Speaker$Companion.smali", "Speaker.smali", "Prefs.smali"):
    for p in ROOT.rglob(filename):
        s = p.read_text(errors="ignore")
        before = s
        if filename.startswith("Speaker"):
            # Kotlin const DEFAULT may be emitted as a field rather than
            # a const-string inside a method.
            s = s.replace('DEFAULT:Ljava/lang/String; = "xenia"', 'DEFAULT:Ljava/lang/String; = "baya"')
            s = s.replace('const-string v0, "xenia"', 'const-string v0, "baya"')
            s = s.replace('const-string v1, "xenia"', 'const-string v1, "baya"')
            s = s.replace('const-string v2, "xenia"', 'const-string v2, "baya"')
            s = s.replace('const-string v3, "xenia"', 'const-string v3, "baya"')
        else:
            # Prefs voice getter default.
            s = s.replace('"voice", "xenia"', '"voice", "baya"')
            # Smali stores the two const strings on separate lines, so also
            # replace xenia in this class only.
            s = s.replace('"xenia"', '"baya"')
        if s != before:
            p.write_text(s)

# 2) Replace the central Speaker.names(Set,List) implementation. Both Android
# TTS onGetVoices and the app's own voice picker flow through this function.
companion = find_one("Speaker$Companion.smali")
s = companion.read_text()
pattern = re.compile(
    r'(?ms)^\.method (?P<sig>[^\n]* names\(Ljava/util/Set;Ljava/util/List;\)Ljava/util/List;)\n.*?^\.end method'
)
m = pattern.search(s)
if not m:
    raise SystemExit("Speaker.names(Set,List) method not found in smali")
replacement = female_list_body(m.group("sig"))
s = s[:m.start()] + replacement + s[m.end():]

# 3) Direct-speech secondary voice picker also stays female-only.
pattern2 = re.compile(
    r'(?ms)^\.method (?P<sig>[^\n]* sameEngine\([^\n]*\)Ljava/util/List;)\n.*?^\.end method'
)
m2 = pattern2.search(s)
if m2:
    replacement2 = female_list_body(m2.group("sig"))
    s = s[:m2.start()] + replacement2 + s[m2.end():]

companion.write_text(s)

# 4) Make the app name unmistakable.
for values in ROOT.glob("res/values*/strings.xml"):
    text = values.read_text(errors="ignore")
    text2 = re.sub(
        r'(<string name="app_name"[^>]*>).*?(</string>)',
        r'\1RuVoice Female\2',
        text,
        count=1,
    )
    if text2 != text:
        values.write_text(text2)

# Sanity checks.
joined = "\n".join(p.read_text(errors="ignore") for p in ROOT.rglob("Speaker$Companion.smali"))
for voice in FEMALE:
    if f'"{voice}"' not in joined:
        raise SystemExit(f"female voice missing after patch: {voice}")

print("PATCHED: Baya default; exposed voices = Baya, Kseniya, Xenia")
