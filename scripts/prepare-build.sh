#!/usr/bin/env bash
# Prepara el árbol para compilar el APK con NDK 29 / protobuf >= 4.
# Idempotente: se puede correr varias veces.
#
#   ./scripts/prepare-build.sh
#   cd android && ./gradlew assembleSideapkDebug -Parm64only
#
# Qué arregla y por qué:
#   1. oboe (submódulo de Google): compila con -Ofast y -Werror en debug.
#      Clang 21, el del NDK 29, marca -Ofast como deprecado y con -Werror mata
#      el build. Se cambia por -O3 y se quita el -Werror de debug. No se puede
#      arreglar desde los arguments de CMake: -Wno-error puesto en
#      CMAKE_CXX_FLAGS queda antes del -Werror del target y pierde.
#   2. nanopb 0.4.6: su generador llama reflection.MakeClass, eliminado en
#      protobuf 4. Se agrega un shim a message_factory.GetMessageClass.
set -euo pipefail

root="$(cd "$(dirname "$0")/.." && pwd)"
oboe="$root/android/app/src/main/cpp/oboe/CMakeLists.txt"
gen="$root/third-party/nanopb/generator/nanopb_generator.py"

if [ -f "$oboe" ]; then
	if grep -q -- "-Ofast" "$oboe"; then
		echo "oboe: -Ofast -> -O3, quitando -Werror de debug"
		# La linea del -Werror cierra el target_compile_options(, asi que se
		# reemplaza por el parentesis en vez de borrarse.
		sed -i 's/^        -Ofast$/        -O3/; s/^        "\$<\$<CONFIG:DEBUG>:-Werror>")\s*$/        )/' "$oboe"
	else
		echo "oboe: ya parcheado"
	fi
else
	echo "oboe: no encontrado, ¿faltan los submódulos? git submodule update --init --recursive" >&2
fi

if [ -f "$gen" ]; then
	if grep -q "message_factory.GetMessageClass" "$gen"; then
		echo "nanopb: ya parcheado"
	else
		echo "nanopb: agregando shim de MakeClass"
		python3 - "$gen" <<'PY'
import sys
p = sys.argv[1]
src = open(p).read()
needle = "    import google.protobuf.reflection as reflection\n"
shim = needle + (
    "    if not hasattr(reflection, 'MakeClass'):\n"
    "        import google.protobuf.message_factory as _message_factory\n"
    "        reflection.MakeClass = _message_factory.GetMessageClass\n"
)
if needle not in src:
    sys.exit("nanopb: no se encontró el import de reflection, revisar a mano")
open(p, "w").write(src.replace(needle, shim, 1))
PY
	fi
else
	echo "nanopb: no encontrado, ¿faltan los submódulos? git submodule update --init --recursive" >&2
fi

cat <<'MSG'

Listo. Recordatorios:
  - protoc debe estar en PATH (https://github.com/protocolbuffers/protobuf/releases, v21.12 sirve).
  - Si compilas desde un venv de Python sin setuptools, añade a los arguments de
    CMake: -DPYTHON_EXECUTABLE=/usr/bin/python3
  - El keystore va en android/local.properties (ver FORK.md §Firma).
MSG
