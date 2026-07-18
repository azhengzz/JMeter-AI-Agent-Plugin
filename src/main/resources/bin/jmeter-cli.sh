#!/bin/sh
# JMeter AI CLI launcher (Linux/macOS)
# Locates JMETER_HOME (env or inferred from script location) and runs the CLI
# from the shaded plugin jar (newest jmeter-agent-*.jar in lib/ext).
# Run "jmeter-cli --help" for full usage.
#
# NOTE: keep this file with LF line endings. CRLF will cause
#       "bad interpreter: /bin/sh^M: No such file or directory" on Linux.

DIR="$(cd "$(dirname "$0")" && pwd)"
: "${JMETER_HOME:=$(cd "$DIR/.." && pwd)}"

# Find newest jmeter-agent-*.jar in lib/ext (the shade uber jar)
JAR="$(ls -t "$JMETER_HOME"/lib/ext/jmeter-agent-*.jar 2>/dev/null | head -n1)"
if [ -z "$JAR" ]; then
    echo "Error: jmeter-agent jar not found in $JMETER_HOME/lib/ext" >&2
    exit 1
fi

# Prefer JAVA_HOME (probe bin/java then root java), fall back to java on PATH.
if [ -n "$JAVA_HOME" ]; then
    if [ -x "$JAVA_HOME/bin/java" ]; then
        JAVACMD="$JAVA_HOME/bin/java"
    elif [ -x "$JAVA_HOME/java" ]; then
        JAVACMD="$JAVA_HOME/java"
    else
        echo "Warning: java not found in JAVA_HOME, fallback to PATH" >&2
        JAVACMD="java"
    fi
else
    JAVACMD="java"
fi

export JMETER_HOME
exec "$JAVACMD" -cp "$JAR" org.gitee.jmeter.ai.cli.JmeterCli "$@"
