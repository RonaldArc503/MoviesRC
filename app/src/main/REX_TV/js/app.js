class REXApp {
    constructor() {
        this.currentItem = null;
        this.seasons = [];
        this.currentSeasonIndex = -1;
        this.currentEpisodeInSeasonIndex = -1;
        this.currentEpisodeMeta = null;
        this.nextEpisodeMeta = null;

        this.episodeUrls = [];
        this.currentEpisodeIndex = 0;

        this.autoNextTimer = null;
        this.autoNextSecondsRemaining = 0;

        this.init();
    }

    async init() {
        this.showLoading(true);
        await this.loadHome();
        this.setupEventListeners();
        this.showLoading(false);
        remoteNav.updateFocusableItems({ resetToStart: true });
    }

    showLoading(show) {
        const el = document.getElementById('loadingOverlay');
        if (el) el.style.display = show ? 'flex' : 'none';
    }

    isSeriesType(type) {
        const safe = (type || '').toLowerCase();
        return safe === 'tvshows' || safe === 'animes' || safe === 'series' || safe === 'tv';
    }

    async loadHome() {
        try {
            const [featured, popular, movies, tvShows] = await Promise.all([
                AllCalidadAPI.getFeatured(),
                AllCalidadAPI.getPopularMovies(),
                AllCalidadAPI.getMovies(1, 20),
                AllCalidadAPI.getTvShows(1, 20)
            ]);

            const container = document.getElementById('sectionsContainer');
            container.innerHTML = '';

            this.renderSection(container, 'Destacadas', featured.slice(0, 15));
            this.renderSection(container, 'Populares', popular.slice(0, 15));
            this.renderSection(container, 'Peliculas', movies);
            this.renderSection(container, 'Series', tvShows);
        } catch (e) {
            console.error(e);
        }
    }

    renderSection(container, title, items) {
        if (!items || !items.length) return;

        const section = document.createElement('div');
        section.className = 'section';
        section.innerHTML = `
            <h2 class="section-title">${title}</h2>
            <div class="horizontal-scroll">
                ${items.map((item) => this.renderCard(item)).join('')}
            </div>
        `;
        container.appendChild(section);
    }

    renderCard(item) {
        const posterUrl = AllCalidadAPI.getPosterUrl(item);
        const title = this.escapeHtml(item.title || 'Sin titulo');
        const year = item.release_date ? item.release_date.substring(0, 4) : '';

        return `
            <div class="movie-card" data-id="${item._id || item.ID}" data-slug="${item.slug || ''}" data-type="${item.type || 'movies'}">
                <img src="${posterUrl || 'https://placehold.co/200x280/333/E50914?text=REX'}" alt="${title}">
                <div class="movie-info">
                    <div class="movie-title">${title}</div>
                    <div class="movie-year">${year || 'Proximamente'}</div>
                </div>
            </div>
        `;
    }

    async showDetail(id, slug, type) {
        this.showLoading(true);

        let item = null;
        if (type === 'movies') {
            const movies = await AllCalidadAPI.getMovies(1, 50);
            item = movies.find((m) => m._id == id || m.slug === slug);
        } else {
            const shows = await AllCalidadAPI.getTvShows(1, 50);
            item = shows.find((s) => s._id == id || s.slug === slug);
        }

        if (!item) {
            this.showLoading(false);
            return;
        }

        this.currentItem = item;
        this.seasons = [];
        this.currentSeasonIndex = -1;
        this.currentEpisodeInSeasonIndex = -1;
        this.currentEpisodeMeta = null;
        this.nextEpisodeMeta = null;

        const isSeries = this.isSeriesType(type || item.type);
        const backdropUrl = AllCalidadAPI.getBackdropUrl(item);

        document.getElementById('detailBackdrop').style.backgroundImage = `url(${backdropUrl})`;
        document.getElementById('detailTitle').innerHTML = this.escapeHtml(item.title || '');
        document.getElementById('detailYear').innerHTML = item.release_date ? item.release_date.substring(0, 4) : '';
        document.getElementById('detailRating').innerHTML = `* ${item.rating || '8.0'}`;
        document.getElementById('detailSynopsis').innerHTML = item.overview || 'Sin sinopsis disponible.';
        document.getElementById('badgeType').innerHTML = isSeries ? 'SERIE' : 'PELICULA';
        document.getElementById('detailSeasons').innerHTML = '';

        const episodesPanel = document.getElementById('episodesPanel');
        episodesPanel.style.display = isSeries ? 'block' : 'none';

        if (isSeries) {
            await this.loadEpisodes(item._id || item.ID);
        }

        this.updatePlayButtonLabel();

        document.getElementById('homePage').classList.remove('active');
        document.getElementById('playerPage').classList.remove('active');
        document.getElementById('detailPage').classList.add('active');

        this.showLoading(false);
        remoteNav.updateFocusableItems({ preferredSelector: '#playButton', resetToStart: true });
    }

    normalizeSeasons(rawSeasons) {
        if (!Array.isArray(rawSeasons)) {
            return [];
        }

        const normalized = rawSeasons
            .map((season, seasonIndex) => {
                const seasonNumber = parseInt(season?.seasonNumber || season?.season_number || season?.season || (seasonIndex + 1), 10) || 1;
                const episodes = Array.isArray(season?.episodes)
                    ? season.episodes
                        .map((ep, epIndex) => ({
                            id: parseInt(ep?.id || ep?.post_id || ep?._id || ep?.ID || 0, 10) || 0,
                            seasonNumber,
                            episodeNumber: parseInt(ep?.episodeNumber || ep?.episode_number || ep?.episode || (epIndex + 1), 10) || (epIndex + 1),
                            title: ep?.title || `Episodio ${epIndex + 1}`
                        }))
                        .filter((ep) => ep.id > 0)
                    : [];

                episodes.sort((a, b) => a.episodeNumber - b.episodeNumber);
                return { seasonNumber, episodes };
            })
            .filter((season) => season.episodes.length > 0);

        normalized.sort((a, b) => a.seasonNumber - b.seasonNumber);
        return normalized;
    }

    async loadEpisodes(postId) {
        try {
            const seasonSelect = document.getElementById('seasonSelect');
            const episodesList = document.getElementById('episodesList');

            this.seasons = this.normalizeSeasons(await AllCalidadAPI.getSeasons(postId));

            if (!this.seasons.length) {
                seasonSelect.innerHTML = '';
                episodesList.innerHTML = '<p>No hay episodios disponibles</p>';
                return;
            }

            seasonSelect.innerHTML = this.seasons
                .map((season, i) => `<option value="${i}">Temporada ${season.seasonNumber || i + 1}</option>`)
                .join('');

            seasonSelect.onchange = () => {
                const idx = parseInt(seasonSelect.value, 10);
                this.renderEpisodes(Number.isNaN(idx) ? 0 : idx);
            };

            this.renderEpisodes(0);
        } catch (e) {
            console.error(e);
        }
    }

    renderEpisodes(seasonIndex) {
        const season = this.seasons[seasonIndex];
        if (!season) return;

        this.currentSeasonIndex = seasonIndex;
        this.currentEpisodeInSeasonIndex = -1;

        const episodesList = document.getElementById('episodesList');
        episodesList.innerHTML = season.episodes.map((ep, epIndex) => `
            <div
                class="episode-item"
                data-id="${ep.id}"
                data-season-index="${seasonIndex}"
                data-episode-index="${epIndex}">
                <div class="episode-number">T${season.seasonNumber}:E${ep.episodeNumber}</div>
                <div class="episode-title">${this.escapeHtml(ep.title || `Episodio ${ep.episodeNumber}`)}</div>
            </div>
        `).join('');

        document.querySelectorAll('.episode-item').forEach((el) => {
            el.addEventListener('click', () => {
                const sIdx = parseInt(el.dataset.seasonIndex, 10);
                const eIdx = parseInt(el.dataset.episodeIndex, 10);
                this.playEpisodeByIndex(sIdx, eIdx);
            });
        });

        this.updatePlayButtonLabel();
        remoteNav.updateFocusableItems();
    }

    getFirstEpisodeMeta() {
        for (let s = 0; s < this.seasons.length; s++) {
            const season = this.seasons[s];
            if (!season || !Array.isArray(season.episodes) || !season.episodes.length) continue;
            return { seasonIndex: s, episodeIndex: 0, episode: season.episodes[0] };
        }
        return null;
    }

    findEpisodeById(episodeId) {
        if (!episodeId) return null;
        for (let s = 0; s < this.seasons.length; s++) {
            const season = this.seasons[s];
            if (!season?.episodes?.length) continue;
            for (let e = 0; e < season.episodes.length; e++) {
                if (season.episodes[e].id == episodeId) {
                    return { seasonIndex: s, episodeIndex: e, episode: season.episodes[e] };
                }
            }
        }
        return null;
    }

    findNextEpisodeMeta(seasonIndex, episodeIndex) {
        const season = this.seasons[seasonIndex];
        if (season?.episodes && episodeIndex + 1 < season.episodes.length) {
            return {
                seasonIndex,
                episodeIndex: episodeIndex + 1,
                episode: season.episodes[episodeIndex + 1]
            };
        }

        for (let s = seasonIndex + 1; s < this.seasons.length; s++) {
            const nextSeason = this.seasons[s];
            if (nextSeason?.episodes?.length) {
                return {
                    seasonIndex: s,
                    episodeIndex: 0,
                    episode: nextSeason.episodes[0]
                };
            }
        }

        return null;
    }

    updatePlayButtonLabel() {
        const playButton = document.getElementById('playButton');
        if (!playButton) return;

        if (!this.currentItem || !this.isSeriesType(this.currentItem.type)) {
            playButton.textContent = 'Reproducir';
            return;
        }

        const first = this.getFirstEpisodeMeta();
        if (!first || !first.episode) {
            playButton.textContent = 'Reproducir';
            return;
        }

        playButton.textContent = `Reproducir T${first.episode.seasonNumber}:E${first.episode.episodeNumber}`;
    }

    async playContent() {
        if (!this.currentItem) return;

        if (this.isSeriesType(this.currentItem.type)) {
            await this.playFirstEpisode();
            return;
        }

        this.showLoading(true);
        const postId = this.currentItem._id || this.currentItem.ID;
        const type = this.currentItem.type || 'movies';
        const urls = await AllCalidadAPI.getPlayableUrls(postId, type);

        if (urls.length) {
            this.openPlayer(urls, this.currentItem.title);
        } else {
            alert('No se pudo obtener la URL de reproduccion');
        }

        this.showLoading(false);
    }

    async playFirstEpisode() {
        const first = this.getFirstEpisodeMeta();
        if (!first || !first.episode) {
            alert('No hay episodios disponibles');
            return;
        }

        await this.playEpisodeByContext(first.seasonIndex, first.episodeIndex, first.episode);
    }

    async playEpisodeByIndex(seasonIndex, episodeIndex) {
        const season = this.seasons[seasonIndex];
        const episode = season?.episodes?.[episodeIndex];
        if (!episode) {
            alert('No se pudo obtener el episodio');
            return;
        }

        await this.playEpisodeByContext(seasonIndex, episodeIndex, episode);
    }

    async playEpisode(episodeId) {
        const resolved = this.findEpisodeById(episodeId);
        if (resolved) {
            await this.playEpisodeByContext(resolved.seasonIndex, resolved.episodeIndex, resolved.episode);
            return;
        }

        this.showLoading(true);
        const urls = await AllCalidadAPI.getPlayableUrls(episodeId, 'episodes');
        if (urls.length) {
            this.openPlayer(urls, `${this.currentItem?.title || 'Serie'} - Episodio`);
        } else {
            alert('No se pudo obtener el episodio');
        }
        this.showLoading(false);
    }

    async playEpisodeByContext(seasonIndex, episodeIndex, episode) {
        if (!episode || !episode.id) {
            alert('No se pudo obtener el episodio');
            return;
        }

        this.showLoading(true);
        const urls = await AllCalidadAPI.getPlayableUrls(episode.id, 'episodes');

        if (urls.length) {
            const episodeTitle = `${this.currentItem?.title || 'Serie'} - T${episode.seasonNumber}:E${episode.episodeNumber}`;
            this.openPlayer(urls, episodeTitle, {
                seasonIndex,
                episodeIndex,
                episode
            });
        } else {
            alert('No se pudo obtener el episodio');
        }

        this.showLoading(false);
    }

    openPlayer(urls, title, episodeContext = null) {
        const playerPage = document.getElementById('playerPage');
        const playerFrame = document.getElementById('playerFrame');
        const nextBtn = document.getElementById('nextEpisodeBtn');

        this.clearNextEpisodeCountdown();

        this.episodeUrls = Array.isArray(urls) ? urls.slice() : [];
        this.currentEpisodeIndex = 0;

        this.currentEpisodeMeta = null;
        this.nextEpisodeMeta = null;

        if (episodeContext && episodeContext.episode) {
            this.currentSeasonIndex = episodeContext.seasonIndex;
            this.currentEpisodeInSeasonIndex = episodeContext.episodeIndex;
            this.currentEpisodeMeta = episodeContext.episode;
            this.nextEpisodeMeta = this.findNextEpisodeMeta(episodeContext.seasonIndex, episodeContext.episodeIndex);
        }

        playerFrame.src = this.episodeUrls[0] || '';
        playerPage.classList.add('active');
        document.getElementById('detailPage').classList.remove('active');
        document.getElementById('homePage').classList.remove('active');

        if (this.nextEpisodeMeta && this.nextEpisodeMeta.episode) {
            nextBtn.style.display = 'block';
            this.updateNextEpisodeButtonLabel();
            this.startNextEpisodeCountdown();
            remoteNav.updateFocusableItems({ preferredSelector: '#nextEpisodeBtn' });
        } else {
            nextBtn.style.display = 'none';
            nextBtn.textContent = 'Siguiente episodio';
            remoteNav.updateFocusableItems({ preferredSelector: '#closePlayerBtn' });
        }

        if (title) {
            document.title = `REX - ${title}`;
        }
    }

    updateNextEpisodeButtonLabel() {
        const nextBtn = document.getElementById('nextEpisodeBtn');
        if (!nextBtn || !this.nextEpisodeMeta?.episode) {
            return;
        }

        const episode = this.nextEpisodeMeta.episode;
        const base = `Siguiente episodio T${episode.seasonNumber}:E${episode.episodeNumber}`;

        if (this.autoNextSecondsRemaining > 0) {
            nextBtn.textContent = `${base} (${this.autoNextSecondsRemaining}s)`;
            return;
        }

        nextBtn.textContent = base;
    }

    startNextEpisodeCountdown() {
        this.clearNextEpisodeCountdown();

        if (!this.nextEpisodeMeta?.episode) {
            return;
        }

        this.autoNextSecondsRemaining = 12;
        this.updateNextEpisodeButtonLabel();

        this.autoNextTimer = setInterval(() => {
            if (!document.getElementById('playerPage').classList.contains('active')) {
                this.clearNextEpisodeCountdown();
                return;
            }

            this.autoNextSecondsRemaining -= 1;

            if (this.autoNextSecondsRemaining <= 0) {
                this.clearNextEpisodeCountdown();
                this.nextEpisode(true);
                return;
            }

            this.updateNextEpisodeButtonLabel();
        }, 1000);
    }

    clearNextEpisodeCountdown() {
        if (this.autoNextTimer) {
            clearInterval(this.autoNextTimer);
            this.autoNextTimer = null;
        }
        this.autoNextSecondsRemaining = 0;
    }

    async nextEpisode(automatic = false) {
        if (this.nextEpisodeMeta?.episode) {
            const target = this.nextEpisodeMeta;
            await this.playEpisodeByContext(target.seasonIndex, target.episodeIndex, target.episode);
            return;
        }

        if (this.currentEpisodeIndex + 1 < this.episodeUrls.length) {
            this.currentEpisodeIndex += 1;
            document.getElementById('playerFrame').src = this.episodeUrls[this.currentEpisodeIndex];
            return;
        }

        if (!automatic) {
            alert('Ya estas en el ultimo episodio disponible');
        }
    }

    closePlayer() {
        this.clearNextEpisodeCountdown();

        const playerPage = document.getElementById('playerPage');
        const playerFrame = document.getElementById('playerFrame');
        const nextBtn = document.getElementById('nextEpisodeBtn');

        playerFrame.src = '';
        nextBtn.style.display = 'none';
        nextBtn.textContent = 'Siguiente episodio';

        playerPage.classList.remove('active');
        document.getElementById('detailPage').classList.add('active');

        remoteNav.updateFocusableItems({ preferredSelector: '#playButton' });
    }

    showHome() {
        this.clearNextEpisodeCountdown();
        document.getElementById('detailPage').classList.remove('active');
        document.getElementById('playerPage').classList.remove('active');
        document.getElementById('homePage').classList.add('active');
        remoteNav.updateFocusableItems({ resetToStart: true });
    }

    onSearchInputActivated() {
        const searchInput = document.getElementById('searchInput');
        if (!searchInput) return;

        searchInput.focus();
        if (typeof searchInput.select === 'function') {
            searchInput.select();
        }
    }

    setupEventListeners() {
        const searchBtn = document.getElementById('searchBtn');
        const searchInput = document.getElementById('searchInput');
        const playButton = document.getElementById('playButton');
        const myListButton = document.getElementById('mylistButton');
        const backButton = document.getElementById('backButton');
        const closePlayerBtn = document.getElementById('closePlayerBtn');
        const nextEpisodeBtn = document.getElementById('nextEpisodeBtn');
        const sectionsContainer = document.getElementById('sectionsContainer');

        searchBtn.addEventListener('click', () => this.performSearch());

        searchInput.addEventListener('click', () => this.onSearchInputActivated());
        searchInput.addEventListener('keydown', (e) => {
            if (e.key === 'Enter' || e.keyCode === 13) {
                e.preventDefault();
                this.performSearch();
            }
        });

        playButton.addEventListener('click', () => this.playContent());
        myListButton.addEventListener('click', () => alert('Agregado a Mi Lista'));
        backButton.addEventListener('click', () => this.showHome());

        closePlayerBtn.addEventListener('click', () => this.closePlayer());
        nextEpisodeBtn.addEventListener('click', () => this.nextEpisode(false));

        sectionsContainer.addEventListener('click', (e) => {
            const card = e.target.closest('.movie-card');
            if (!card) return;

            const id = card.dataset.id;
            const slug = card.dataset.slug;
            const type = card.dataset.type;
            this.showDetail(id, slug, type);
        });
    }

    async performSearch() {
        const query = document.getElementById('searchInput').value.trim();
        if (!query) return;

        this.showLoading(true);
        const results = await AllCalidadAPI.search(query);
        const container = document.getElementById('sectionsContainer');
        container.innerHTML = '';
        this.renderSection(container, `Resultados para: "${this.escapeHtml(query)}"`, results);
        this.showLoading(false);

        document.getElementById('homePage').classList.add('active');
        document.getElementById('detailPage').classList.remove('active');
        document.getElementById('playerPage').classList.remove('active');

        remoteNav.updateFocusableItems({ preferredSelector: '.movie-card', resetToStart: true });
    }

    escapeHtml(text) {
        const div = document.createElement('div');
        div.textContent = text;
        return div.innerHTML;
    }
}

document.addEventListener('DOMContentLoaded', () => {
    window.app = new REXApp();
});
