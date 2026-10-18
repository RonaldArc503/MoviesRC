# Plan de recuperación de AllCalidad

Fecha: 18 de octubre de 2026  
Estado: plan; implementación pendiente

## Diagnóstico que guía el trabajo

- El log del dispositivo registra HTTP 404 para `https://tmdb.allcalidad.re/v1/top?kind=movie&page=1&limit=8` y `https://tmdb.allcalidad.re/v1/search?q=La+Odisea&page=1`. La comprobación en navegador también devolvió una página 404 de cPanel para esas rutas y para `/v1/items?kind=movie&page=1&limit=16`.
- La web actual sí mostró resultados en `/search/la%20odisea`, una película en `/peliculas/la-odisea-2026`, una serie en `/series/frasier-1993` y un capítulo en `/episodio/frasier-temporada-1-episodio-1`.
- El HTML declara `https://allcalidad.re/wp-json/wpf/v1` y `https://allcalidad.re/api/rest`. El índice REST de `wpf/v1` anuncia `/episodes`, pero no anuncia sustitutos directos de `/search`, `/top`, `/items` o `/player`; `/episodes` sin ID responde `invalid post id`. El significado de `api/rest` y los parámetros reales todavía requieren verificación.
- `AllCalidadScraper.java` usa un único `API_BASE` antiguo para catálogo, búsqueda, temporadas, reproducción, relacionados, visitas y calificación. Además construye fichas con `/pelicula/`, `/serie/` y `/anime/`, que no coinciden con las rutas observadas. `getPlayer()` puede fabricar un embed de Vimeos a partir de un código en caché; ese supuesto debe volver a comprobarse.
- El repositorio tiene cambios locales previos en el scraper, su integración y otros archivos. La implementación deberá trabajar sobre ese estado y conservar los cambios ajenos a AllCalidad.

## Alcance

Recuperar la búsqueda, las filas de inicio que provienen de AllCalidad, las fichas, temporadas, episodios y servidores reproducibles de AllCalidad. Mantener la arquitectura `AllCalidadScraper` → `AllCalidadSource`/repositorios → detalle/reproductor. No ampliar el trabajo a otros proveedores ni cambiar los controles o subtítulos del reproductor salvo por un fallo demostrado durante la integración.

## Fase 1. Contrato actual y matriz de rutas

1. Observar en el navegador las solicitudes que realiza la web al cargar inicio, películas, series, búsqueda, ficha de película, ficha de serie, lista de episodios y reproductor. Registrar URL, método, parámetros, código HTTP, tipo de respuesta y campos necesarios. No inferir una ruta a partir de su nombre.
2. Verificar si `api/rest` sirve datos de catálogo, si `wpf/v1/episodes` acepta un ID de publicación y cómo se obtiene ese ID desde una ficha. Documentar por separado ID interno del sitio, ID de TMDb, slug y URL canónica.
3. Guardar muestras pequeñas y saneadas de respuestas o HTML para pruebas; no guardar cookies, credenciales ni URLs privadas de sesión.
4. Completar esta matriz antes de programar. Marcar como `confirmada`, `sin equivalente` o `pendiente` cada operación:

   | Operación actual | Ruta antigua en el scraper | Dato que debe entregar el reemplazo |
   | --- | --- | --- |
   | Películas/series recientes | `/v1/items?kind=...` | Título, año, tipo, imagen, URL, IDs y paginación |
   | Destacados/populares | `/v1/top?kind=...` | Lista ordenada con criterio verificable |
   | Búsqueda | `/v1/search?q=...` | Resultados y página solicitada |
   | Ficha/relacionados | `/v1/items/movie/{id}`, `/v1/related` | Metadatos y vínculos canónicos |
   | Temporadas/capítulos | `/v1/items/tvshow/{id}/seasons[/n]` | Temporada, número de capítulo e ID/URL real del episodio |
   | Servidores | Código de `/v1/items/movie/{id}` | URLs de embed comprobadas para película o episodio |
   | Visitas/calificación | `/v1/hit`, `/v1/rating` | Confirmar si aún hacen falta; nunca bloquear contenido por ellas |

**Puerta de decisión:** si no existe una API JSON estable, usar las páginas públicas verificadas como fuente para catálogo, búsqueda y fichas, con Jsoup ya incluido en el proyecto. Solo ofrecer reproducción cuando la ficha o una respuesta confirmada entregue servidores reales. Una ruta web de ficha no se tratará como enlace de vídeo.

## Fase 2. Adaptar AllCalidadScraper

