/**
 * extract_tudorama_catalog.js
 * 
 * Extrae el catálogo completo de Doramas en Español Latino desde https://tudorama.com/genero/espanol-latino/
 * incluyendo metadatos, TMDB ID, póster, sinopsis, y la lista completa de todos los episodios
 * paginando con la API interna de Wstream/Corvus.
 * 
 * Genera: doramas_latino.json
 */

const fs = require('fs');
const path = require('path');

const BASE_URL = 'https://tudorama.com';
const CATEGORY_URL = 'https://tudorama.com/genero/espanol-latino/';
const OUTPUT_FILE = path.join(__dirname, '..', 'doramas_latino.json');
const HEADERS = {
    'User-Agent': 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36',
    'Accept': 'text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8',
    'Accept-Language': 'es-ES,es;q=0.9,en;q=0.8'
};

async function fetchHtml(url) {
    const res = await fetch(url, { headers: HEADERS });
    if (!res.ok) {
        throw new Error(`HTTP ${res.status} para ${url}`);
    }
    return await res.text();
}

async function getCategoryDoramas() {
    const list = [];
    let page = 1;

    while (true) {
        const pageUrl = page === 1 ? CATEGORY_URL : `${CATEGORY_URL}page/${page}/`;
        console.log(`[CATALOG] Escaneando página ${page}: ${pageUrl}`);
        
        try {
            const res = await fetch(pageUrl, { headers: HEADERS });
            if (res.status === 404) {
                console.log(`[CATALOG] Fin de páginas en página ${page} (HTTP 404).`);
                break;
            }
            const html = await res.text();
            
            const articleRegex = /<article class="ipst">([\s\S]*?)<\/article>/g;
            let match;
            let pageCount = 0;

            while ((match = articleRegex.exec(html)) !== null) {
                const block = match[1];
                const linkMatch = block.match(/href="([^"]+)"/);
                const titleMatch = block.match(/<h3 class="ipst__title"><a [^>]+>([^<]+)<\/a>/);
                const imgMatch = block.match(/src="([^"]+)"/);
                const scoreMatch = block.match(/class="avg-score[^"]*">\s*<i[^>]*><\/i>\s*([0-9.]+)/);

                if (linkMatch && titleMatch) {
                    list.push({
                        detailUrl: linkMatch[1],
                        title: titleMatch[1].trim(),
                        posterUrl: imgMatch ? imgMatch[1] : '',
                        rating: scoreMatch ? parseFloat(scoreMatch[1]) : 0.0
                    });
                    pageCount++;
                }
            }

            console.log(`[CATALOG] Página ${page}: ${pageCount} doramas encontrados.`);
            if (pageCount === 0) break;
            page++;
        } catch (e) {
            console.error(`[ERROR] Fallo al leer página ${page}:`, e.message);
            break;
        }
    }

    return list;
}

