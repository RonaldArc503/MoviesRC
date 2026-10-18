/**
 * sync_tudorama.js
 * 
 * Script de detección y sincronización incremental de TuDorama (Español Latino):
 * 1. Escanea únicamente la Página 1 (los 20 doramas más recientes).
 * 2. Compara contra doramas_latino.json:
 *    - Si un dorama es NUEVO -> extrae todos sus datos y lo añade.
 *    - Si un dorama ya existía pero está "En emisión" -> comprueba si hay nuevos episodios y los actualiza.
 * 3. Si hubo cambios, actualiza doramas_latino.json y (si existe serviceAccountKey.json) lo sube a Firebase.
 * 
 * Este script se puede ejecutar localmente con:
 *   node scripts/sync_tudorama.js
 * O programarse en un GitHub Action o Cron para ejecutarse automáticamente.
 */

const fs = require('fs');
const path = require('path');

const BASE_URL = 'https://tudorama.com';
const CATEGORY_URL = 'https://tudorama.com/genero/espanol-latino/';
const JSON_FILE = path.join(__dirname, '..', 'doramas_latino.json');
const SERVICE_ACCOUNT_FILE = path.join(__dirname, '..', 'serviceAccountKey.json');

const HEADERS = {
    'User-Agent': 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36',
    'Accept': 'text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8'
};

async function fetchHtml(url) {
    const res = await fetch(url, { headers: HEADERS });
    if (!res.ok) throw new Error(`HTTP ${res.status} para ${url}`);
    return await res.text();
}

async function getPage1Items() {
    console.log(`[SYNC] Escaneando página 1 de TuDorama Latino (${CATEGORY_URL})...`);
    const html = await fetchHtml(CATEGORY_URL);
    const list = [];
    const articleRegex = /<article class="ipst">([\s\S]*?)<\/article>/g;
    let match;

    while ((match = articleRegex.exec(html)) !== null) {
        const block = match[1];
        const linkMatch = block.match(/href="([^"]+)"/);
        const titleMatch = block.match(/<h3 class="ipst__title"><a [^>]+>([^<]+)<\/a>/);
        const imgMatch = block.match(/src="([^"]+)"/);
        const scoreMatch = block.match(/class="avg-score[^"]*">\s*<i[^>]*><\/i>\s*([0-9.]+)/);

        if (linkMatch && titleMatch) {
            const detailUrl = linkMatch[1];
            const slug = detailUrl.replace(BASE_URL, '').replace(/^\/serie\//, '').replace(/\/$/, '');
            list.push({
                slug: slug,
                detailUrl: detailUrl,
                title: titleMatch[1].trim(),
                posterUrl: imgMatch ? imgMatch[1] : '',
                rating: scoreMatch ? parseFloat(scoreMatch[1]) : 0.0
            });
        }
    }

    return list;
}

