"""Validate named runtime presets in fresh processes, including explicit overrides."""
import os
from pathlib import Path
import subprocess
import tempfile
repo = Path(__file__).resolve().parent.parent
source = r'''
#include "macapk/compat_profile.h"
#include <cstdio>
int main() {
 macapk::ApplyCompatibilityProfile();
 for (const char* k : {"MACAPK_SYSCALL_TEXT_SHADOW", "MACAPK_NO_GUEST_TEXT_PATCH", "MACAPK_RAW_SYSCALL_REDIRECT", "MACAPK_MOCK_SENSORS"})
   printf("%s=%s\n", k, getenv(k) ? getenv(k) : "unset");
}
'''
with tempfile.TemporaryDirectory(prefix='macapk-compat-') as tmp:
    root = Path(tmp); (root/'probe.cpp').write_text(source)
    subprocess.run(['xcrun','clang++','-std=c++17','-I'+str(repo/'runtime/include'),str(root/'probe.cpp'),'-o',str(root/'probe')],check=True)
    clean = {k:v for k,v in os.environ.items() if not k.startswith('MACAPK_')}
    cases = [({}, ['unset','unset','unset','unset']),
             ({'MACAPK_COMPAT_PROFILE':'native-video'}, ['1','unset','unset','unset']),
             ({'MACAPK_COMPAT_PROFILE':'execution-copy'}, ['1','1','0','unset']),
             ({'MACAPK_COMPAT_PROFILE':'execution-copy','MACAPK_NO_GUEST_TEXT_PATCH':'0','MACAPK_RAW_SYSCALL_REDIRECT':'1'}, ['1','0','1','unset']),
             ({'MACAPK_COMPAT_PROFILE':'unknown'}, ['unset','unset','unset','unset'])]
    for extra, expected in cases:
        out = subprocess.check_output([str(root/'probe')],env=clean|extra,text=True)
        assert [row.split('=',1)[1] for row in out.splitlines()] == expected, out
print('PASS: default isolation, video presets, explicit overrides, unknown profile, mocks remain opt-in')
