class REXApp {
    constructor() {
        this.currentItem = null;
        this.seasons = [];
        this.currentEpisodeIndex = 0;
        this.episodeUrls = [];
        this.init();
    }
    
    async init() {
        this.showLoading(true);
        await this.loadHome();
        this.setupEventListeners();
        this.showLoading(false);
        remoteNav.updateFocusableItems();
    }
    
    showLoading(show) {
        const el = document.getElementById('loadingOverlay');
        if (el) el.style.display = show ? 'flex' : 'none';
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
            
            this.renderSection(container, '🔥 Destacadas', featured.slice(0, 15));
            this.renderSection(container, '📈 Populares', popular.slice(0, 15));
            this.renderSection(container, '🎬 Películas', movies);
            this.renderSection(container, '📺 Series', tvShows);
            
        } catch (e) { console.error(e); }
    }
    
    renderSection(container, title, items) {
        if (!items?.length) return;
        const section = document.createElement('div');
        section.className = 'section';
        section.innerHTML = `
            <h2 class="section-title">${title}</h2>
            <div class="horizontal-scroll">
                ${items.map(item => this.renderCard(item)).join('')}
            </div>
        `;
        container.appendChild(section);
    }
    
    renderCard(item) {
        const posterUrl = AllCalidadAPI.getPosterUrl(item);
        const title = this.escapeHtml(item.title || 'Sin título');
        const year = item.release_date ? item.release_date.substring(0, 4) : '';
        return `
            <div class="movie-card" data-id="${item._id || item.ID}" data-slug="${item.slug}" data-type="${item.type || 'movies'}">
                <img src="${posterUrl || 'https://placehold.co/200x280/333/E50914?text=REX'}" alt="${title}">
                <div class="movie-info">
                    <div class="movie-title">${title}</div>
                    <div class="movie-year">${year || 'Próximamente'}</div>
                </div>
            </div>
        `;
    }
    
    async showDetail(id, slug, type) {
        this.showLoading(true);
        
        // Obtener item completo
        let item = null;
        if (type === 'movies') {
            const movies = await AllCalidadAPI.getMovies(1, 50);
            item = movies.find(m => m._id == id || m.slug === slug);
        } else {
            const shows = await AllCalidadAPI.getTvShows(1, 50);
            item = shows.find(s => s._id == id || s.slug === slug);
        }
        
        if (!item) {
            this.showLoading(false);
            return;
        }
        
        this.currentItem = item;
        const isSeries = type === 'tvshows' || type === 'animes';
        
        // Actualizar UI
        const backdropUrl = AllCalidadAPI.getBackdropUrl(item);
        document.getElementById('detailBackdrop').style.backgroundImage = `url(${backdropUrl})`;
        document.getElementById('detailTitle').innerHTML = this.escapeHtml(item.title || '');
        document.getElementById('detailYear').innerHTML = item.release_date ? item.release_date.substring(0, 4) : '';
        document.getElementById('detailRating').innerHTML = `★ ${item.rating || '8.0'}`;
        document.getElementById('detailSynopsis').innerHTML = item.overview || 'Sin sinopsis disponible.';
        document.getElementById('badgeType').innerHTML = isSeries ? 'SERIE' : 'PELÍCULA';
        
        if (isSeries) {
            document.getElementById('detailSeasons').innerHTML = '';
            document.getElementById('episodesPanel').style.display = 'block';
            await this.loadEpisodes(item._id || item.ID);
        } else {
            document.getElementById('detailSeasons').innerHTML = '';
            document.getElementById('episodesPanel').style.display = 'none';
        }
        
        // Cambiar página
        document.getElementById('homePage').classList.remove('active');
        document.getElementById('detailPage').classList.add('active');
        
        this.showLoading(false);
        remoteNav.updateFocusableItems();
    }
    
    async loadEpisodes(postId) {
        try {
            this.seasons = await AllCalidadAPI.getSeasons(postId);
            const seasonSelect = document.getElementById('seasonSelect');
            const episodesList = document.getElementById('episodesList');
            
            if (!this.seasons.length) {
                episodesList.innerHTML = '<p>No hay episodios disponibles</p>';
                return;
            }
            
            seasonSelect.innerHTML = this.seasons.map((s, i) => 
                `<option value="${i}">Temporada ${s.seasonNumber || i+1}</option>`
            ).join('');
            
            seasonSelect.onchange = () => this.renderEpisodes(parseInt(seasonSelect.value));
            this.renderEpisodes(0);
            
        } catch (e) { console.error(e); }
    }
    
    renderEpisodes(seasonIndex) {
        const season = this.seasons[seasonIndex];
        if (!season) return;
        
        const episodesList = document.getElementById('episodesList');
        episodesList.innerHTML = season.episodes.map(ep => `
            <div class="episode-item" data-id="${ep.id}" data-season="${season.seasonNumber}" data-episode="${ep.episodeNumber}">
                <div class="episode-number">T${season.seasonNumber}:E${ep.episodeNumber}</div>
                <div class="episode-title">${this.escapeHtml(ep.title)}</div>
            </div>
        `).join('');
        
        // Eventos de episodios
        document.querySelectorAll('.episode-item').forEach(el => {
            el.addEventListener('click', () => {
                const id = el.dataset.id;
                this.playEpisode(id);
            });
        });
        
        remoteNav.updateFocusableItems();
    }
    
    async playContent() {
        if (!this.currentItem) return;
        this.showLoading(true);
        const postId = this.currentItem._id || this.currentItem.ID;
        const type = this.currentItem.type || 'movies';
        const urls = await AllCalidadAPI.getPlayableUrls(postId, type);
        if (urls.length) {
            this.openPlayer(urls, this.currentItem.title);
        } else {
            alert('No se pudo obtener la URL de reproducción');
        }
        this.showLoading(false);
    }
    
    async playEpisode(episodeId) {
        this.showLoading(true);
        const urls = await AllCalidadAPI.getPlayableUrls(episodeId, 'episodes');
        if (urls.length) {
            this.openPlayer(urls, `${this.currentItem.title} - Episodio`);
        } else {
            alert('No se pudo obtener el episodio');
        }
        this.showLoading(false);
    }
    
    openPlayer(urls, title) {
        const playerPage = document.getElementById('playerPage');
        const playerFrame = document.getElementById('playerFrame');
        const nextBtn = document.getElementById('nextEpisodeBtn');
        
        this.episodeUrls = urls;
        this.currentEpisodeIndex = 0;
        
        playerFrame.src = urls[0];
        playerPage.classList.add('active');
        document.getElementById('detailPage').classList.remove('active');
        nextBtn.style.display = urls.length > 1 ? 'block' : 'none';
    }
    
    nextEpisode() {
        if (this.currentEpisodeIndex + 1 < this.episodeUrls.length) {
            this.currentEpisodeIndex++;
            document.getElementById('playerFrame').src = this.episodeUrls[this.currentEpisodeIndex];
        }
    }
    
    closePlayer() {
        const playerPage = document.getElementById('playerPage');
        const playerFrame = document.getElementById('playerFrame');
        playerFrame.src = '';
        playerPage.classList.remove('active');
        document.getElementById('detailPage').classList.add('active');
    }
    
    showHome() {
        document.getElementById('detailPage').classList.remove('active');
        document.getElementById('homePage').classList.add('active');
        remoteNav.updateFocusableItems();
    }
    
    setupEventListeners() {
        // Búsqueda
        document.getElementById('searchBtn').addEventListener('click', () => this.performSearch());
        document.getElementById('searchInput').addEventListener('keypress', (e) => {
            if (e.key === 'Enter') this.performSearch();
        });
        
        // Detalle
        document.getElementById('playButton').addEventListener('click', () => this.playContent());
        document.getElementById('backButton').addEventListener('click', () => this.showHome());
        
        // Reproductor
        document.getElementById('closePlayerBtn').addEventListener('click', () => this.closePlayer());
        document.getElementById('nextEpisodeBtn').addEventListener('click', () => this.nextEpisode());
        
        // Navegación por tarjetas
        document.getElementById('sectionsContainer').addEventListener('click', (e) => {
            const card = e.target.closest('.movie-card');
            if (card) {
                const id = card.dataset.id;
                const slug = card.dataset.slug;
                const type = card.dataset.type;
                this.showDetail(id, slug, type);
            }
        });
    }
    
    async performSearch() {
        const query = document.getElementById('searchInput').value.trim();
        if (!query) return;
        
        this.showLoading(true);
        const results = await AllCalidadAPI.search(query);
        const container = document.getElementById('sectionsContainer');
        container.innerHTML = '';
        this.renderSection(container, `Resultados para: "${query}"`, results);
        this.showLoading(false);
        remoteNav.updateFocusableItems();
    }
    
    escapeHtml(text) {
        const div = document.createElement('div');
        div.textContent = text;
        return div.innerHTML;
    }
}

// Iniciar
document.addEventListener('DOMContentLoaded', () => {
    window.app = new REXApp();
});