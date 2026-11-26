@echo off
chcp 65001 >nul
cd /d "%~dp0"

if not exist out mkdir out

echo Compiling...
javac -encoding UTF-8 -cp "lib\sqlite-jdbc.jar" -d out server\*.java
if errorlevel 1 goto fail

echo.
echo Server starting at http://localhost:8080
echo Press Ctrl+C to stop.
echo.
java -cp "out;lib\sqlite-jdbc.jar" Server
goto end

:fail
echo.
echo Compile failed. Please send the errors above to your teacher.

:end
pause
