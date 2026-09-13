@echo off
where gradle >nul 2>nul
if errorlevel 1 (
  echo No se encontro Gradle. Abre el proyecto en Android Studio o usa el workflow de GitHub Actions incluido.
  exit /b 1
)
gradle %*
