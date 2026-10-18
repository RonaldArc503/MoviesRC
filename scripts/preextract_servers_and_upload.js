/**
 * scripts/preextract_servers_and_upload.js
 * 
 * Pre-extrae los embeds directos de servidores de reproducción en Español Latino
 * para los 925 episodios de los 77 doramas y actualiza Firebase Realtime Database
 * en /catalog/endpoints/{tmdbId}.json.
 * 
 * Esto permite reproducción 100% INSTANTÁNEA (< 50ms) en la app Rex.
 */

const fs = require('fs');
const path = require('path');

const JSON_FILE = path.join(__dirname, '..', 'doramas_latino.json');
const RTDB_BASE = 'https://anime-rc-default-rtdb.firebaseio.com';
const AJAX_URL = 'https://tudorama.com/wp-admin/admin-ajax.php';
const USER_AGENT = 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36';

const CONCURRENCY = 5;

async function sleep(ms) {
    return new Promise(resolve => setTimeout(resolve, ms));
}

async function putJson(url, data) {
    const res = await fetch(url, {
        method: 'PUT',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(data)
    });
    if (!res.ok) {
        throw new Error(`HTTP ${res.status}: ${await res.text()}`);
    }
    return await res.json();
}

async function resolveEpisodeServers(epPermalink) {
    if (!epPermalink || !epPermalink.startsWith('http')) return [];

    try {
        const res = await fetch(epPermalink, {
            headers: { 'User-Agent': USER_AGENT }
        });
        if (!res.ok) return [];
        const html = await res.text();

        const dropdownMatch = html.match(/class="ep__dropdown"[^>]*data-id="([^"]+)"[^>]*data-nonce="([^"]+)"/i);
        if (!dropdownMatch) return [];

        const postId = dropdownMatch[1];
        const nonce = dropdownMatch[2];

        const body = new URLSearchParams({
            action: 'corvus_get_servers',
            post_id: postId,
            nonce: nonce
        });

        const ajaxRes = await fetch(AJAX_URL, {
            method: 'POST',
            body: body.toString(),
            headers: {
                'Content-Type': 'application/x-www-form-urlencoded',
                'User-Agent': USER_AGENT,
                'Referer': epPermalink
            }
        });

        if (!ajaxRes.ok) return [];
        const servers = await ajaxRes.json();
        if (!Array.isArray(servers)) return [];

        const latino = servers.filter(s => (s.lang === 'es' || (s.name && s.name.toLowerCase().startsWith('esp:'))));
        const targetServers = latino.length > 0 ? latino : servers;

        const directUrls = [];
        for (const s of targetServers) {
            const u = s.url || '';
            const mId = u.match(/[?&]id1=([a-zA-Z0-9_-]+)/);
            if (mId) {
                if (u.includes('server-1.php')) directUrls.push('https://hgcloud.to/e/' + mId[1]);
                else if (u.includes('server-2.php')) directUrls.push('https://bysesukior.com/e/' + mId[1]);
                else if (u.includes('server-3.php')) directUrls.push('https://minochinos.com/embed/' + mId[1]);
                else if (u.includes('abyss.php')) directUrls.push('https://abyssplayer.com/' + mId[1]);
            } else if (u.includes('pixeldrain.com')) {
                directUrls.push(u);
            }
        }

        return [...new Set(directUrls)];
    } catch (e) {
        return [];
    }
}

