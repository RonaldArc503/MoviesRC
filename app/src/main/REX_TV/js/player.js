class Player {
    constructor() {
        this.currentUrl = null;
        this.isPlaying = false;
    }
    
    open(urls, title) {
        const urlsArray = Array.isArray(urls) ? urls : [urls];
        
        // Crear modal/iframe para reproducir
        const modal = document.createElement('div');
        modal.id = 'playerModal';
        modal.style.cssText = `
            position: fixed;
            top: 0;
            left: 0;
            right: 0;
            bottom: 0;
            background: #000;
            z-index: 10000;
            display: flex;
            flex-direction: column;
        `;
        
        const closeBtn = document.createElement('button');
        closeBtn.textContent = '✕';
        closeBtn.style.cssText = `
            position: absolute;
            top: 20px;
            right: 20px;
            width: 40px;
            height: 40px;
            background: rgba(0,0,0,0.7);
            border: none;
            color: white;
            font-size: 20px;
            cursor: pointer;
            z-index: 10001;
            border-radius: 50%;
        `;
        closeBtn.onclick = () => modal.remove();
        
        const iframe = document.createElement('iframe');
        iframe.style.cssText = `
            width: 100%;
            height: 100%;
            border: none;
        `;
        iframe.src = urlsArray[0];
        iframe.allow = 'autoplay; fullscreen';
        
        modal.appendChild(iframe);
        modal.appendChild(closeBtn);
        document.body.appendChild(modal);
        
        // Navegación con control remoto
        modal.addEventListener('keydown', (e) => {
            if (e.key === 'Escape' || e.keyCode === 461) {
                modal.remove();
            }
        });
        modal.focus();
    }
}

const player = new Player();