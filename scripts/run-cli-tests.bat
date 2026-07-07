@echo off
setlocal EnableDelayedExpansion

rem jmeter-cli 回归测试(Windows / cmd)
rem 用法: run-cli-tests.bat [JMETER_HOME]
rem
rem 覆盖范围:P0 冒烟 —— 探活 / 帮助 / 用法(exit 2)/ 鉴权(HTTP 401)/ 白名单封堵(HTTP 400)。
rem 这些用例只需 exit code + stderr 关键词,在 cmd 下可靠。
rem
rem 未覆盖:端到端 CRUD 链路(含 --properties JSON)。
rem 原因 :cmd 对命令行 JSON 双引号的解析不可靠;且 create_jmeter_element 不直接返回 elementId,
rem        需 find --json + jq 解析。完整端到端请用 run-cli-tests.sh(git bash / Linux / macOS),
rem        或参考 docs\jmeter-cli-test-cases.md 第 4 节手工执行。

set "REPO_ROOT=%~dp0.."

rem --- 定位 jar:优先 shade jar(含 Main-Class);排除 assembly 的 *-jar-with-dependencies.jar ---
set "JAR="
for /f "delims=" %%f in ('dir /b /o-d "%REPO_ROOT%\target\jmeter-agent-*.jar" 2^>nul') do (
  echo %%f | findstr "jar-with-dependencies" >nul || ( set "JAR=%REPO_ROOT%\target\%%f" & goto :gotJar )
)
set "JH=%~1"
if "%JH%"=="" set "JH=%JMETER_HOME%"
if not "%JH%"=="" for /f "delims=" %%f in ('dir /b /o-d "%JH%\lib\ext\jmeter-agent-*.jar" 2^>nul') do (
  echo %%f | findstr "jar-with-dependencies" >nul || ( set "JAR=%JH%\lib\ext\%%f" & goto :gotJar )
)
:gotJar
if "!JAR!"=="" ( echo Error: jmeter-agent jar not found. Run 'mvn package' or pass JMETER_HOME. 1>&2 & exit /b 2 )
if "%JH%"=="" ( echo Error: JMETER_HOME not set. Usage: %0 ^<JMETER_HOME^> 1>&2 & exit /b 2 )

if defined JAVA_HOME ( set "JAVACMD=%JAVA_HOME%\bin\java.exe" ) else ( set "JAVACMD=java" )

set /a PASS=0
set /a FAIL=0

echo === jmeter-cli 回归测试 (Windows) ===
echo jar=!JAR!  home=!JH!  java=!JAVACMD!
echo.

rem --- 0) 探活 ---
echo --- 0) 探活 ---
"%JAVACMD%" -jar "!JAR!" list --jmeter-home "!JH!" >nul 2>&1
if errorlevel 1 (
  echo   [FAIL] list - 无实例?请用 -Jjmeter.ai.ipc.enabled=true 启动 JMeter
  set /a FAIL+=1
  goto :summary
)
echo   [OK]   list & set /a PASS+=1
"%JAVACMD%" -jar "!JAR!" health --jmeter-home "!JH!" >nul 2>&1
call :expect_exit 0 !errorlevel! "health"

rem --- 1) 帮助与用法 (P0) ---
echo --- 1) 帮助与用法 (P0) ---
"%JAVACMD%" -jar "!JAR!" >nul 2>&1
call :expect_exit 0 !errorlevel! "TC-EXC-01 no-args help"
"%JAVACMD%" -jar "!JAR!" -h >nul 2>&1
call :expect_exit 0 !errorlevel! "TC-EXC-02 -h/--help"
"%JAVACMD%" -jar "!JAR!" help create >nul 2>&1
call :expect_exit 0 !errorlevel! "TC-EXC-03 help ^<cmd^>"
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

rem --- 1b) 更多客户端用法/退出码用例(纯客户端,cmd 可靠)---
"%JAVACMD%" -jar "!JAR!" --help >nul 2>&1
call :expect_exit 0 !errorlevel! "TC-EXC-02 --help"
"%JAVACMD%" -jar "!JAR!" help >nul 2>&1
call :expect_exit 0 !errorlevel! "TC-EXC-02 help"
"%JAVACMD%" -jar "!JAR!" help bogus >nul 2>&1
call :expect_exit 2 !errorlevel! "TC-EXC-04 help <unknown cmd>"
"%JAVACMD%" -jar "!JAR!" create -h >nul 2>&1
call :expect_exit 0 !errorlevel! "TC-EXC-03 <cmd> -h"
"%JAVACMD%" -jar "!JAR!" create --elementType threadgroup --elementName X --parentId abc --jmeter-home "!JH!" >nul 2>&1
call :expect_exit 2 !errorlevel! "TC-EXC-10 non-integer parentId"
"%JAVACMD%" -jar "!JAR!" delete --elementId abc --jmeter-home "!JH!" >nul 2>&1
call :expect_exit 2 !errorlevel! "TC-EXC-10b non-integer elementId"
"%JAVACMD%" -jar "!JAR!" health --jmeter-home "!JH!" --json >nul 2>&1
call :expect_exit 0 !errorlevel! "TC-ENV-06 health --json"

rem --- 2) 鉴权与白名单 (P0, 安全零容忍) ---
echo --- 2) 鉴权与白名单 (P0, 安全零容忍) ---
"%JAVACMD%" -jar "!JAR!" health --jmeter-home "!JH!" --token wrong >nul 2>&1
call :expect_exit 1 !errorlevel! "TC-EXC-11 bad token (exit)"
"%JAVACMD%" -jar "!JAR!" health --jmeter-home "!JH!" --token wrong 2>&1 | findstr /i "401" >nul && ( echo   [OK]   TC-EXC-11 msg @401 & set /a PASS+=1 ) || ( echo   [FAIL] TC-EXC-11 msg @401 & set /a FAIL+=1 )
"%JAVACMD%" -jar "!JAR!" tool exec --params "{}" --jmeter-home "!JH!" >nul 2>&1
call :expect_exit 1 !errorlevel! "TC-TOOL-04 exec blocked"
"%JAVACMD%" -jar "!JAR!" tool exec --params "{}" --jmeter-home "!JH!" 2>&1 | findstr /i "not allowed" >nul && ( echo   [OK]   TC-TOOL-04 msg @not allowed & set /a PASS+=1 ) || ( echo   [FAIL] TC-TOOL-04 msg & set /a FAIL+=1 )
"%JAVACMD%" -jar "!JAR!" tool read_file --params "{}" --jmeter-home "!JH!" >nul 2>&1
call :expect_exit 1 !errorlevel! "TC-TOOL-05 read_file blocked"
rem TC-TOOL-06:H1 后 run_test 已放行;改测仍被封堵的 web_search(白名单语义不变)
"%JAVACMD%" -jar "!JAR!" tool web_search --params "{}" --jmeter-home "!JH!" >nul 2>&1
call :expect_exit 1 !errorlevel! "TC-TOOL-06 web_search blocked"

echo.
echo --- 端到端 CRUD 链路:请用 run-cli-tests.sh(git bash)或 docs\jmeter-cli-test-cases.md §4 ---
echo.

:summary
echo ==== PASS=!PASS!  FAIL=!FAIL! ====
if !FAIL! equ 0 ( exit /b 0 ) else ( exit /b 1 )

:expect_exit
  rem %1=expected exit  %2=actual exit  %3=name
  if "%~2"=="%~1" ( echo   [OK]   %~3 & set /a PASS+=1 ) else ( echo   [FAIL] %~3 ^(expected exit=%~1 got=%~2^) & set /a FAIL+=1 )
  exit /b 0