async function extractDoramaDetails(item, index, total) {
    console.log(`\n[${index + 1}/${total}] Procesando: ${item.title} (${item.detailUrl})`);
    
    try {
        const html = await fetchHtml(item.detailUrl);
        
        // Metadatos
        const originalTitleMatch = html.match(/class="details__col col-name">Nombre original<\/div>\s*<div class="details__col">([^<]+)<\/div>/i);
        const altTitlesMatch = html.match(/class="details__col col-name">Títulos alternativos<\/div>\s*<div class="details__col">([^<]+)<\/div>/i);
        const yearMatch = html.match(/class="details__col col-name">Año<\/div>\s*<div class="details__col">([^<]+)<\/div>/i);
        const statusMatch = html.match(/class="details__col col-status"><span[^>]*>([^<]+)<\/span>/i);
        const backdropMatch = html.match(/<figure class="hero__backdrop">\s*<img[^>]+src="([^"]+)"/i);
        const posterMatch = html.match(/<div class="hero__poster">\s*<picture>\s*<img[^>]+src="([^"]+)"/i);
        const overviewMatch = html.match(/<p class="hero__overview">([\s\S]*?)<\/p>/i);
        
        // Genres
        const genres = [];
        const genreRegex = /href="https:\/\/tudorama\.com\/genres\/[^"]+">([^<]+)<\/a>/g;
        let gMatch;
        while ((gMatch = genreRegex.exec(html)) !== null) {
            genres.push(gMatch[1].trim());
        }

        // Cast
        const cast = [];
        const castRegex = /class="cod__item">\s*<a [^>]+title="([^"]+)"[\s\S]*?<img src="([^"]+)"/g;
        let cMatch;
        while ((cMatch = castRegex.exec(html)) !== null) {
            cast.push({ name: cMatch[1].trim(), photo: cMatch[2] });
        }

        // Slug y TMDb ID
        const slug = item.detailUrl.replace(BASE_URL, '').replace(/^\/serie\//, '').replace(/\/$/, '');
        const epsDivMatch = html.match(/class="eps"[^>]*data-tmdb-id="([^"]+)"[^>]*data-season-number="([^"]+)"[^>]*data-nonce="([^"]+)"[^>]*data-results="([^"]+)"/i);
        
        let tmdbId = 0;
        let nonce = '';
        let seasonNumber = 1;
        let resultsPerPage = 8;
        if (epsDivMatch) {
            tmdbId = parseInt(epsDivMatch[1], 10) || 0;
            seasonNumber = parseInt(epsDivMatch[2], 10) || 1;
            nonce = epsDivMatch[3] || '';
            resultsPerPage = parseInt(epsDivMatch[4], 10) || 8;
        }

        // Episodios iniciales del HTML
        const episodesMap = new Map();
        const epRegex = /<li class="lep"([^>]*)>([\s\S]*?)<\/li>/g;
        let epMatch;
        while ((epMatch = epRegex.exec(html)) !== null) {
            const block = epMatch[2];
            const aMatch = block.match(/href="([^"]+)"/);
            const titleMatch = block.match(/<h3 class="lep__title">([^<]+)<\/h3>/);
            const epNumMatch = block.match(/Episodio\s*(\d+)/i) || epMatch[1].match(/data-episode="(\d+)"/);
            const sNumMatch = block.match(/S(\d+)/i) || epMatch[1].match(/data-season="(\d+)"/);
            const thumbMatch = block.match(/<img[^>]+src="([^"]+)"/);

            if (aMatch && epNumMatch) {
                const epNum = parseInt(epNumMatch[1], 10);
                const sNum = sNumMatch ? parseInt(sNumMatch[1], 10) : seasonNumber;
                episodesMap.set(`${sNum}x${epNum}`, {
                    episodeNumber: epNum,
                    seasonNumber: sNum,
                    title: titleMatch ? titleMatch[1].trim() : `Episodio ${epNum}`,
                    thumbnail: thumbMatch ? thumbMatch[1] : '',
                    permalink: aMatch[1]
                });
            }
        }

        // Si hay paginación AJAX y tmdbId, consultar páginas restantes
        if (tmdbId > 0 && nonce) {
            let offset = resultsPerPage;
            let hasMore = true;

            while (hasMore) {
                try {
                    const form = new URLSearchParams();
                    form.append('action', 'corvus_get_episodes');
                    form.append('nonce', nonce);
                    form.append('post_id', tmdbId.toString());
                    form.append('season', seasonNumber.toString());
                    form.append('results', resultsPerPage.toString());
                    form.append('offset', offset.toString());
                    form.append('order', 'DESC');

                    const ajaxRes = await fetch(`${BASE_URL}/wp-admin/admin-ajax.php`, {
                        method: 'POST',
                        headers: {
                            ...HEADERS,
                            'Referer': item.detailUrl,
                            'Origin': BASE_URL,
                            'X-Requested-With': 'XMLHttpRequest',
                            'Content-Type': 'application/x-www-form-urlencoded'
                        },
                        body: form.toString()
                    });

                    const json = await ajaxRes.json();
                    if (json.success && json.data && Array.isArray(json.data.results) && json.data.results.length > 0) {
                        for (const ep of json.data.results) {
                            const epNum = parseInt(ep.episode_number, 10);
                            const sNum = parseInt(ep.season_number, 10) || seasonNumber;
                            const key = `${sNum}x${epNum}`;
                            if (!episodesMap.has(key)) {
                                episodesMap.set(key, {
                                    episodeNumber: epNum,
                                    seasonNumber: sNum,
                                    title: ep.title || `Episodio ${epNum}`,
                                    thumbnail: ep.episode_image || '',
                                    permalink: ep.permalink || ''
                                });
                            }
                        }
                        hasMore = !!json.data.hasMore;
                        offset += resultsPerPage;
                    } else {
                        hasMore = false;
                    }
                } catch (e) {
                    console.warn(`   [WARN] Error en paginación AJAX (offset ${offset}):`, e.message);
                    hasMore = false;
                }
            }
        }

        // Ordenar episodios por número ascendente
        const episodesList = Array.from(episodesMap.values()).sort((a, b) => {
            if (a.seasonNumber !== b.seasonNumber) return a.seasonNumber - b.seasonNumber;
            return a.episodeNumber - b.episodeNumber;
        });

        console.log(`   OK: TMDb=${tmdbId}, Episodios=${episodesList.length}, Estado=${statusMatch ? statusMatch[1].trim() : 'Desconocido'}`);

        return {
            id: `tudorama_${slug}`,
            tmdbId: tmdbId,
            slug: slug,
            title: item.title,
            originalTitle: originalTitleMatch ? originalTitleMatch[1].trim() : '',
            altTitles: altTitlesMatch ? altTitlesMatch[1].trim() : '',
            overview: overviewMatch ? overviewMatch[1].replace(/<[^>]+>/g, '').trim() : '',
            year: yearMatch ? yearMatch[1].trim() : '',
            rating: item.rating,
            status: statusMatch ? statusMatch[1].trim() : '',
            posterUrl: posterMatch ? posterMatch[1] : item.posterUrl,
            backdropUrl: backdropMatch ? backdropMatch[1] : '',
            genres: genres.length > 0 ? genres : ['Dorama', 'Drama'],
            cast: cast,
            audio: 'Latino',
            source: 'tudorama',
            detailUrl: item.detailUrl,
            episodesCount: episodesList.length,
            episodes: episodesList,
            updatedAt: new Date().toISOString()
        };
    } catch (e) {
        console.error(`   [ERROR] Falló procesar ${item.title}:`, e.message);
        return null;
    }
}