async function extractEpisodes(detailUrl, tmdbId, nonce, seasonNumber = 1) {
    const html = await fetchHtml(detailUrl);
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

    if (tmdbId > 0 && nonce) {
        let offset = 8;
        let hasMore = true;
        while (hasMore) {
            try {
                const form = new URLSearchParams();
                form.append('action', 'corvus_get_episodes');
                form.append('nonce', nonce);
                form.append('post_id', tmdbId.toString());
                form.append('season', seasonNumber.toString());
                form.append('results', '8');
                form.append('offset', offset.toString());
                form.append('order', 'DESC');

                const ajaxRes = await fetch(`${BASE_URL}/wp-admin/admin-ajax.php`, {
                    method: 'POST',
                    headers: {
                        ...HEADERS,
                        'Referer': detailUrl,
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
                    offset += 8;
                } else {
                    hasMore = false;
                }
            } catch (e) {
                hasMore = false;
            }
        }
    }

    return Array.from(episodesMap.values()).sort((a, b) => {
        if (a.seasonNumber !== b.seasonNumber) return a.seasonNumber - b.seasonNumber;
        return a.episodeNumber - b.episodeNumber;
    });
}

async function sync() {
    console.log('=== VERIFICADOR DE NOVEDADES EN TUDORAMA LATINO ===');
    
    if (!fs.existsSync(JSON_FILE)) {
        console.error(`[ERROR] No existe ${JSON_FILE}. Ejecuta primero extract_tudorama_catalog.js`);
        return;
    }

    const currentCatalog = JSON.parse(fs.readFileSync(JSON_FILE, 'utf8'));
    const catalogMap = new Map();
    currentCatalog.forEach(item => catalogMap.set(item.slug, item));

    const page1Items = await getPage1Items();
    console.log(`[SYNC] Se encontraron ${page1Items.length} doramas en la página 1.`);

    let updatedCount = 0;
    let addedCount = 0;
    const changedItems = [];

    for (const p1 of page1Items) {
        if (!catalogMap.has(p1.slug)) {
            // ¡Dorama totalmente NUEVO!
            console.log(`\n[NUEVO DORAMA DETECTADO] -> "${p1.title}" (${p1.slug})`);
            const html = await fetchHtml(p1.detailUrl);
            const epsDivMatch = html.match(/class="eps"[^>]*data-tmdb-id="([^"]+)"[^>]*data-season-number="([^"]+)"[^>]*data-nonce="([^"]+)"/i);
            const tmdbId = epsDivMatch ? parseInt(epsDivMatch[1], 10) : 0;
            const nonce = epsDivMatch ? epsDivMatch[3] : '';
            const statusMatch = html.match(/class="details__col col-status"><span[^>]*>([^<]+)<\/span>/i);
            const yearMatch = html.match(/class="details__col col-name">Año<\/div>\s*<div class="details__col">([^<]+)<\/div>/i);
            const overviewMatch = html.match(/<p class="hero__overview">([\s\S]*?)<\/p>/i);
            const posterMatch = html.match(/<div class="hero__poster">\s*<picture>\s*<img[^>]+src="([^"]+)"/i);
            const backdropMatch = html.match(/<figure class="hero__backdrop">\s*<img[^>]+src="([^"]+)"/i);

            const episodes = await extractEpisodes(p1.detailUrl, tmdbId, nonce);

            const newItem = {
                id: `tudorama_${p1.slug}`,
                tmdbId: tmdbId,
                slug: p1.slug,
                title: p1.title,
                originalTitle: '',
                altTitles: '',
                overview: overviewMatch ? overviewMatch[1].replace(/<[^>]+>/g, '').trim() : '',
                year: yearMatch ? yearMatch[1].trim() : '',
                rating: p1.rating,
                status: statusMatch ? statusMatch[1].trim() : 'En emision',
                posterUrl: posterMatch ? posterMatch[1] : p1.posterUrl,
                backdropUrl: backdropMatch ? backdropMatch[1] : '',
                genres: ['Dorama', 'Drama'],
                cast: [],
                audio: 'Latino',
                source: 'tudorama',
                detailUrl: p1.detailUrl,
                episodesCount: episodes.length,
                episodes: episodes,
                updatedAt: new Date().toISOString()
            };

            currentCatalog.unshift(newItem); // Añadir al inicio
            catalogMap.set(p1.slug, newItem);
            changedItems.push(newItem);
            addedCount++;
        } else {
            // Ya existía: Comprobar si está "En emision" y si tiene nuevos capítulos
            const existing = catalogMap.get(p1.slug);
            if (existing.status && existing.status.toLowerCase().includes('emision')) {
                const html = await fetchHtml(p1.detailUrl);
                const epsDivMatch = html.match(/class="eps"[^>]*data-tmdb-id="([^"]+)"[^>]*data-season-number="([^"]+)"[^>]*data-nonce="([^"]+)"/i);
                const tmdbId = epsDivMatch ? parseInt(epsDivMatch[1], 10) : existing.tmdbId;
                const nonce = epsDivMatch ? epsDivMatch[3] : '';
                const statusMatch = html.match(/class="details__col col-status"><span[^>]*>([^<]+)<\/span>/i);

                const currentEpisodes = await extractEpisodes(p1.detailUrl, tmdbId, nonce);
                if (currentEpisodes.length > (existing.episodesCount || 0)) {
                    console.log(`\n[NUEVOS CAPÍTULOS] -> "${existing.title}": Tenía ${existing.episodesCount} eps, ahora tiene ${currentEpisodes.length} eps.`);
                    existing.episodes = currentEpisodes;
                    existing.episodesCount = currentEpisodes.length;
                    existing.status = statusMatch ? statusMatch[1].trim() : existing.status;
                    existing.updatedAt = new Date().toISOString();
                    changedItems.push(existing);
                    updatedCount++;
                }
            }
        }
    }

    if (addedCount > 0 || updatedCount > 0) {
        console.log(`\n[GUARDANDO] Se agregaron ${addedCount} doramas y se actualizaron ${updatedCount} con nuevos capítulos.`);
        fs.writeFileSync(JSON_FILE, JSON.stringify(currentCatalog, null, 2), 'utf8');
        console.log(`[OK] ${JSON_FILE} actualizado exitosamente.`);

        // Si existe serviceAccountKey.json, sincronizar los cambios en Firebase
        if (fs.existsSync(SERVICE_ACCOUNT_FILE)) {
            console.log('\n[FIREBASE] Subiendo novedades a Firestore y Realtime DB...');
            const admin = require('firebase-admin');
            const serviceAccount = require(SERVICE_ACCOUNT_FILE);
            if (!admin.apps.length) {
                admin.initializeApp({
                    credential: admin.credential.cert(serviceAccount),
                    databaseURL: 'https://anime-rc-default-rtdb.firebaseio.com'
                });
            }
            const firestore = admin.firestore();
            const rtdb = admin.database();

            for (const item of changedItems) {
                await firestore.collection('doramas_latino').document(item.id).set(item, { merge: true });
                if (item.tmdbId > 0) {
                    await rtdb.ref(`catalog/endpoints/${item.tmdbId}`).update({
                        title: item.title,
                        year: item.year,
                        kind: 'series',
                        tmdb_id: item.tmdbId,
                        seasons: [
                            {
                                season_number: 1,
                                episodes: item.episodes.map(ep => ({
                                    episode_number: ep.episodeNumber,
                                    title: ep.title,
                                    detail_url: ep.permalink,
                                    source: 'tudorama'
                                }))
                            }
                        ],
                        sources: {
                            tudorama: {
                                detail_url: item.detailUrl,
                                status: item.status,
                                episodes_count: item.episodesCount
                            }
                        },
                        updated_at: Date.now()
                    });
                }
            }
            console.log(`[FIREBASE] ${changedItems.length} títulos sincronizados en Firebase.`);
        }
    } else {
        console.log('\n[OK] No se detectaron novedades ni nuevos episodios en la Página 1.');
        console.log('El catálogo ya está completamente al día.');
    }
}

sync().catch(e => {
    console.error('[ERROR FATAL]', e);
});
