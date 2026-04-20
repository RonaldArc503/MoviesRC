class RemoteNavigation {
    constructor() {
        this.focusableItems = [];
        this.currentIndex = 0;
        this.init();
    }
    
    init() {
        document.addEventListener('keydown', (e) => this.handleKey(e));
        this.updateFocusableItems();
        
        const observer = new MutationObserver(() => this.updateFocusableItems());
        observer.observe(document.body, { childList: true, subtree: true });
    }
    
    updateFocusableItems() {
        const selectors = '.movie-card, .episode-item, .search-input, .search-btn, .btn-play, .btn-mylist, .back-button, .season-select, .next-episode-btn, .close-player';
        this.focusableItems = Array.from(document.querySelectorAll(selectors));
        this.focusableItems.forEach(el => el.classList.remove('focused'));
        if (this.focusableItems.length) {
            this.currentIndex = 0;
            this.focusItem(0);
        }
    }
    
    focusItem(index) {
        if (!this.focusableItems.length) return;
        if (index < 0) index = 0;
        if (index >= this.focusableItems.length) index = this.focusableItems.length - 1;
        
        this.focusableItems.forEach(el => el.classList.remove('focused'));
        this.currentIndex = index;
        const item = this.focusableItems[index];
        if (item) {
            item.classList.add('focused');
            item.scrollIntoView({ behavior: 'smooth', block: 'nearest', inline: 'center' });
        }
    }
    
    handleKey(e) {
        const key = e.keyCode;
        switch(key) {
            case 37: case 38: // izquierda, arriba
                e.preventDefault();
                this.focusItem(this.currentIndex - 1);
                break;
            case 39: case 40: // derecha, abajo
                e.preventDefault();
                this.focusItem(this.currentIndex + 1);
                break;
            case 13: // Enter
                e.preventDefault();
                const focused = this.focusableItems[this.currentIndex];
                if (focused) focused.click();
                break;
            case 27: case 461: // Escape o Back LG
                e.preventDefault();
                if (document.getElementById('playerPage').classList.contains('active')) {
                    window.app.closePlayer();
                } else if (document.getElementById('detailPage').classList.contains('active')) {
                    window.app.showHome();
                }
                break;
        }
    }
}

const remoteNav = new RemoteNavigation();