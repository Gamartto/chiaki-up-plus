#!/usr/bin/env bash
# Prepares the tree to build the APK with NDK 29 / protobuf >= 4.
# Idempotent: safe to run more than once.
#
#   ./scripts/prepare-build.sh
#   cd android && ./gradlew assembleSideapkDebug -Parm64only
#
# What it fixes, and why:
#   1. oboe (Google submodule): builds with -Ofast and -Werror in debug. Clang 21,
#      the one shipped with NDK 29, marks -Ofast as deprecated and -Werror turns
#      that into a failed build. It becomes -O3 and the debug -Werror is dropped.
#      This cannot be fixed from the CMake arguments: -Wno-error placed in
#      CMAKE_CXX_FLAGS is applied before the target's own -Werror and loses.
#   2. nanopb 0.4.6: its generator calls reflection.MakeClass, removed in
#      protobuf 4. A shim to message_factory.GetMessageClass is added.
set -euo pipefail

root="$(cd "$(dirname "$0")/.." && pwd)"
oboe="$root/android/app/src/main/cpp/oboe/CMakeLists.txt"
gen="$root/third-party/nanopb/generator/nanopb_generator.py"

if [ -f "$oboe" ]; then
	if grep -q -- "-Ofast" "$oboe"; then
		echo "oboe: -Ofast -> -O3, dropping the debug -Werror"
		# The -Werror line also closes target_compile_options(, so it is replaced
		# with the closing parenthesis rather than deleted.
		sed -i 's/^        -Ofast$/        -O3/; s/^        "\$<\$<CONFIG:DEBUG>:-Werror>")\s*$/        )/' "$oboe"
	else
		echo "oboe: already patched"
	fi
else
	echo "oboe: not found, are the submodules missing? git submodule update --init --recursive" >&2
fi

if [ -f "$gen" ]; then
	if grep -q "message_factory.GetMessageClass" "$gen"; then
		echo "nanopb: already patched"
	else
		echo "nanopb: adding the MakeClass shim"
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
    sys.exit("nanopb: the reflection import was not found, check it by hand")
open(p, "w").write(src.replace(needle, shim, 1))
PY
	fi
else
	echo "nanopb: not found, are the submodules missing? git submodule update --init --recursive" >&2
fi

cat <<'MSG'

Done. Reminders:
  - protoc must be on PATH (https://github.com/protocolbuffers/protobuf/releases, v21.12 works).
  - If you build from a Python venv without setuptools, add to the CMake arguments:
    -DPYTHON_EXECUTABLE=/usr/bin/python3
  - The keystore goes in android/local.properties (see README, "Signing").
MSG
