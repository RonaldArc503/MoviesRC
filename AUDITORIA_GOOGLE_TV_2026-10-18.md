# Auditoría de MoviesRC para Google TV — 18 de octubre de 2026

## Dictamen

**La app está preparada para abrir y navegar en TV, pero todavía no puede calificarse como optimizada para Google TV.** El manifiesto declara `LEANBACK_LAUNCHER`, pantalla táctil opcional y un diseño específico para televisión; inicio, fichas y ambos reproductores incluyen manejo de foco o teclas del control remoto. Los principales pendientes son el tiempo de carga del catálogo, la experiencia de acceso, el banner del launcher, la estabilidad del reproductor web y la medición real en hardware de TV.

Esta es una auditoría estática del estado actual del directorio de trabajo, que contiene cambios locales sin confirmar. No se modificó código. No hubo Google TV conectada para comprobar fluidez, audio, video, foco o consumo de memoria; `adb` no estuvo disponible en el entorno de esta auditoría. La existencia de un APK en `app/release/` no demuestra que represente este código ni que funcione en un televisor.

## Lo que ya está bien encaminado

| Área | Evidencia | Evaluación |
| --- | --- | --- |
| Declaración TV | `app/src/main/AndroidManifest.xml:5-11,26-33` | Leanback y pantalla táctil opcional declarados. |
| Diseño TV | `app/src/main/res/layout-television/activity_home.xml:19-82,218-295`; `app/src/main/res/layout-television/activity_detail.xml:209-264` | Hay interfaz de 10 pies, menú y rutas explícitas de foco. |
| Foco en inicio | `app/src/main/java/sv/edu/catolica/rex/ui/home/HomeActivity.java:155-170,1589-1617,1689-1699` | Se guarda/restaura foco y se gestionan carrusel y secciones. |
| Reproductor nativo | `app/src/main/java/sv/edu/catolica/rex/ui/player/PlayerExoActivity.java:1939-2047,2066-2100` | Soporta DPAD, teclas multimedia, pausa en segundo plano y libera recursos. |
| Reproductor web | `app/src/main/java/sv/edu/catolica/rex/ui/player/PlayerContenidoActivity.java:2767-2902,2906-2954` | Controles propios con DPAD, guardado de progreso y destrucción de WebView. |
| Datos inmediatos y caché | `app/src/main/java/sv/edu/catolica/rex/ui/home/HomeActivity.java:748-781`; `app/src/main/java/sv/edu/catolica/rex/ui/player/PlaybackCacheHelper.java:37,78-91` | Muestra datos locales cuando existen y limita caché de video a 40 MB. |

## Hallazgos priorizados

### P0 — Banner de TV incorrecto para el launcher

