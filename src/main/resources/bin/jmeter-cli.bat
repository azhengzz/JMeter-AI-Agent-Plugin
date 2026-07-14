@echo off
setlocal EnableDelayedExpansion

rem JMeter AI CLI launcher (Windows)
rem Locates JMETER_HOME (env or inferred from script location) and runs the CLI
rem from the shaded plugin jar (newest jmeter-agent-*.jar in lib/ext).
rem Run "jmeter-cli --help" for full usage.

rem Default JMETER_HOME to the parent of this script's directory
rem (for %%~fi is pure string normalization: cross-drive and UNC safe, no pushd side effects)
if "!JMETER_HOME!"=="" for %%i in ("%~dp0..") do set "JMETER_HOME=%%~fi"

rem Find newest jmeter-agent-*.jar in lib/ext (the shade uber jar)
set "JAR="
for /f "delims=" %%f in ('dir /b /o-d "!JMETER_HOME!\lib\ext\jmeter-agent-*.jar" 2^>nul') do (
    set "JAR=!JMETER_HOME!\lib\ext\%%f"
    goto :gotJar
)
:gotJar
if "!JAR!"=="" (
    echo Error: jmeter-agent jar not found in !JMETER_HOME!\lib\ext 1>&2
    exit /b 1
)

if defined JAVA_HOME (
    rem Probe standard bin\java.exe first, then root java.exe, then fallback to PATH
    if exist "!JAVA_HOME!\bin\java.exe" (
        set "JAVACMD=!JAVA_HOME!\bin\java.exe"
    ) else if exist "!JAVA_HOME!\java.exe" (
        set "JAVACMD=!JAVA_HOME!\java.exe"
    ) else (
        echo Warning: java not found in JAVA_HOME, fallback to PATH 1>&2
        set "JAVACMD=java"
    )
) else (
    set "JAVACMD=java"
)

"!JAVACMD!" -cp "!JAR!" org.gitee.jmeter.ai.cli.JmeterCli %*

endlocal
