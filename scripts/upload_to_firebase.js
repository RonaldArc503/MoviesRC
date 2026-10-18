/**
 * upload_to_firebase.js
 * 
 * Sube los 77 doramas en Español Latino a Firebase Realtime Database (anime-rc)
 * utilizando el endpoint REST nativo:
 * 1. /catalog/series/{tmdbId}.json -> Metadatos de catálogo (título, poster, overview, genres, cast, rating)
 * 2. /catalog/endpoints/{tmdbId}.json -> Endpoints de reproducción compatibles con FirebaseEndpointResolver
 * 
 * Si existe serviceAccountKey.json, también sube a Cloud Firestore.
 */

const fs = require('fs');
const path = require('path');

const JSON_FILE = path.join(__dirname, '..', 'doramas_latino.json');
const SERVICE_ACCOUNT_FILE = path.join(__dirname, '..', 'serviceAccountKey.json');
const RTDB_BASE = 'https://anime-rc-default-rtdb.firebaseio.com';

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

async function uploadToFirebaseRTDB(items) {
    console.log(`\n=== SUBIENDO ${items.length} DORAMAS A FIREBASE REALTIME DATABASE ===`);
    console.log(`Destino: ${RTDB_BASE}/catalog/`);

    let successSeries = 0;
    let successEndpoints = 0;

    for (let i = 0; i < items.length; i++) {
        const item = items[i];
        if (!item.tmdbId || item.tmdbId <= 0) continue;

        const tmdbId = item.tmdbId;

        // 1. Objeto de Catálogo para /catalog/series/{tmdbId}.json
        const seriesData = {
            id: String(tmdbId),
            tmdb_id: tmdbId,
            title: item.title,
            original_title: item.originalTitle || item.title,
            year: item.year || '',
            poster: item.posterUrl || '',
            backdrop: item.backdropUrl || '',
            rating: item.rating || 0.0,
            overview: item.overview || '',
            genres: Array.isArray(item.genres) ? [...new Set(item.genres)] : ['Dorama', 'Drama'],
            cast: Array.isArray(item.cast) ? item.cast.map(c => c.name || c) : [],
            kind: 'series',
            audio: 'Latino',
            playback: {
                total_endpoints: 1,
                primary_endpoint: item.episodes.length > 0 ? item.episodes[0].permalink : item.detailUrl,
                endpoints: [
                    {
                        language: 'latino',
                        provider: 'tudorama',
                        quality: 'HD',
                        server: 'tudorama_detail',
                        type: 'detail_page',
                        url: item.detailUrl
                    }
                ]
            },
            sources: {
                has_tudorama: true,
                has_cuevana: false,
                has_allcalidad: false,
                tudorama: {
                    detail_url: item.detailUrl,
                    playable: true,
                    slug: item.slug
                }
            },
            updated_at: Date.now()
        };

        // 2. Objeto de Endpoints para /catalog/endpoints/{tmdbId}.json
        const endpointsData = {
            kind: 'series',
            title: item.title,
            tmdb_id: tmdbId,
            year: item.year || '',
            total_endpoints: 1,
            primary_endpoint: item.episodes.length > 0 ? item.episodes[0].permalink : item.detailUrl,
            endpoints: [
                {
                    language: 'latino',
                    provider: 'tudorama',
                    quality: 'HD',
                    server: 'tudorama_detail',
                    type: 'detail_page',
                    url: item.detailUrl
                }
            ],
            seasons: [
                {
                    season_number: 1,
                    episodes: item.episodes.map(ep => ({
                        episode_number: ep.episodeNumber,
                        season_number: ep.seasonNumber || 1,
                        title: ep.title || `Episodio ${ep.episodeNumber}`,
                        detail_url: ep.permalink,
                        thumbnail: ep.thumbnail || '',
                        source: 'tudorama'
                    }))
                }
            ],
            sources: {
                tudorama: {
                    detail_url: item.detailUrl,
                    episodes_count: item.episodesCount || item.episodes.length,
                    status: item.status || 'Finalizado'
                }
            },
            updated_at: Date.now()
        };

        try {
            // Guardar en /catalog/series/{tmdbId}.json
            await putJson(`${RTDB_BASE}/catalog/series/${tmdbId}.json`, seriesData);
            successSeries++;

            // Guardar en /catalog/endpoints/{tmdbId}.json
            await putJson(`${RTDB_BASE}/catalog/endpoints/${tmdbId}.json`, endpointsData);
            successEndpoints++;

            process.stdout.write(`\r[PROGRESO] ${i + 1}/${items.length} subidos: "${item.title}" (TMDb ${tmdbId})`);
        } catch (e) {
            console.error(`\n[ERROR] Falló subir TMDb ${tmdbId} (${item.title}):`, e.message);
        }
    }

    console.log(`\n\n=== SUBIDA A REALTIME DATABASE COMPLETADA ===`);
    console.log(`- Catálogo series: ${successSeries}/${items.length} documentos`);
    console.log(`- Endpoints de reproducción: ${successEndpoints}/${items.length} documentos`);
}

async function uploadToFirestoreIfAvailable(items) {
    if (!fs.existsSync(SERVICE_ACCOUNT_FILE)) {
        return;
    }
    console.log('\n[FIRESTORE] Se encontró serviceAccountKey.json. Subiendo también a Cloud Firestore...');
    const admin = require('firebase-admin');
    const serviceAccount = require(SERVICE_ACCOUNT_FILE);

    if (!admin.apps.length) {
        admin.initializeApp({
            credential: admin.credential.cert(serviceAccount)
        });
    }

    const firestore = admin.firestore();
    const BATCH_SIZE = 400;
    let batch = firestore.batch();
    let opCount = 0;

    for (const item of items) {
        const docRef = firestore.collection('doramas_latino').document(item.id);
        batch.set(docRef, item, { merge: true });
        opCount++;

        if (opCount >= BATCH_SIZE) {
            await batch.commit();
            batch = firestore.batch();
            opCount = 0;
        }
    }
    if (opCount > 0) {
        await batch.commit();
    }
    console.log(`[FIRESTORE] ${items.length} doramas sincronizados en la colección 'doramas_latino'.`);
}

async function run() {
    if (!fs.existsSync(JSON_FILE)) {
        console.error(`[ERROR] No se encontró ${JSON_FILE}`);
        process.exit(1);
    }

    const items = JSON.parse(fs.readFileSync(JSON_FILE, 'utf8'));
    await uploadToFirebaseRTDB(items);
    await uploadToFirestoreIfAvailable(items);
}

run().catch(e => {
    console.error('[ERROR FATAL]', e);
    process.exit(1);
});
