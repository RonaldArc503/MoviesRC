# SisFootball

Descripción:
Esta skill contendrá indicaciones para que Codex/Ejecuciones automáticas interactúen con la app (por ejemplo, reproducir partidos, cambiar fuentes o extraer información de reproducción).

Instrucciones (escribe aquí):
- Objetivo: 
- Entradas esperadas: 
- Pasos a ejecutar: 

Ejemplo rápido:
- Objetivo: reproducir el último partido de "Equipo A" en calidad alta.
- Entradas: `team: Equipo A`, `quality: high`
- Pasos:
  1. Buscar el `postId` del partido en el backend.
  2. Llamar a `AllCalidadScraper.getPlayer(postId)`.
  3. Obtener `getPlayableUrls(...)` y seleccionar la URL `.m3u8` preferida.
  4. Iniciar `PlayerExoActivity` con la lista de URLs.

Notas:
- Guarda aquí las variantes y flags necesarios (referer, origin, headers especiales).
- Puedes usar YAML o texto libre; yo puedo convertir ejemplos a `.yaml` si lo prefieres.
