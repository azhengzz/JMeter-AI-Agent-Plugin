@echo off
setlocal EnableDelayedExpansion

rem jmeter-cli regression tests (Windows / cmd)
rem Usage: run-cli-tests.bat [JMETER_HOME]
rem
rem Coverage (P0 smoke): probe / help / usage (exit 2) / auth (HTTP 401) /
rem allowlist block (HTTP 400) / test-exec commands (help + idle state).
rem These cases rely only on exit code + stderr keywords, reliable under cmd.
rem
rem Not covered: end-to-end CRUD (with --properties JSON).
rem Reason: cmd quoting of JSON double-quotes is unreliable; and create_jmeter_element
rem does not return elementId directly (needs find --json + jq). Use run-cli-tests.sh
rem (git bash / Linux / macOS) for full end-to-end, or docs\jmeter-cli-test-cases.md section 4.

set "REPO_ROOT=%~dp0.."
set "JH=%~1"
if "%JH%"=="" set "JH=%JMETER_HOME%"

rem --- locate jar: prefer shade jar (has Main-Class); exclude assembly *-jar-with-dependencies.jar ---
set "JAR="
for /f "delims=" %%f in ('dir /b /o-d "%REPO_ROOT%\target\jmeter-agent-*.jar" 2^>nul') do (
  echo %%f | findstr "jar-with-dependencies" >nul || ( set "JAR=%REPO_ROOT%\target\%%f" & goto :gotJar )
)
if not "%JH%"=="" for /f "delims=" %%f in ('dir /b /o-d "%JH%\lib\ext\jmeter-agent-*.jar" 2^>nul') do (
  echo %%f | findstr "jar-with-dependencies" >nul || ( set "JAR=%JH%\lib\ext\%%f" & goto :gotJar )
)
:gotJar
if "!JAR!"=="" ( echo Error: jmeter-agent jar not found. Run 'mvn package' or pass JMETER_HOME. 1>&2 & exit /b 2 )
if "%JH%"=="" ( echo Error: JMETER_HOME not set. Usage: %0 ^<JMETER_HOME^> 1>&2 & exit /b 2 )

if defined JAVA_HOME ( set "JAVACMD=%JAVA_HOME%\bin\java.exe" ) else ( set "JAVACMD=java" )

set /a PASS=0
set /a FAIL=0

echo === jmeter-cli regression tests (Windows) ===
echo jar=!JAR!  home=!JH!  java=!JAVACMD!
echo.

rem --- 0) probe ---
echo --- 0) probe ---
"%JAVACMD%" -jar "!JAR!" list --jmeter-home "!JH!" >nul 2>&1
if errorlevel 1 (
  echo   [FAIL] list - no instance? Start JMeter with -Jjmeter.ai.ipc.enabled=true
  set /a FAIL+=1
  goto :summary
)
echo   [OK]   list & set /a PASS+=1
"%JAVACMD%" -jar "!JAR!" health --jmeter-home "!JH!" >nul 2>&1
call :expect_exit 0 !errorlevel! "health"

rem --- 1) help and usage (P0) ---
echo --- 1) help and usage (P0) ---
"%JAVACMD%" -jar "!JAR!" >nul 2>&1
call :expect_exit 0 !errorlevel! "TC-EXC-01 no-args help"
"%JAVACMD%" -jar "!JAR!" -h >nul 2>&1
call :expect_exit 0 !errorlevel! "TC-EXC-02 -h/--help"
"%JAVACMD%" -jar "!JAR!" help create >nul 2>&1
call :expect_exit 0 !errorlevel! "TC-EXC-03 help cmd"
"%JAVACMD%" -jar "!JAR!" bogus >nul 2>&1
call :expect_exit 2 !errorlevel! "TC-EXC-05 unknown command"
"%JAVACMD%" -jar "!JAR!" create --elementName X --parentId 2 >nul 2>&1
call :expect_exit 2 !errorlevel! "TC-EXC-06 missing --elementType"
"%JAVACMD%" -jar "!JAR!" find --query x >nul 2>&1
call :expect_exit 2 !errorlevel! "TC-EXC-07 missing --searchBy"
"%JAVACMD%" -jar "!JAR!" update --elementId 5 >nul 2>&1
call :expect_exit 2 !errorlevel! "TC-EXC-08 missing --properties"
"%JAVACMD%" -jar "!JAR!" batch >nul 2>&1
call :expect_exit 2 !errorlevel! "TC-EXC-09 missing --elementIds"

