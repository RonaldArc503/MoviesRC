# Plan de mejoras para Google TV — MoviesRC

Fecha: 18 de octubre de 2026. Base: [auditoría](AUDITORIA_GOOGLE_TV_2026-10-18.md) del directorio de trabajo actual, que ya tenía cambios locales antes de este plan.

## Objetivo

Lograr que la app se pueda abrir, recorrer, buscar y reproducir de forma predecible con un control remoto Google TV, con una primera pantalla rápida, audio/video estables y un paquete de publicación verificable.

## Fase 1 — Correcciones aplicables al código actual

| Cambio | Estado | Criterio de aceptación |
| --- | --- | --- |
| Banner TV horizontal 320 × 180 con marca y nombre | Aplicado | `android:banner` apunta a `banner_tv.png`; recurso revisado visualmente. Confirmar en launcher físico. |
| Acceso específico para TV | Aplicado | Layout de TV con campos y botones grandes, foco visible y recorrido DPAD; confirmar teclado y Google Sign-In en TV. |
| Primera fila de Home sin esperar todo el catálogo | Aplicado | Se presenta la fila destacada al recibirla y luego el catálogo completo; vista parcial no se persiste como caché final. Medir arranque y foco. |
| Búsqueda con tiempo total acotado | Aplicado | Las cinco fuentes comparten un plazo global de cinco segundos y un pool de cinco hilos; probar fuentes lentas y consultas consecutivas. |
| Pausa al dejar el reproductor web | Aplicado | En `onPause` se envía pausa a frames TuDorama y al contenido HTML5/JW accesible; confirmar audio con Home/Back en proveedores reales. |
| Restringir acceso `file://` en WebView | Aplicado | Se desactivó acceso cruzado desde archivos locales; comprobar todos los proveedores porque el contenido mixto sigue permitido por compatibilidad. |

Validación de esta fase: revisión del recurso de imagen, XML, JavaScript, compilación debug, tests unitarios disponibles y prueba manual TV del recorrido descrito abajo. Un build correcto no sustituye la prueba de reproducción.

### Validación realizada en este entorno

- Banner PNG revisado visualmente y confirmado a 320 × 180 px.
- Manifest, layout y selector nuevos: XML bien formado. Bridge TuDorama: `node --check` correcto.
- Compilación y tests **no concluyentes**: Gradle pudo iniciar, pero falló antes de compilar con `Unable to establish loopback connection`, incluso al usar la caché local con permiso ampliado. AAPT2 también terminó de forma anómala (`-1073741819`) en este entorno; no cuenta como validación de recursos.
- ADB tampoco pudo iniciar su daemon local, por lo que no hubo instalación ni prueba en Google TV física.

## Fase 2 — Prueba de campo y correcciones por evidencia

1. Instalar un APK generado a partir del estado actual en una Google TV física; registrar modelo, versión Android TV, RAM y versión de la app.
2. Recorrer **login → Home → búsqueda → ficha → reproductor nativo/web → volver** solo con DPAD. Registrar cualquier elemento sin foco, salto de foco o teclado que no se abra.
3. Medir en caché vacía y caliente: tiempo de apertura a primera fila, tiempo hasta foco utilizable, tiempo de búsqueda y tiempo a primer fotograma. Comparar antes/después en el mismo equipo y red.
4. Probar MP4/HLS y cada proveedor web activo: play/pause, seek, subtítulos, audio, reanudación, cambio de servidor, siguiente episodio, Home/Back y retorno.
5. Durante 30 minutos de reproducción y navegación, capturar cierres, memoria, frames lentos y carga de CPU. Corregir los cuellos confirmados; ajustar precarga de posters, carrusel y cachés según esos resultados.

**Salida de fase:** tabla de dispositivos/fuentes con aprobado o fallo reproducible, logs útiles y prioridades actualizadas. Si el WebView no puede controlar un iframe de forma fiable, ofrecer una fuente alternativa visible antes de cambiar arquitectura.

## Fase 3 — Preparación de release

1. Crear variante de release con Lint obligatorio y resolver advertencias relevantes para TV. Activar minificación y reducción de recursos de forma gradual, verificando Firebase, Media3, Cast y scrapers después de cada cambio.
2. Limitar tráfico HTTP y contenido mixto a los orígenes que una prueba demuestre necesarios; revisar `networkSecurityConfig`, `data_extraction_rules` y `backup_rules` para proteger datos de cuenta e historial.
3. Verificar banner/ícono, acceso de revisión de Google Play, instalación desde AAB y requisitos vigentes de 64 bits y páginas de 16 KB. No declarar aptitud para Play hasta completar esta revisión.
4. Revisar si `minSdk 31` cubre los modelos objetivo; bajarlo solo si se prueban APIs, dependencias y un dispositivo anterior.

## Fase 4 — Experiencia TV ampliada

- Inicio de sesión por código/QR enlazado a una cuenta existente, después de diseñar y asegurar el flujo de autorización entre móvil y TV.
- Búsqueda por voz y `MediaSession` para comandos de reproducción.
- Integración de “seguir viendo” con superficies del sistema TV si se va a distribuir ampliamente.
- Baseline Profiles y optimización de imágenes después de obtener una línea base de rendimiento.

## Riesgos y decisiones

- El directorio contiene numerosos cambios locales previos. Conservarlos y revisar el diff de cada archivo tocado antes de integrar.
- El acceso a proveedores externos puede cambiar sin actualización de la app. Mantener fallback visible y pruebas por proveedor.
- El acceso por QR requiere un flujo de servidor/autenticación nuevo; no se implementa como pantalla simulada.
- No se debe llamar “optimizada” a la app hasta verificar dispositivos TV reales y fuentes de reproducción activas.