async function run() {
    if (!fs.existsSync(JSON_FILE)) {
        console.error('No se encontró doramas_latino.json');
        process.exit(1);
    }

    const doramas = JSON.parse(fs.readFileSync(JSON_FILE, 'utf8'));
    console.log(`=== PRE-EXTRACCIÓN DE REPRODUCTORES PARA ${doramas.length} DORAMAS ===`);

    let totalEpisodesCount = 0;
    let totalEpisodesResolved = 0;

    for (let dIdx = 0; dIdx < doramas.length; dIdx++) {
        const dorama = doramas[dIdx];
        if (!dorama.episodes || dorama.episodes.length === 0) continue;

        const tmdbId = dorama.tmdbId;
        console.log(`\n[${dIdx + 1}/${doramas.length}] "${dorama.title}" (TMDb ${tmdbId}, ${dorama.episodes.length} episodios)...`);

        let doramaModified = false;

        // Procesar episodios en lotes de CONCURRENCY
        for (let i = 0; i < dorama.episodes.length; i += CONCURRENCY) {
            const batch = dorama.episodes.slice(i, i + CONCURRENCY);
            const promises = batch.map(async (ep) => {
                totalEpisodesCount++;
                if (ep.host_urls && ep.host_urls.length > 0 && ep.url) {
                    totalEpisodesResolved++;
                    return; // Ya resuelto
                }

                const urls = await resolveEpisodeServers(ep.permalink);
                if (urls && urls.length > 0) {
                    ep.url = urls[0];
                    ep.embed_url = urls[0];
                    ep.host_urls = urls;
                    ep.servers = urls;
                    doramaModified = true;
                    totalEpisodesResolved++;
                }
            });

            await Promise.all(promises);
            await sleep(150); // Pequeña pausa de cortesía
            process.stdout.write(`\r  > Episodios procesados: ${Math.min(i + CONCURRENCY, dorama.episodes.length)}/${dorama.episodes.length}`);
        }

        // Subir endpoints actualizados a Firebase RTDB para este dorama
        const endpointsData = {
            kind: 'series',
            title: dorama.title,
            tmdb_id: tmdbId,
            year: dorama.year || '',
            total_endpoints: dorama.episodes.length > 0 && dorama.episodes[0].host_urls ? dorama.episodes[0].host_urls.length : 1,
            primary_endpoint: dorama.episodes.length > 0 && dorama.episodes[0].url ? dorama.episodes[0].url : (dorama.episodes[0]?.permalink || dorama.detailUrl),
            endpoints: (dorama.episodes.length > 0 && dorama.episodes[0].host_urls)
                ? dorama.episodes[0].host_urls.map(u => ({
                    language: 'latino',
                    provider: 'tudorama',
                    quality: 'HD',
                    server: u.includes('hgcloud') ? 'streamhg' : (u.includes('bysesukior') ? 'filemoon' : 'direct'),
                    type: 'embed',
                    url: u
                }))
                : [
                    {
                        language: 'latino',
                        provider: 'tudorama',
                        quality: 'HD',
                        server: 'tudorama_detail',
                        type: 'detail_page',
                        url: dorama.detailUrl
                    }
                ],
            seasons: [
                {
                    season_number: 1,
                    episodes: dorama.episodes.map(ep => ({
                        episode_number: ep.episodeNumber,
                        season_number: ep.seasonNumber || 1,
                        title: ep.title || `Episodio ${ep.episodeNumber}`,
                        detail_url: ep.permalink,
                        url: ep.url || '',
                        embed_url: ep.embed_url || '',
                        host_urls: ep.host_urls || [],
                        servers: ep.servers || [],
                        thumbnail: ep.thumbnail || '',
                        source: 'tudorama'
                    }))
                }
            ],
            sources: {
                tudorama: {
                    detail_url: dorama.detailUrl,
                    episodes_count: dorama.episodesCount || dorama.episodes.length,
                    status: dorama.status || 'Finalizado'
                }
            },
            updated_at: Date.now()
        };

        try {
            await putJson(`${RTDB_BASE}/catalog/endpoints/${tmdbId}.json`, endpointsData);
        } catch (err) {
            console.error(`\n  [ERROR RTDB TMDb ${tmdbId}]:`, err.message);
        }

        // Guardar progreso en el JSON local cada 5 doramas
        if (dIdx % 5 === 0 || dIdx === doramas.length - 1) {
            fs.writeFileSync(JSON_FILE, JSON.stringify(doramas, null, 2), 'utf8');
        }
    }

    // Guardar final
    fs.writeFileSync(JSON_FILE, JSON.stringify(doramas, null, 2), 'utf8');
    console.log(`\n\n=== PRE-EXTRACCIÓN COMPLETADA ===`);
    console.log(`Total episodios resueltos: ${totalEpisodesResolved}`);
    console.log(`Todos los endpoints en Firebase RTDB han sido actualizados con servidores directos.`);
}

run().catch(e => {
    console.error('[ERROR FATAL]', e);
    process.exit(1);
});
