@echo off
rem SPDX-License-Identifier: GPL-2.0-or-later
rem Compila Sirga Studio PC con Visual Studio o Build Tools (C++ y SDK de Windows 10/11).
rem Resultado: pc\build\SirgaStudioPC.exe

setlocal
set "VSWHERE=%ProgramFiles(x86)%\Microsoft Visual Studio\Installer\vswhere.exe"
if not exist "%VSWHERE%" goto novs
for /f "usebackq tokens=*" %%i in (`call "%VSWHERE%" -latest -products * -requires Microsoft.VisualStudio.Component.VC.Tools.x86.x64 -property installationPath`) do set "VSDIR=%%i"
if not defined VSDIR goto novs
call "%VSDIR%\VC\Auxiliary\Build\vcvars64.bat" >nul 2>nul || exit /b 1

cd /d "%~dp0"
cmake -S . -B build -G Ninja -DCMAKE_BUILD_TYPE=Release || exit /b 1
cmake --build build || exit /b 1
echo.
echo Listo: %~dp0build\SirgaStudioPC.exe
exit /b 0

:novs
echo No se encuentra Visual Studio con C++. Instala "Build Tools para Visual Studio" con "Desarrollo para el escritorio con C++".
exit /b 1
