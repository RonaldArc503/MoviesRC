class RemoteNavigation {
    constructor() {
        this.focusableItems = [];
        this.currentIndex = -1;
        this.lastFocusedKey = '';
        this.pendingMutationUpdate = null;
        this.selectors = [
            '.movie-card',
            '.episode-item',
            '.search-input',
            '.search-btn',
            '.btn-play',
            '.btn-mylist',
            '.back-button',
            '.season-select',
            '.next-episode-btn',
            '.close-player'
        ].join(', ');
        this.init();
    }

    init() {
        document.addEventListener('keydown', (e) => this.handleKey(e));
        this.updateFocusableItems();

        const observer = new MutationObserver(() => {
            clearTimeout(this.pendingMutationUpdate);
            this.pendingMutationUpdate = setTimeout(() => this.updateFocusableItems(), 40);
        });
        observer.observe(document.body, {
            childList: true,
            subtree: true,
            attributes: true,
            attributeFilter: ['class', 'style', 'disabled']
        });
    }

    getActivePage() {
        return document.querySelector('.page.active');
    }

    isVisible(el) {
        if (!el) return false;
        const style = window.getComputedStyle(el);
        if (style.display === 'none' || style.visibility === 'hidden' || style.opacity === '0') {
            return false;
        }
        if (el.offsetParent === null && style.position !== 'fixed') {
            return false;
        }
        if (el.disabled) {
            return false;
        }

        const activePage = this.getActivePage();
        if (!activePage) return true;
        if (activePage.contains(el)) return true;

        return !el.closest('.page');
    }

    getFocusKey(el) {
        if (!el) return '';
        if (!el.dataset.focusKey) {
            const base = el.id || el.dataset.id || el.className || 'focus-item';
            el.dataset.focusKey = `${base}-${Math.random().toString(36).slice(2, 8)}`;
        }
        return el.dataset.focusKey;
    }

    updateFocusableItems(options = {}) {
        const { preferredSelector = null, resetToStart = false } = options;
        const previousFocused = this.focusableItems[this.currentIndex] || null;
        const previousKey = previousFocused ? this.getFocusKey(previousFocused) : this.lastFocusedKey;

        this.focusableItems = Array
            .from(document.querySelectorAll(this.selectors))
            .filter((el) => this.isVisible(el));

        this.focusableItems.forEach((el) => el.classList.remove('focused'));

        if (!this.focusableItems.length) {
            this.currentIndex = -1;
            return;
        }

        let targetIndex = -1;

        if (preferredSelector) {
            const preferred = this.focusableItems.find((el) => el.matches(preferredSelector));
            if (preferred) {
                targetIndex = this.focusableItems.indexOf(preferred);
            }
        }

        if (targetIndex < 0 && !resetToStart && previousKey) {
            targetIndex = this.focusableItems.findIndex((el) => this.getFocusKey(el) === previousKey);
        }

        if (targetIndex < 0 && !resetToStart && this.currentIndex >= 0) {
            targetIndex = Math.min(this.currentIndex, this.focusableItems.length - 1);
        }

        if (targetIndex < 0) {
            targetIndex = 0;
        }

        this.focusItem(targetIndex);
    }

    focusElement(el, options = {}) {
        if (!el) return;

        if (!this.focusableItems.includes(el)) {
            this.updateFocusableItems();
        }

        const index = this.focusableItems.indexOf(el);
        if (index >= 0) {
            this.focusItem(index, options);
        }
    }

    focusItem(index, options = {}) {
        const { scroll = true } = options;

        if (!this.focusableItems.length) return;
        if (index < 0) index = 0;
        if (index >= this.focusableItems.length) index = this.focusableItems.length - 1;

        this.focusableItems.forEach((el) => el.classList.remove('focused'));
        this.currentIndex = index;

        const item = this.focusableItems[index];
        if (!item) return;

        item.classList.add('focused');
        this.lastFocusedKey = this.getFocusKey(item);

        if (typeof item.focus === 'function') {
            item.focus({ preventScroll: true });
        }

        if (scroll) {
            item.scrollIntoView({ behavior: 'smooth', block: 'nearest', inline: 'nearest' });
        }
    }

    activateFocused(e) {
        const focused = this.focusableItems[this.currentIndex];
        if (!focused) return;

        const tag = (focused.tagName || '').toLowerCase();

        if (tag === 'input' || tag === 'textarea') {
            e.preventDefault();
            focused.focus();
            if (typeof focused.select === 'function') {
                focused.select();
            }
            if (window.app && typeof window.app.onSearchInputActivated === 'function') {
                window.app.onSearchInputActivated();
            }
            return;
        }

        if (tag === 'select') {
            e.preventDefault();
            focused.focus();
            focused.click();
            return;
        }

        e.preventDefault();
        focused.click();
    }

    handleKey(e) {
        const code = e.keyCode || e.which;
        const key = e.key;

        if (code === 27 || code === 461 || code === 10009 || key === 'Escape' || key === 'GoBack') {
            e.preventDefault();
            if (document.getElementById('playerPage').classList.contains('active')) {
                window.app.closePlayer();
            } else if (document.getElementById('detailPage').classList.contains('active')) {
                window.app.showHome();
            }
            return;
        }

        if (!this.focusableItems.length) {
            this.updateFocusableItems();
            return;
        }

        if (code === 13 || code === 23 || key === 'Enter') {
            this.activateFocused(e);
            return;
        }

        if (code === 37 || key === 'ArrowLeft' || code === 38 || key === 'ArrowUp') {
            e.preventDefault();
            this.focusItem(this.currentIndex - 1);
            return;
        }

        if (code === 39 || key === 'ArrowRight' || code === 40 || key === 'ArrowDown') {
            e.preventDefault();
            this.focusItem(this.currentIndex + 1);
        }
    }
}

const remoteNav = new RemoteNavigation();
