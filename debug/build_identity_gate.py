#!/usr/bin/env python3
"""Check startup order, optional labels, and SDK selection in fresh JVMs."""
from pathlib import Path
import os
import subprocess
import tempfile

repo = Path(__file__).resolve().parents[1]
java_home = Path(os.environ["JAVA_HOME"])
classpath = os.pathsep.join(str(repo / "runtime/build/lib" / name)
                          for name in ("android-framework.jar", "macapk-loader.jar"))
base = os.environ.copy()
for key in tuple(base):
    if key.startswith("MACAPK_DEVICE_") or key.startswith("MACAPK_PAIRIP_"):
        del base[key]
base["MACAPK_SDK_INT"] = "34"
cases = [
    ("default", {}, ("Mac.apk", "Voltare", "Mac.apk")),
    ("phone", {"BRAND": "samsung", "MANUFACTURER": "samsung", "MODEL": "SM-A525F"},
     ("samsung", "samsung", "SM-A525F")),
    ("model-only", {"MODEL": "Test Phone"}, ("Mac.apk", "Voltare", "Test Phone")),
    ("empty", {"BRAND": " ", "MODEL": "", "MANUFACTURER": "\t"},
     ("Mac.apk", "Voltare", "Mac.apk")),
    ("invalid", {"BRAND": "bad/brand", "MODEL": "bad\nmodel", "MANUFACTURER": "x" * 92},
     ("Mac.apk", "Voltare", "Mac.apk")),
    ("native-length", {"MODEL": "\U0001f4f1" * 16}, ("Mac.apk", "Voltare", "Mac.apk")),
]
with tempfile.TemporaryDirectory(prefix="macapk-identity-") as temporary:
    subprocess.run([str(java_home / "bin/javac"), "-cp", classpath, "-d", temporary,
                    str(repo / "debug/BuildIdentityTest.java")], check=True)
    count = 0
    for name, labels, expected in cases:
        env = base | {"MACAPK_DEVICE_" + key: value for key, value in labels.items()}
        for first in ("build", "properties"):
            subprocess.run([str(java_home / "bin/java"), "-cp", temporary + os.pathsep + classpath,
                            "BuildIdentityTest", first, *expected, "34", "14"], env=env, check=True)
            count += 1
        print("PASS " + name, flush=True)
    # The identity labels must not force a phone's original Android/API version.
    env = base | {"MACAPK_SDK_INT": "36", "MACAPK_DEVICE_BRAND": "samsung",
                  "MACAPK_DEVICE_MANUFACTURER": "samsung", "MACAPK_DEVICE_MODEL": "SM-A525F"}
    subprocess.run([str(java_home / "bin/java"), "-cp", temporary + os.pathsep + classpath,
                    "BuildIdentityTest", "build", "samsung", "samsung", "SM-A525F", "36", "16"],
                   env=env, check=True)
    print(f"PASS: {count + 1} JVM runs")
