# Solución para descargar Android TV System Image

## Problema
Error: "Not in GZIP format" al descargar Android TV Intel x86 Atom System Image API 36

## Soluciones (en orden de recomendación)

### Solución 1: Limpiar caché del SDK Manager
1. Cierra Android Studio completamente
2. Ve a la carpeta del SDK: `C:\Users\luigu\AppData\Local\Android\Sdk`
3. Elimina o renombra la carpeta `temp` dentro del SDK
4. Elimina archivos `.tmp` en la carpeta del SDK
5. Reinicia Android Studio y vuelve a intentar la descarga

### Solución 2: Ejecutar Android Studio como Administrador
1. Cierra Android Studio
2. Click derecho en el icono de Android Studio
3. Selecciona "Ejecutar como administrador"
4. Intenta descargar nuevamente

### Solución 3: Invalidar caché de Android Studio
1. En Android Studio: `File` > `Invalidate Caches / Restart`
2. Selecciona `Invalidate and Restart`
3. Después de reiniciar, intenta descargar nuevamente

### Solución 4: Descarga manual (si las anteriores fallan)
1. Descarga manualmente desde: https://dl.google.com/android/repository/sys-img/android-tv/x86-36_r03.zip
2. Extrae el contenido
3. Copia la carpeta extraída a: `C:\Users\luigu\AppData\Local\Android\Sdk\system-images\android-36\android-tv\x86\`
4. Reinicia Android Studio

### Solución 5: Usar línea de comandos del SDK Manager
Abre PowerShell como administrador y ejecuta:
```powershell
cd "C:\Users\luigu\AppData\Local\Android\Sdk\cmdline-tools\latest\bin"
.\sdkmanager.bat "system-images;android-36;android-tv;x86"
```






