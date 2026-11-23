# Táctica LEON

App de regata para el **LEON (ESP‑1222, Azuree 41)**: polar ORC 2026, asistente de virada, velas por tramo, temporizador de salida y línea, lectura de instrucciones de regata.

- `www/index.html`: la app (la misma que la versión web).
- `android/`: proyecto Android (Capacitor 6) con el plugin `BoatNet`, que lee NMEA 0183 de la Actisense W2K‑1 (192.168.4.1:60003, TCP o UDP) y sincroniza los dispositivos por difusión UDP (puerto 47474) en la WiFi del barco.
- Cada `push` a `main` compila el APK en GitHub Actions y lo publica en **Releases**.

La clave `android/app/leon-debug.keystore` es una clave de depuración (contraseña `android`), solo para que cada versión se instale encima de la anterior. No sirve para publicar en Google Play.
