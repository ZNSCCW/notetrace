@echo off
REM NoteTrace launcher (Windows CMD / double-click)
REM Sets ingest dir to <repo-root>\data\notes automatically.
cd /d "%~dp0"
set "NOTETRACE_INGEST_DIR=%~dp0data\notes"
echo Ingest dir: %NOTETRACE_INGEST_DIR%
echo Starting NoteTrace... (http://localhost:8081)
mvn -f backend\pom.xml spring-boot:run
