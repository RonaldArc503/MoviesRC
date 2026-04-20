const API_BASE = 'https://allcalidad.re/api/rest';
const SITE_BASE = 'https://allcalidad.re';

class AllCalidadAPI {
    
    static async getMovies(page = 1, perPage = 20) {
        try {
            const res = await fetch(`${API_BASE}/listing?post_type=movies&page=${page}&posts_per_page=${perPage}`);
            const data = await res.json();
            return (!data.error && data.data) ? (data.data.posts || data.data) : [];
        } catch (e) { return []; }
    }
    
    static async getTvShows(page = 1, perPage = 20) {
        try {
            const res = await fetch(`${API_BASE}/listing?post_type=tvshows&page=${page}&posts_per_page=${perPage}`);
            const data = await res.json();
            return (!data.error && data.data) ? (data.data.posts || data.data) : [];
        } catch (e) { return []; }
    }
    
    static async getPopularMovies() {
        try {
            const res = await fetch(`${API_BASE}/tops?range=month&limit=20&post_type=movies`);
            const data = await res.json();
            return (!data.error && data.data) ? (data.data.posts || data.data) : [];
        } catch (e) { return []; }
    }
    
    static async getPopularTvShows() {
        try {
            const res = await fetch(`${API_BASE}/tops?range=month&limit=20&post_type=tvshows`);
            const data = await res.json();
            return (!data.error && data.data) ? (data.data.posts || data.data) : [];
        } catch (e) { return []; }
    }
    
    static async getFeatured() {
        try {
            const res = await fetch(`${API_BASE}/sliders?type=movies&posts_per_page=8`);
            const data = await res.json();
            return (!data.error && data.data) ? (data.data.posts || data.data) : [];
        } catch (e) { return []; }
    }
    
    static async search(query, page = 1) {
        try {
            const encoded = encodeURIComponent(query);
            const res = await fetch(`${API_BASE}/search?query=${encoded}&page=${page}&post_type=movies%2Ctvshows%2Canimes&posts_per_page=30`);
            const data = await res.json();
            return (!data.error && data.data) ? (data.data.posts || data.data) : [];
        } catch (e) { return []; }
    }
    
    static async getSeasons(postId) {
        try {
            const res = await fetch(`${API_BASE}/episodes?post_id=${postId}`);
            const data = await res.json();
            if (!data.error && data.data) {
                if (data.data.seasons) return data.data.seasons;
                if (data.data.episodes) return this.groupEpisodesBySeason(data.data.episodes);
            }
            return [];
        } catch (e) { return []; }
    }
    
    static groupEpisodesBySeason(episodes) {
        const seasons = {};
        episodes.forEach(ep => {
            const seasonNum = ep.season_number || ep.season || 1;
            if (!seasons[seasonNum]) {
                seasons[seasonNum] = { seasonNumber: seasonNum, episodes: [] };
            }
            seasons[seasonNum].episodes.push({
                id: ep.post_id || ep._id || ep.ID,
                episodeNumber: ep.episode_number || ep.episode || 1,
                title: ep.title || `Episodio ${ep.episode_number || 1}`,
                overview: ep.overview || '',
                stillUrl: ep.still_path || ep.still || ''
            });
        });
        return Object.values(seasons);
    }
    
    static async getPlayableUrls(postId, postType = 'movies') {
        try {
            await this.hit(postId, postType);
            const res = await fetch(`${API_BASE}/player?post_id=${postId}&_any=1`);
            const data = await res.json();
            const urls = [];
            if (!data.error && data.data) {
                if (data.data.embed_url) urls.push(data.data.embed_url);
                if (data.data.servers) {
                    data.data.servers.forEach(s => { if (s.url) urls.push(s.url); });
                }
            }
            return urls;
        } catch (e) { return []; }
    }
    
    static async hit(postId, postType) {
        try {
            await fetch(`${API_BASE}/hit?nocache=${Date.now()}&post_id=${postId}&post_type=${postType}`);
        } catch (e) {}
    }
    
    static getPosterUrl(item) {
        if (item.images?.poster) return `https://allcalidad.re${item.images.poster}`;
        if (item.poster) return `https://allcalidad.re${item.poster}`;
        return null;
    }
    
    static getBackdropUrl(item) {
        if (item.images?.backdrop) return `https://allcalidad.re${item.images.backdrop}`;
        return this.getPosterUrl(item);
    }
}