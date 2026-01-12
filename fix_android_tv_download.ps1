# Script para solucionar descarga de Android TV System Image
# Ejecutar como Administrador

$sdkPath = "C:\Users\luigu\AppData\Local\Android\Sdk"

Write-Host "=== Limpiando caché del SDK ===" -ForegroundColor Yellow

# Limpiar carpeta temp si existe
if (Test-Path "$sdkPath\temp") {
    Write-Host "Eliminando carpeta temp..." -ForegroundColor Cyan
    Remove-Item -Path "$sdkPath\temp" -Recurse -Force -ErrorAction SilentlyContinue
}

# Limpiar archivos .tmp
Write-Host "Eliminando archivos .tmp..." -ForegroundColor Cyan
Get-ChildItem -Path $sdkPath -Filter "*.tmp" -Recurse -ErrorAction SilentlyContinue | Remove-Item -Force

# Limpiar descargas incompletas de system-images
$systemImagesPath = "$sdkPath\system-images\android-36\android-tv\x86"
if (Test-Path $systemImagesPath) {
    Write-Host "Limpiando descargas incompletas..." -ForegroundColor Cyan
    Get-ChildItem -Path $systemImagesPath -Filter "*.zip" -ErrorAction SilentlyContinue | Remove-Item -Force
    Get-ChildItem -Path $systemImagesPath -Filter "*.tmp" -ErrorAction SilentlyContinue | Remove-Item -Force
}

Write-Host "`n=== Caché limpiada ===" -ForegroundColor Green
Write-Host "`nAhora intenta descargar desde Android Studio:" -ForegroundColor Yellow
Write-Host "1. Tools > SDK Manager" -ForegroundColor White
Write-Host "2. SDK Platforms > Show Package Details" -ForegroundColor White
Write-Host "3. Android TV > Android 36.0 > Android TV Intel x86 Atom System Image" -ForegroundColor White
Write-Host "`nO ejecuta este comando en PowerShell (como Admin):" -ForegroundColor Yellow
Write-Host "cd `"$sdkPath\cmdline-tools\latest\bin`"" -ForegroundColor Cyan
Write-Host ".\sdkmanager.bat `"system-images;android-36;android-tv;x86`"" -ForegroundColor Cyan

