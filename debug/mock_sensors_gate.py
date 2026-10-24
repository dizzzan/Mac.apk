"""Check simulated sensor catalogs and Java/NDK event delivery."""
from pathlib import Path
import os
import shlex
import subprocess
import tempfile

repo = Path(__file__).resolve().parent.parent
build = repo / 'runtime/build/native'
jdk = Path(os.environ['JAVA_HOME'])
with tempfile.TemporaryDirectory(prefix='mock-sensors-') as temp:
    out = Path(temp)
    cp = os.pathsep.join(str(repo / 'runtime/build/lib' / name)
                        for name in ('android-framework.jar', 'macapk-loader.jar'))
    subprocess.run([str(jdk / 'bin/javac'), '-cp', cp, '-d', str(out),
                    str(repo / 'debug/MockSensorsTest.java')], check=True)
    args = shlex.split((build / 'CMakeFiles/macapk-runtime.dir/link.txt').read_text())
    args = [x for x in args if 'main.cpp.o' not in x and '-sectcreate' not in x and x != '-DNDEBUG']
    exe = out / 'mock-sensors-test'
    args[args.index('-o') + 1] = str(exe)
    angle = repo / 'thirdparty/angle-prebuilt/lib'
    args[1:1] = [str(repo / 'debug/MockSensorsTest.cpp'), '-std=c++20',
                 '-I' + str(jdk / 'include'), '-I' + str(jdk / 'include/darwin'),
                 '-Wl,-rpath,' + str(angle)]
    subprocess.run(args, cwd=build, check=True)
    subprocess.run(['install_name_tool', '-change', './libGLESv2.dylib', str(angle / 'libGLESv2.dylib'),
                    '-change', './libEGL.dylib', str(angle / 'libEGL.dylib'), str(exe)], check=True)
    subprocess.run(['codesign', '--force', '--sign', '-', '--entitlements',
                    str(repo / 'runtime/scripts/jit-entitlements.plist'), str(exe)], check=True)
    base = dict(os.environ)
    for key in list(base):
        if key.startswith('MACAPK_MOCK_'):
            del base[key]
    command = [str(exe), str(repo / 'runtime/translator/build/libmacapk-bionic-shim.dylib'),
               str(out) + os.pathsep + cp]
    for enabled in ('0', '1'):
        subprocess.run(command, env=base | {'MACAPK_MOCK_SENSORS': enabled,
                       'MACAPK_MOCK_BLUETOOTH': enabled}, check=True, timeout=20)
    for setting, expected in [('500', '500'), ('NaN', '200'), ('-1', '200'), ('1,2', '200')]:
        subprocess.run(command,
                       env=base | {'MACAPK_MOCK_SENSORS': '1', 'MACAPK_MOCK_SENSOR_LIGHT': setting,
                                   'MACAPK_TEST_LIGHT': expected}, check=True, timeout=20)