1. Centralizar las rutas y el transporte de AllCalidad dentro del scraper o un cliente pequeño de ese proveedor. Quitar las llamadas a `/v1` cuando su reemplazo esté validado. Conservar tiempos de espera y control de errores, pero no reintentar 404 permanentes.
2. Adaptar `getMovies`, `getFeaturedMovies`, `getTvShows`, `getPopularMovies`, `getPopularTvShows` y `search` al contrato confirmado. Mantener su salida `ContentItem` para reducir cambios en `HomeRepository`, `UnifiedSearchService` y `AllCalidadSource`.
3. Usar la URL canónica que entregue el sitio; si hay que construirla, respetar `/peliculas/{slug}`, `/series/{slug}`, `/animes/{slug}` y `/episodio/{slug}` según el tipo. Conservar paginación y normalización de imágenes.
4. Separar explícitamente `postId` de AllCalidad y `tmdbId`. No copiar uno al otro sin evidencia de que son iguales. Revisar claves de caché y coincidencia título/año/tipo para no abrir otra obra con nombre parecido.
5. Adaptar `getSeasons` y `getPlayer` a IDs o URLs reales de episodios. No usar el ID de la serie para reproducir un capítulo, ni fabricar Vimeos cuando el código actual no esté confirmado. Devolver una lista vacía con causa registrable si no hay servidor.
6. Tratar relacionados, rating e hit como secundarios. Si no tienen equivalente vigente, desactivar solo esas llamadas y conservar catálogo y reproducción funcionales.

## Fase 3. Integración y cachés

1. Revisar `AllCalidadSource`, `SmartScraperEngine`, `DetalleActivity`, `HomeRepository` y `PlayerExoActivity` para consumir correctamente los nuevos IDs y URLs sin alterar la prioridad ni las rutas de otros proveedores.
2. Distinguir respuestas nuevas de servidores antiguos guardados en Firebase o caché local. Un enlace cacheado puede seguir funcionando, pero no debe contar como prueba de que la búsqueda y resolución actuales funcionan.
3. Invalidar únicamente entradas incompatibles de AllCalidad. Preservar progreso de reproducción, favoritos y datos del usuario. Mantener visible el fallback existente cuando AllCalidad no entregue servidores.
4. Registrar errores útiles: operación, URL sin datos sensibles, HTTP/parseo, y si se usó caché o fallback. Evitar que un 404 se convierta silenciosamente en una lista vacía indistinguible de un catálogo vacío.

## Fase 4. Verificación y cierre

1. Añadir pruebas de parser con las muestras verificadas: búsqueda, paginación, tipos, URL canónica, IDs separados, temporadas/episodios, servidores y respuesta 404/no JSON. Revisar la prueba existente `AllCalidadScraperTest.java` sin sustituir pruebas reales por expectativas inventadas.
2. Ejecutar `./gradlew.bat :app:testDebugUnitTest :app:assembleDebug` y revisar el diff para asegurar que los cambios quedan limitados a AllCalidad y sus puntos de integración necesarios.
3. Probar en la app una búsqueda sin resultado precargado, una película, una serie y un episodio; verificar imágenes, ficha correcta, temporadas y servidor. Repetir al menos una película y un episodio en el dispositivo real hasta confirmar reproducción, pausa, seek y reanudación.
4. Confirmar en logs que esas operaciones ya no solicitan `tmdb.allcalidad.re/v1`, que no dependen de un enlace previamente guardado y que el fallback sigue apareciendo cuando AllCalidad falla.

## Criterios de aceptación

- Inicio y búsqueda muestran contenido de AllCalidad actual con URL e identificadores correctos.
- Una película y un episodio recién resueltos entregan un servidor válido y se reproducen en el dispositivo.
- Las temporadas y capítulos coinciden con la ficha pública; cambiar de episodio no reutiliza el ID de la serie.
- Ninguna operación activa consulta la API `/v1` retirada; un fallo real muestra una causa y mantiene el fallback.
- Las pruebas y el build pasan; el progreso, subtítulos y otros proveedores conservan su comportamiento.

## Riesgos por comprobar

- La web puede entregar datos mediante llamadas no documentadas o cambiar sus respuestas; por eso la fase 1 es obligatoria antes de elegir JSON o HTML.
- Los IDs de WordPress, TMDb y los códigos de reproductor pueden representar entidades distintas.
- Un APK compilado o un enlace de Firebase funcional no demuestra que AllCalidad vuelva a resolver contenido nuevo; el cierre requiere prueba de reproducción real.