async function run() {
    console.log('=== EXTRACTOR DE DORAMAS EN ESPAÑOL LATINO (TuDorama) ===');
    const start = Date.now();

    // 1. Obtener catálogo básico de las 4 páginas
    const catalog = await getCategoryDoramas();
    console.log(`\nTotal doramas listados en categoría Latino: ${catalog.length}\n`);

    // 2. Extraer detalles y lista completa de episodios para cada dorama
    const fullData = [];
    for (let i = 0; i < catalog.length; i++) {
        const details = await extractDoramaDetails(catalog[i], i, catalog.length);
        if (details) {
            fullData.push(details);
        }
        // Guardado incremental cada 10 doramas para no perder progreso
        if ((i + 1) % 10 === 0 || i === catalog.length - 1) {
            fs.writeFileSync(OUTPUT_FILE, JSON.stringify(fullData, null, 2), 'utf8');
            console.log(`[CHECKPOINT] Guardados ${fullData.length} doramas en ${OUTPUT_FILE}`);
        }
    }

    const durationSec = Math.round((Date.now() - start) / 1000);
    console.log(`\n=== EXTRACCIÓN COMPLETADA en ${durationSec}s ===`);
    console.log(`Doramas procesados con éxito: ${fullData.length}`);
    console.log(`Archivo generado: ${OUTPUT_FILE}`);
}

run();
