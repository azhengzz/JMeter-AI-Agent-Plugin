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

# Prefer JAVA_HOME, fall back to java on PATH.
if [ -n "$JAVA_HOME" ]; then
    JAVACMD="$JAVA_HOME/bin/java"
else
    JAVACMD="java"
fi

exec "$JAVACMD" -cp "$JAR" org.gitee.jmeter.ai.cli.JmeterCli "$@"
