@echo off
setlocal EnableDelayedExpansion
title Build Atlas Client
cd /d "%~dp0"
echo ============================================================
echo  Atlas Client - build the mod JAR on this PC
echo ============================================================
echo This runs Gradle (the normal Fabric mod build). The first run downloads
echo Gradle, Fabric Loom, Minecraft 1.21.11 and the Fabric libraries from their
echo official sites, which can take 5-15 minutes. Later runs are much faster.
echo.

rem ---- find a Java 21+ JDK (needs javac, a plain Java runtime is not enough)
set "JDK="
if defined JAVA_HOME if exist "%JAVA_HOME%\bin\javac.exe" set "JDK=%JAVA_HOME%"
if not defined JDK (
  for %%D in ("%ProgramFiles%\Eclipse Adoptium" "%ProgramFiles%\Java" "%ProgramFiles%\Microsoft" "%ProgramFiles%\Zulu" "%ProgramFiles%\Amazon Corretto" "%LOCALAPPDATA%\Programs\Eclipse Adoptium") do (
    if exist "%%~D" for /d %%J in ("%%~D\jdk-2*" "%%~D\jdk2*" "%%~D\zulu-2*" "%%~D\jdk21*") do (
      if exist "%%~J\bin\javac.exe" set "JDK=%%~J"
    )
  )
)
if not defined JDK (
  for /f "delims=" %%P in ('where javac 2^>nul') do if not defined JDK set "JDK=%%~dpP.."
)
if not defined JDK (
  echo No Java Development Kit 21 or newer was found.
  echo.
  echo Install "Eclipse Temurin JDK 21" (free) from https://adoptium.net/temurin/releases/?version=21
  echo  - pick Windows x64, JDK, .msi, and tick "Set JAVA_HOME" during setup -
  echo then double-click this file again.
  echo.
  pause
  exit /b 1
)
set "JAVA_HOME=%JDK%"
set "PATH=%JDK%\bin;%PATH%"
echo Using Java at %JDK%
"%JDK%\bin\java.exe" -version
echo.

call gradlew.bat build --no-daemon
if errorlevel 1 (
  echo.
  echo The build FAILED. Scroll up for the error, or send a screenshot of it.
  pause
  exit /b 1
)

set "OUT="
for %%F in (build\libs\atlasclient-*.jar) do (
  echo %%~nF | findstr /i "sources dev" >nul || set "OUT=%%F"
)
if not defined OUT (
  echo Build finished but the JAR was not found in build\libs.
  pause
  exit /b 1
)
copy /y "%OUT%" "atlasclient-latest.jar" >nul
echo.
echo ============================================================
echo  Done: atlasclient-latest.jar is in this folder.
echo  Put it in .minecraft\mods (with Fabric API) and start 1.21.11.
echo ============================================================
explorer /select,"%CD%\atlasclient-latest.jar"
pause
