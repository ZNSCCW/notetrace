@echo off
REM NoteTrace launcher (Windows CMD / double-click)
REM Sets ingest dir to <repo-root>\data\notes automatically.
REM Loads DEEPSEEK_API_KEY from user env if not present (set via: setx DEEPSEEK_API_KEY "sk-xxx")
cd /d "%~dp0"
set "NOTETRACE_INGEST_DIR=%~dp0data\notes"
if not defined DEEPSEEK_API_KEY (
    for /f "delims=" %%i in ('powershell -NoProfile -Command "[Environment]::GetEnvironmentVariable('DEEPSEEK_API_KEY','User')"') do set "DEEPSEEK_API_KEY=%%i"
)
echo Ingest dir: %NOTETRACE_INGEST_DIR%
echo Starting NoteTrace... (http://localhost:8081)
REM Extra args pass-through, e.g.: start.bat -Dspring-boot.run.arguments=--eval
mvn -f backend\pom.xml spring-boot:run %*
