@echo off
rem JMeter AI CLI launcher (Windows)
rem Locates JMETER_HOME (env or inferred from script location) and runs the CLI
rem from the shaded plugin jar (newest jmeter-agent-*.jar in lib/ext).
rem Run "jmeter-cli --help" for full usage.
rem
rem STRUCTURAL INVARIANT — DisableDelayedExpansion: never SET and READ the same
rem variable within one parenthesized block. This script relies on it: every
rem intra-block statement only WRITES (set JAR inside the for body, set JAVACMD
rem inside the if/else chain) and every READ happens in a LATER statement (after
rem the goto :gotJar exit, or on the final java line). Re-enabling delayed
rem expansion would silently corrupt arguments containing '!' (EnableDelayed-
rem Expansion eats !...! spans); restructuring must preserve write-then-read-
rem in-a-later-statement, or percent expansion goes stale inside blocks.
rem
rem KNOWN LIMITATION (cmd.exe inherent): the args passthrough cannot be reliably
rem re-quoted in a batch file, so UNQUOTED cmd metacharacters in arguments
rem (ampersand, pipe, less-than, greater-than, caret) are re-interpreted by cmd.
rem Quote such arguments, or use jmeter-cli.sh / invoke java directly in
rem scripted environments. (Do NOT put raw metacharacters in rem lines either -
rem cmd parses them here too.)
setlocal DisableDelayedExpansion

rem Default JMETER_HOME to the parent of this script's directory
rem (for %%~fi is pure string normalization: cross-drive and UNC safe, no pushd side effects)
if "%JMETER_HOME%"=="" for %%i in ("%~dp0..") do set "JMETER_HOME=%%~fi"

rem Find newest jmeter-agent-*.jar in lib/ext (the shade uber jar)
set "JAR="
for /f "delims=" %%f in ('dir /b /o-d "%JMETER_HOME%\lib\ext\jmeter-agent-*.jar" 2^>nul') do (
    set "JAR=%JMETER_HOME%\lib\ext\%%f"
    goto :gotJar
)
:gotJar
if "%JAR%"=="" (
    echo Error: jmeter-agent jar not found in %JMETER_HOME%\lib\ext 1>&2
    exit /b 1
)

if defined JAVA_HOME (
    rem Probe standard bin\java.exe first, then root java.exe, then fallback to PATH
    if exist "%JAVA_HOME%\bin\java.exe" (
        set "JAVACMD=%JAVA_HOME%\bin\java.exe"
    ) else if exist "%JAVA_HOME%\java.exe" (
        set "JAVACMD=%JAVA_HOME%\java.exe"
    ) else (
        echo Warning: java not found in JAVA_HOME, fallback to PATH 1>&2
        set "JAVACMD=java"
    )
) else (
    set "JAVACMD=java"
)

"%JAVACMD%" -cp "%JAR%" org.gitee.jmeter.ai.cli.JmeterCli %*

rem Propagate the java exit code (0/1/2 contract, see jmeter-cli-test-cases.md 6.1).
rem Capture before endlocal; %CLI_EXIT% expands when the line is parsed, i.e. still
rem inside the setlocal scope, so the value survives the endlocal on the same line.
set "CLI_EXIT=%ERRORLEVEL%"
endlocal & exit /b %CLI_EXIT%