rem --- 1b) more client usage / exit-code cases (pure client, reliable under cmd) ---
"%JAVACMD%" -jar "!JAR!" --help >nul 2>&1
call :expect_exit 0 !errorlevel! "TC-EXC-02 --help"
"%JAVACMD%" -jar "!JAR!" help >nul 2>&1
call :expect_exit 0 !errorlevel! "TC-EXC-02 help"
"%JAVACMD%" -jar "!JAR!" help bogus >nul 2>&1
call :expect_exit 2 !errorlevel! "TC-EXC-04 help unknown cmd"
"%JAVACMD%" -jar "!JAR!" create -h >nul 2>&1
call :expect_exit 0 !errorlevel! "TC-EXC-03 cmd -h"
"%JAVACMD%" -jar "!JAR!" create --elementType threadgroup --elementName X --parentId abc --jmeter-home "!JH!" >nul 2>&1
call :expect_exit 2 !errorlevel! "TC-EXC-10 non-integer parentId"
"%JAVACMD%" -jar "!JAR!" delete --elementId abc --jmeter-home "!JH!" >nul 2>&1
call :expect_exit 2 !errorlevel! "TC-EXC-10b non-integer elementId"
"%JAVACMD%" -jar "!JAR!" health --jmeter-home "!JH!" --json >nul 2>&1
call :expect_exit 0 !errorlevel! "TC-ENV-06 health --json"

rem --- 2) auth and allowlist (P0, security zero-tolerance) ---
echo --- 2) auth and allowlist (P0, security zero-tolerance) ---
"%JAVACMD%" -jar "!JAR!" health --jmeter-home "!JH!" --token wrong >nul 2>&1
call :expect_exit 1 !errorlevel! "TC-EXC-11 bad token exit"
"%JAVACMD%" -jar "!JAR!" health --jmeter-home "!JH!" --token wrong 2>&1 | findstr /i "401" >nul && ( echo   [OK]   TC-EXC-11 msg @401 & set /a PASS+=1 ) || ( echo   [FAIL] TC-EXC-11 msg @401 & set /a FAIL+=1 )
"%JAVACMD%" -jar "!JAR!" tool exec --params "{}" --jmeter-home "!JH!" >nul 2>&1
call :expect_exit 1 !errorlevel! "TC-TOOL-04 exec blocked"
"%JAVACMD%" -jar "!JAR!" tool exec --params "{}" --jmeter-home "!JH!" 2>&1 | findstr /i "not allowed" >nul && ( echo   [OK]   TC-TOOL-04 msg @not allowed & set /a PASS+=1 ) || ( echo   [FAIL] TC-TOOL-04 msg & set /a FAIL+=1 )
"%JAVACMD%" -jar "!JAR!" tool read_file --params "{}" --jmeter-home "!JH!" >nul 2>&1
call :expect_exit 1 !errorlevel! "TC-TOOL-05 read_file blocked"
rem TC-TOOL-06: run_test is now allowed (H1); test web_search which is still blocked (allowlist semantics unchanged)
"%JAVACMD%" -jar "!JAR!" tool web_search --params "{}" --jmeter-home "!JH!" >nul 2>&1
call :expect_exit 1 !errorlevel! "TC-TOOL-06 web_search blocked"

