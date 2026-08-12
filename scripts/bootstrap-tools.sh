#!/usr/bin/env bash
# Dev sandbox toolchain (JDK 21 + Maven). Lives outside the workspace snapshot in ~/.cache.
# Usage: source scripts/bootstrap-tools.sh
set -e
TOOLS="$HOME/.cache/tools"
if [ ! -x "$TOOLS/jdk21/bin/java" ]; then
  mkdir -p "$TOOLS"
  echo "[bootstrap] downloading Temurin JDK 21..."
  curl -sSL --max-time 560 "https://api.adoptium.net/v3/binary/latest/21/ga/linux/x64/jdk/hotspot/normal/eclipse" -o /tmp/jdk21.tgz
  tar xzf /tmp/jdk21.tgz -C "$TOOLS" && mv "$TOOLS"/jdk-21* "$TOOLS/jdk21" && rm /tmp/jdk21.tgz
fi
if [ ! -x "$TOOLS/maven/bin/mvn" ]; then
  mkdir -p "$TOOLS"
  echo "[bootstrap] downloading Maven 3.9.9..."
  curl -sSL --max-time 300 "https://archive.apache.org/dist/maven/maven-3/3.9.9/binaries/apache-maven-3.9.9-bin.tar.gz" -o /tmp/maven.tgz
  tar xzf /tmp/maven.tgz -C "$TOOLS" && mv "$TOOLS"/apache-maven-3.9.9 "$TOOLS/maven" && rm /tmp/maven.tgz
fi
export JAVA_HOME="$TOOLS/jdk21"
export PATH="$TOOLS/maven/bin:$TOOLS/jdk21/bin:$PATH"
echo "[bootstrap] java: $(java -version 2>&1 | head -1) · maven: $(mvn -version | head -1)"