`app/src/main/AndroidManifest.xml:17,37` usa `@mipmap/ic_launcher` como `android:banner`; en recursos solo aparecen variantes del icono `ic_launcher`, sin un banner TV específico. Google pide un banner horizontal de 320 × 180 px con el nombre de la app. Esta configuración puede mostrar una imagen cuadrada o recortada y no satisface de forma verificable el criterio visual del launcher. Crear un banner 16:9 por idioma y asignarlo a la aplicación y a la actividad de entrada. Referencia: [Google TV app quality, TV-LB/TV-BN](https://developer.android.com/docs/quality-guidelines/tv-app-quality).

### P1 — El catálogo espera demasiadas fuentes antes de mostrarse

`app/src/main/java/sv/edu/catolica/rex/repository/HomeRepository.kt:22-68` inicia 14 consultas en paralelo, pero espera los resultados de todas antes de construir las secciones y emitir `Success` en `HomeViewModel.kt:40-51`. En primera instalación o tras expirar caché, una fuente lenta puede alargar el spinner aunque otras ya hayan respondido. El uso de `Dispatchers.IO` evita bloquear directamente la UI, pero no reduce el tiempo percibido. Propuesta: mostrar primero secciones esenciales, agregar las demás de forma incremental, poner plazos por fuente, limitar concurrencia y medir tiempo de primera fila y tiempo hasta foco utilizable.

### P1 — Inicio de sesión poco adaptado a TV

La actividad inicial es `LoginActivity` (`AndroidManifest.xml:26-33`) y utiliza el mismo `activity_login.xml` de teléfono en TV (`LoginActivity.java:39-70`). La entrada principal exige escribir correo y contraseña o completar un flujo de Google Sign-In. Hay foco básico en dos enlaces (`activity_login.xml:107-121`), pero no un layout de TV ni activación por código/QR desde el móvil. Propuesta: pantalla TV con foco y estados visibles, acceso mediante código/QR y comprobación de Google Sign-In con control remoto. La sesión ya iniciada salta al inicio (`LoginActivity.java:42-46`). Referencia: [Google TV app quality, TV-LI/TV-LC](https://developer.android.com/docs/quality-guidelines/tv-app-quality).

### P1 — Reproducción web dependiente de proveedor y con superficie de riesgo amplia

`PlayerContenidoActivity.java:691-735` activa JavaScript, cookies de terceros, ventanas múltiples, acceso universal desde URL `file://`, contenido mixto HTTP/HTTPS y un puente JavaScript. El control del video se realiza por inyección y `evaluateJavascript` (`PlayerContenidoActivity.java:1043,1517,3077-3083`), de modo que cambios en iframes o proveedores pueden romper pausa, seek, reanudación o audio. `onPause()` pausa el WebView (`2941-2954`), pero aquí no se ve una orden explícita de pausar el medio antes de salir; comprobar en TV que no siga sonando. Propuesta: preferir URL reproducible con Media3 cuando se pueda, mantener fallback visible por servidor, restringir orígenes y capacidades WebView a lo imprescindible, y probar cada proveedor con control remoto. Esto es un riesgo de robustez y seguridad deducido del código, no una falla observada en un televisor.

### P1 — Falta una prueba real de calidad TV

Los tests encontrados son principalmente unitarios de fuentes, sincronización y progreso; `app/src/androidTest/.../ExampleInstrumentedTest.java` es la única prueba instrumentada localizada. No se encontraron pruebas automatizadas del recorrido completo con DPAD, foco, reproducción, cambio de fuente y retorno desde segundo plano. Sin datos de Google TV física no puede afirmarse velocidad de arranque, FPS, estabilidad de decodificación, consumo RAM ni compatibilidad de audio. Propuesta: probar al menos una Google TV de memoria limitada y una de gama media, capturar Perfetto/Memory Profiler, tiempo hasta primera fila, frames lentos, errores de reproducción y memoria durante 30 minutos. Referencia: [Google TV app quality, TV-LS/TV-DP/TV-ME](https://developer.android.com/docs/quality-guidelines/tv-app-quality).

### P2 — Búsqueda puede acumular espera y trabajo de red

`UnifiedSearchService.java:28-29,76-179,199-205` usa un pool sin límite fijo, lanza cinco fuentes y espera cada `Future` secuencialmente con 5 segundos. Aunque se inician juntas, si varias no terminan, la suma de esperas puede llegar aproximadamente a 25 segundos; se cancelan al vencer el plazo, sin garantía de que toda llamada de red termine inmediatamente. `SearchActivity.java:145-157` ejecuta esa búsqueda en un hilo separado, por lo que el problema es latencia y consumo, no bloqueo directo de UI. Propuesta: plazo total único, límite de concurrencia, resultados parciales y cancelación efectiva al cambiar de consulta.

### P2 — Ajustes de publicación y seguridad

`app/build.gradle:39-47` publica sin minificación y desactiva la comprobación de Lint de release; eso agranda el binario y permite que advertencias lleguen al APK. `AndroidManifest.xml:16-25` permite backup y tráfico HTTP sin una política de red específica; `res/xml/data_extraction_rules.xml` y `backup_rules.xml` siguen como plantillas. Revisar qué datos locales de historial/autenticación se respaldan y limitar HTTP a los dominios estrictamente necesarios. El `minSdk 31` (`app/build.gradle:29`) sí cumple el criterio actual de Google TV (31 o inferior), pero excluye televisores con Android 11 o anterior si ese público importa. No bajar el mínimo sin verificar APIs y dependencias. Referencia: [Google TV app quality, TV-PS](https://developer.android.com/docs/quality-guidelines/tv-app-quality).

### P3 — Mejoras de experiencia posteriores

- Búsqueda por voz y comandos multimedia con `MediaSession`; actualmente se ve entrada por teclado y gestión manual de teclas en `SearchActivity.java:67-124` y los reproductores. Referencia: [Google TV app quality, TV-VS/TV-VC](https://developer.android.com/docs/quality-guidelines/tv-app-quality).
- Integración de “seguir viendo” con superficies de Google TV si se planea distribución amplia; hoy el progreso está en la app (`HomeActivity.java:824-835`). Referencia: [Google TV app quality, TV-EC](https://developer.android.com/docs/quality-guidelines/tv-app-quality).
- Perfilar antes de ajustar precarga de imágenes y filas: el inicio configura caché de 8 vistas para TV (`HomeActivity.java:138`), cada fila precarga 8 tarjetas (`HomeAdapter.java:153`) y el carrusel pide imágenes de 1280 × 720 (`HeroPagerAdapter.java:233`). Puede ser adecuado o pesado según RAM y GPU; no hay medición que lo resuelva.

## Matriz mínima de validación en Google TV

1. Instalar versión generada del estado actual y comprobar icono/banner, apertura y regreso al launcher.
2. Cuenta nueva y recurrente: recorrer todo el login con DPAD y teclado en pantalla; probar error y recuperación.
3. Arranque con caché vacía, caché caliente, red lenta y sin red; medir tiempo a primera fila y a primer foco seleccionable.
4. Moverse por todas las filas, abrir una ficha, volver y confirmar que el foco regresa al elemento anterior.
5. Buscar por teclado; repetir consultas rápidamente y confirmar que no aparecen resultados antiguos.
6. Reproducir MP4/HLS en Media3 y al menos una fuente web de cada proveedor activo: play/pause, seek, subtítulos, audio, siguiente episodio, error/fallback, volver y reanudar.
7. Salir con Home durante reproducción: confirmar que video/audio se pausan; volver y comprobar progreso local y Firebase.
8. Dejar la app 30 minutos navegando y reproduciendo; medir RAM, frames lentos, temperatura y cierres del WebView en una TV con poca memoria.

## Orden recomendado

1. Corregir banner y acceso TV.
2. Mostrar Home incrementalmente y acotar espera de búsqueda.
3. Validar y endurecer reproducción web; mejorar fallback y pausa real al salir.
4. Habilitar Lint de release, revisar backups/red y perfilar en hardware.
5. Agregar voz, integración con Google TV y ajustes finos solo después de medir.

## Límite del dictamen

Este informe identifica comportamiento visible en código y riesgos probables. No certifica aprobación de Google Play, compatibilidad con todos los modelos Google TV ni rendimiento sostenido; esos puntos dependen de una compilación identificable y de pruebas en dispositivo real.
