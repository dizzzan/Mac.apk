"""Link the built runtime objects into a JNI/ANGLE external-texture pixel regression test."""
from pathlib import Path
import os
import shlex
import subprocess
import tempfile

repo = Path(__file__).resolve().parent.parent
build = repo / 'runtime/build/native'
jdk = Path(os.environ['JAVA_HOME'])
with tempfile.TemporaryDirectory(prefix='external-texture-') as temp:
    out = Path(temp)
    cp = os.pathsep.join(str(repo / 'runtime/build/lib' / name)
                        for name in ('android-framework.jar', 'macapk-loader.jar'))
    subprocess.run([str(jdk / 'bin/javac'), '-cp', cp, '-d', str(out),
                    str(repo / 'debug/SurfaceTextureExternalTest.java')], check=True)
    args = shlex.split((build / 'CMakeFiles/macapk-runtime.dir/link.txt').read_text())
    args = [x for x in args if 'main.cpp.o' not in x and '-sectcreate' not in x and x != '-DNDEBUG']
    exe = out / 'external-texture-test'
    args[args.index('-o') + 1] = str(exe)
    angle = repo / 'thirdparty/angle-prebuilt/lib'
    args[1:1] = [str(repo / 'debug/SurfaceTextureExternalTest.cpp'), '-std=c++20',
                 '-I' + str(jdk / 'include'), '-I' + str(jdk / 'include/darwin'),
                 '-Wl,-rpath,' + str(angle)]
    subprocess.run(args, cwd=build, check=True)
    subprocess.run(['install_name_tool', '-change', './libGLESv2.dylib', str(angle / 'libGLESv2.dylib'),
                    '-change', './libEGL.dylib', str(angle / 'libEGL.dylib'), str(exe)], check=True)
    subprocess.run(['codesign', '--force', '--sign', '-', '--entitlements',
                    str(repo / 'runtime/scripts/jit-entitlements.plist'), str(exe)], check=True)
    # ANGLE's Metal backend does not implement external images. Its Vulkan
    # backend does, and is the one selected for GLES 3.1-capable APKs.
    icd = str(angle / 'MoltenVK_icd.json')
    env = dict(os.environ, ANGLE_DEFAULT_PLATFORM='vulkan',
               VK_ICD_FILENAMES=icd, VK_DRIVER_FILES=icd,
               MVK_CONFIG_LOG_LEVEL='0')
    subprocess.run([str(exe), str(repo / 'runtime/translator/build/libmacapk-bionic-shim.dylib'),
                    str(out) + os.pathsep + cp], env=env, check=True)
