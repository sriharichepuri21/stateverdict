#!/usr/bin/env python3
"""Copy a Spring Boot jar, replacing one application class with a mutant's bytecode.

    scripts/mutation/patch-jar.py BOOT_JAR CLASS_NAME MUTANT.class OUT_JAR

Every other entry is copied byte for byte with its original compression; Spring Boot requires the
nested library jars to stay uncompressed (stored). Exits 1 if the class is not in the jar.
"""
import shutil
import sys
import zipfile


def main():
    jar, cls, mutant, out = sys.argv[1:5]
    entry = "BOOT-INF/classes/" + cls.replace(".", "/") + ".class"
    data = open(mutant, "rb").read()
    replaced = False
    with zipfile.ZipFile(jar) as src, zipfile.ZipFile(out + ".tmp", "w") as dst:
        for info in src.infolist():
            if info.filename == entry:
                dst.writestr(info, data, compress_type=info.compress_type)
                replaced = True
            else:
                dst.writestr(info, src.read(info.filename), compress_type=info.compress_type)
    if not replaced:
        print(f"{entry} is not in {jar}")
        return 1
    shutil.move(out + ".tmp", out)
    return 0


if __name__ == "__main__":
    sys.exit(main())