rem --- 2b) parentId smoke (find_element new --parentId scope; mirrors run-cli-tests.sh 2b) ---
rem pure-client: --parentId documented in find help
"%JAVACMD%" -jar "!JAR!" find -h 2>&1 | findstr /i /c:"--parentId" >nul && ( echo   [OK]   TC-READ-24 find help lists --parentId & set /a PASS+=1 ) || ( echo   [FAIL] TC-READ-24 find help missing --parentId & set /a FAIL+=1 )
rem server-dependent: nonexistent parentId -> tool error exit 1 (no JSON / data-prep needed)
"%JAVACMD%" -jar "!JAR!" find --searchBy name --query x --parentId 999999 --jmeter-home "!JH!" >nul 2>&1
call :expect_exit 1 !errorlevel! "TC-READ-25 parentId 不存在 exit"
"%JAVACMD%" -jar "!JAR!" find --searchBy name --query x --parentId 999999 --jmeter-home "!JH!" 2>&1 | findstr /i /c:"Could not find parent node" >nul && ( echo   [OK]   TC-READ-25 msg @Could not find parent node & set /a PASS+=1 ) || ( echo   [FAIL] TC-READ-25 msg & set /a FAIL+=1 )

rem --- 3) RUN test-exec commands (P0: help + idle-state error path) ---
echo --- 3) RUN test-exec commands (P0) ---
"%JAVACMD%" -jar "!JAR!" help run >nul 2>&1
call :expect_exit 0 !errorlevel! "TC-RUN-01 help run"
"%JAVACMD%" -jar "!JAR!" help stop >nul 2>&1
call :expect_exit 0 !errorlevel! "TC-RUN-02 help stop"
"%JAVACMD%" -jar "!JAR!" help shutdown >nul 2>&1
call :expect_exit 0 !errorlevel! "TC-RUN-03 help shutdown"
"%JAVACMD%" -jar "!JAR!" help status >nul 2>&1
call :expect_exit 0 !errorlevel! "TC-RUN-04 help status"
"%JAVACMD%" -jar "!JAR!" help results >nul 2>&1
call :expect_exit 0 !errorlevel! "TC-RUN-05 help results"
"%JAVACMD%" -jar "!JAR!" run -h >nul 2>&1
call :expect_exit 0 !errorlevel! "TC-RUN-06 run -h"
"%JAVACMD%" -jar "!JAR!" stop -h >nul 2>&1
call :expect_exit 0 !errorlevel! "TC-RUN-07 stop -h"
"%JAVACMD%" -jar "!JAR!" shutdown -h >nul 2>&1
call :expect_exit 0 !errorlevel! "TC-RUN-08 shutdown -h"
"%JAVACMD%" -jar "!JAR!" status -h >nul 2>&1
call :expect_exit 0 !errorlevel! "TC-RUN-09 status -h"
"%JAVACMD%" -jar "!JAR!" results -h >nul 2>&1
call :expect_exit 0 !errorlevel! "TC-RUN-10 results -h"
rem idle-state error path: needs server; assumes no test running in GUI session (else stop/shutdown hits a running test and returns exit 0)
"%JAVACMD%" -jar "!JAR!" stop --jmeter-home "!JH!" >nul 2>&1
call :expect_exit 1 !errorlevel! "TC-RUN-11 stop idle exit 1"
"%JAVACMD%" -jar "!JAR!" shutdown --jmeter-home "!JH!" >nul 2>&1
call :expect_exit 1 !errorlevel! "TC-RUN-12 shutdown idle exit 1"

echo.
echo --- End-to-end CRUD: use run-cli-tests.sh (git bash) or docs\jmeter-cli-test-cases.md section 4 ---
echo.

:summary
echo ==== PASS=!PASS!  FAIL=!FAIL! ====
if !FAIL! equ 0 ( exit /b 0 ) else ( exit /b 1 )

:expect_exit
  rem %1=expected exit  %2=actual exit  %3=name
  if "%~2"=="%~1" ( echo   [OK]   %~3 & set /a PASS+=1 ) else ( echo   [FAIL] %~3 ^(expected exit=%~1 got=%~2^) & set /a FAIL+=1 )
  exit /b 0
