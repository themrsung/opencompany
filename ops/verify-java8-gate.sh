#!/usr/bin/env bash
# Proves the Java 8 gate is live (brief SS1, milestone 1).
#
# A compiler setting that silently stops working is worse than no setting: the
# build stays green and the breakage surfaces as a NoSuchMethodError on a
# client's JRE months later. So we assert the gate by feeding it something that
# must fail, rather than trusting the pom to still say <release>8</release>.
set -euo pipefail

cd "$(dirname "$0")/../backend"
PROBE="platform-compat/src/main/java/com/coreintra/compat/GateProbe.java"
cleanup() { rm -f "$PROBE"; }
trap cleanup EXIT

cat > "$PROBE" <<'JAVA'
package com.coreintra.compat;
import java.util.List;
public final class GateProbe {
    public static List<String> probe() { return List.of("java9+"); }
}
JAVA

echo "verify-java8-gate: compiling a Java 9+ API, expecting failure..."
if ./mvnw -B -q -pl platform-compat compile > /tmp/gate-probe.log 2>&1; then
  echo "FAIL: List.of compiled. The Java 8 gate is NOT enforcing --release 8." >&2
  echo "      Check maven-compiler-plugin <release> in backend/pom.xml." >&2
  exit 1
fi

if ! grep -q "cannot find symbol" /tmp/gate-probe.log; then
  echo "FAIL: build failed, but not because List.of is unavailable." >&2
  echo "      The gate may be masked by an unrelated compile error:" >&2
  tail -20 /tmp/gate-probe.log >&2
  exit 1
fi

echo "verify-java8-gate: OK - Java 9+ APIs fail the build."
