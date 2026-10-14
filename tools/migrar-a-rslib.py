#!/usr/bin/env python3
"""Pasa un plugin Rs de SnLib a RsLib (cambios mecánicos).

Uso: python3 migrar-a-rslib.py <carpeta-del-plugin> [versión-de-rslib]

Qué cambia:
  - Java: paquete com.sn.lib -> com.rs.lib, tipos Sn* -> Rs*, Sn -> Rs, sn()/sn -> rs()/rs,
    reregisterAll -> reRegisterAll, claves "snlib.*" -> "rslib.*" y la palabra SnLib -> RsLib.
  - pom.xml: dependencia com.sn:snlib -> com.rs:rslib y propiedad snlib.version -> rslib.version.
  - plugin.yml: SnLib -> RsLib en depend/softdepend/loadbefore.
  - .yml del jar: el bloque raíz 'snlib:' pasa a 'rslib:' y los comentarios nombran a RsLib.
No sube la versión del plugin ni toca la lógica; compilar y revisar después.
"""
import pathlib
import re
import sys

# Tipos de SnLib que no se importan por su nombre (anidados).
NESTED_TYPES = {"SnArg"}
IMPORTED = re.compile(r"^import\s+(?:static\s+)?com\.sn\.lib\.[\w.]*?\b(Sn\w*)\b", re.M)

JAVA_RULES = [
    (re.compile(r"\bcom\.sn\.lib\b"), "com.rs.lib"),
    (re.compile(r"\bSnLib\b"), "RsLib"),
    (re.compile(r"\bsn\(\)"), "rs()"),
    (re.compile(r"\bsn\b"), "rs"),
    (re.compile(r"\breregisterAll\b"), "reRegisterAll"),
    (re.compile(r'"snlib\.'), '"rslib.'),
]


def java(path):
    text = path.read_text(encoding="utf-8")
    # Solo los tipos de SnLib que el archivo usa: otros nombres con "Sn" (SnFlyBoostLimit) quedan.
    types = set(IMPORTED.findall(text)) | NESTED_TYPES
    new = text
    for name in sorted(types, key=len, reverse=True):
        new = re.sub(r"\b%s\b" % re.escape(name), "Rs" + name[2:], new)
    for pattern, repl in JAVA_RULES:
        new = pattern.sub(repl, new)
    return new


def pom(path, version):
    text = path.read_text(encoding="utf-8")
    new = re.sub(r"<groupId>com\.sn</groupId>(\s*)<artifactId>snlib</artifactId>(\s*)<version>\$\{snlib\.version\}</version>",
                 r"<groupId>com.rs</groupId>\1<artifactId>rslib</artifactId>\2<version>${rslib.version}</version>", text)
    new = re.sub(r"<snlib\.version>[^<]*</snlib\.version>", "<rslib.version>%s</rslib.version>" % version, new)
    new = re.sub(r"\bSnLib\b", "RsLib", new)
    new = re.sub(r"\bsn\.papi\(\)", "rs.papi()", new)
    return new


def plugin_yml(path):
    text = path.read_text(encoding="utf-8")
    return re.sub(r"\bSnLib\b", "RsLib", text)


def resource_yml(path):
    text = path.read_text(encoding="utf-8")
    text = re.sub(r"(?m)^snlib:", "rslib:", text)
    text = re.sub(r"\bSnLib\b", "RsLib", text)
    return re.sub(r"\bsnlib\.", "rslib.", text)


def main():
    if len(sys.argv) < 2:
        print(__doc__)
        sys.exit(1)
    root = pathlib.Path(sys.argv[1])
    version = sys.argv[2] if len(sys.argv) > 2 else "1.0.0"
    changed = []
    for path in sorted(root.rglob("*")):
        if not path.is_file() or "target" in path.parts or ".git" in path.parts:
            continue
        new = None
        if path.suffix == ".java":
            new = java(path)
        elif path.name == "pom.xml":
            new = pom(path, version)
        elif path.name in ("plugin.yml", "paper-plugin.yml"):
            new = plugin_yml(path)
        elif path.suffix == ".yml" and "resources" in path.parts:
            new = resource_yml(path)
        if new is not None and new != path.read_text(encoding="utf-8"):
            path.write_text(new, encoding="utf-8", newline="")
            changed.append(path.relative_to(root))
    for path in changed:
        print("cambiado:", path)
    leftovers = []
    for path in root.rglob("*.java"):
        for n, line in enumerate(path.read_text(encoding="utf-8").splitlines(), 1):
            if re.search(r"\bcom\.sn\b|\bSn[A-Z]", line):
                leftovers.append("%s:%d: %s" % (path.relative_to(root), n, line.strip()))
    if leftovers:
        print("Revisar a mano:")
        print("\n".join(leftovers))


if __name__ == "__main__":
    main()
