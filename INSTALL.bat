@echo off
setlocal enabledelayedexpansion
cd /d "%~dp0"

REM ---------------------------------------------------------------
REM  SAMAQU - one-click installer
REM  Installs the Lite APK over USB and prints the REAL error code
REM  from Android (the phone UI only ever says "App not installed").
REM ---------------------------------------------------------------

set "TC=%~dp0..\poc-samaqu-keyboard\.toolchain"
set "ADB=%TC%\android-sdk\platform-tools\adb.exe"
set "APK=%~dp0SAMAQU-Lite-v1.0.1.apk"

echo ===============================================
echo   SAMAQU - Installer (Lite build)
echo ===============================================
echo.

if not exist "%ADB%" (
    echo [X] adb tidak ditemukan:
    echo     %ADB%
    echo     Pastikan folder poc-samaqu-keyboard\.toolchain masih ada.
    goto :end
)

if not exist "%APK%" (
    echo [X] APK tidak ditemukan:
    echo     %APK%
    goto :end
)

echo [1/3] Mencari HP...
"%ADB%" start-server >nul 2>&1
"%ADB%" devices -l

echo.
echo     Pastikan HP muncul di daftar di atas dalam status "device".
echo     Kalau tertulis "unauthorized", cek layar HP lalu tap "Allow".
echo     Kalau daftar kosong, nyalakan USB debugging dan colok kabelnya.
echo.
pause

echo [2/3] Menginstal...
echo.
"%ADB%" install -r "%APK%"
set "RC=%ERRORLEVEL%"
echo.

if "%RC%"=="0" (
    echo [OK] Instalasi BERHASIL.
    echo.
    echo Selanjutnya di HP:
    echo   1. Settings - Additional settings - Languages ^& input
    echo   2. Manage keyboards - nyalakan "SAMAQU Lite"
    echo   3. TAP "OK" di popup peringatan (wajib!)
    echo   4. Saat mengetik, pilih SAMAQU Lite dari ikon keyboard
) else (
    echo [X] Instalasi GAGAL dengan kode %RC%
    echo     Baca baris error di atas ^(mis. INSTALL_FAILED_...^)
    echo.
    echo     INSTALL_FAILED_USER_RESTRICTED        -^> MIUI membatasi adb.
    echo         Developer options: nyalakan "Install via USB" dan
    echo         matikan "Turn on MIUI optimization" ^(perlu restart^).
    echo     INSTALL_FAILED_INSUFFICIENT_STORAGE   -^> penyimpanan HP penuh.
    echo     INSTALL_PARSE_FAILED_*                -^> file APK rusak, kirim ulang.
    echo     INSTALL_FAILED_UPDATE_INCOMPATIBLE    -^> app dengan nama sama sudah
    echo         terpasang dengan tanda tangan beda; uninstall dulu.
)

echo [3/3] Menyiapkan logcat (tekan Ctrl+C untuk keluar)...
echo.
pause
"%ADB%" logcat -c
"%ADB%" logcat | findstr /I "samaqu AndroidRuntime PackageManager"

:end
echo.
pause
endlocal
