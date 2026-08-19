@echo off
setlocal

rem Always run the Agent that is next to this script, regardless of the Task Scheduler working directory.
cd /d "%~dp0"

set "NODE_EXE=C:\Program Files\nodejs\node.exe"
if not exist "%NODE_EXE%" set "NODE_EXE=node.exe"

"%NODE_EXE%" "%~dp0agent.mjs" >> "%~dp0home-agent.log" 2>&1
exit /b %ERRORLEVEL%
