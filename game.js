const canvas = document.getElementById('gameCanvas');
const ctx = canvas.getContext('2d');

// Set crisp canvas dimensions
canvas.width = 440;
canvas.height = 650;

// Game State Variables
let score = 0;
let highScore = localStorage.getItem('neon_highscore') || 0;
document.getElementById('high-score').innerText = highScore;

let gameActive = true;
let speedMultiplier = 1;
let screenShake = 0;

// Control arrays
let keys = {};

// Player Object
const player = {
    x: canvas.width / 2 - 15,
    y: canvas.height - 80,
    width: 30,
    height: 30,
    speed: 7,
    color: '#00f3ff',
    draw() {
        ctx.save();
        ctx.shadowBlur = 15;
        ctx.shadowColor = this.color;
        ctx.fillStyle = this.color;
        
        // Draw a sleek neon triangle ship
        ctx.beginPath();
        ctx.moveTo(this.x + this.width / 2, this.y);
        ctx.lineTo(this.x, this.y + this.height);
        ctx.lineTo(this.x + this.width, this.y + this.height);
        ctx.closePath();
        ctx.fill();
        ctx.restore();
    },
    update() {
        if (keys['ArrowLeft'] || keys['a']) this.x -= this.speed;
        if (keys['ArrowRight'] || keys['d']) this.x += this.speed;
        
        // Boundaries
        if (this.x < 0) this.x = 0;
        if (this.x + this.width > canvas.width) this.x = canvas.width - this.width;
    }
};

// Pools for Entities
let obstacles = [];
let particles = [];

class Obstacle {
    constructor() {
        this.width = Math.random() * 60 + 40;
        this.height = 15;
        this.x = Math.random() * (canvas.width - this.width);
        this.y = -this.height;
        this.speed = (Math.random() * 3 + 4) * speedMultiplier;
        // Alternate neon colors
        this.color = Math.random() > 0.5 ? '#ff0055' : '#ffee00';
    }

    draw() {
        ctx.save();
        ctx.shadowBlur = 12;
        ctx.shadowColor = this.color;
        ctx.fillStyle = this.color;
        ctx.fillRect(this.x, this.y, this.width, this.height);
        ctx.restore();
    }

    update() {
        this.y += this.speed;
    }
}

class Particle {
    constructor(x, y, color) {
        this.x = x;
        this.y = y;
        this.size = Math.random() * 3 + 1;
        this.speedX = Math.random() * 6 - 3;
        this.speedY = Math.random() * 6 - 3;
        this.color = color;
        this.alpha = 1;
    }

    draw() {
        ctx.save();
        ctx.globalAlpha = this.alpha;
        ctx.fillStyle = this.color;
        ctx.fillRect(this.x, this.y, this.size, this.size);
        ctx.restore();
    }

    update() {
        this.x += this.speedX;
        this.y += this.speedY;
        this.alpha -= 0.02;
    }
}

// Spawning Logic
let obstacleTimer = 0;
function handleObstacles() {
    obstacleTimer++;
    if (obstacleTimer % Math.max(15, Math.floor(40 / speedMultiplier)) === 0) {
        obstacles.push(new Obstacle());
    }

    for (let i = obstacles.length - 1; i >= 0; i--) {
        obstacles[i].update();
        obstacles[i].draw();

        // Check Collision
        if (
            obstacles[i].x < player.x + player.width &&
            obstacles[i].x + obstacles[i].width > player.x &&
            obstacles[i].y < player.y + player.height &&
            obstacles[i].y + obstacles[i].height > player.y
        ) {
            triggerGameOver();
        }

        // Clean up off-screen items & award score
        if (obstacles[i].y > canvas.height) {
            obstacles.splice(i, 1);
            score += 10;
            document.getElementById('score').innerText = score;
            
            // Speed scaling smoothly as score increases
            speedMultiplier = 1 + (score / 300);
        }
    }
}

function handleParticles() {
    for (let i = particles.length - 1; i >= 0; i--) {
        particles[i].update();
        particles[i].draw();
        if (particles[i].alpha <= 0) {
            particles.splice(i, 1);
        }
    }
}

function createExplosion(x, y, color) {
    for (let i = 0; i < 25; i++) {
        particles.push(new Particle(x, y, color));
    }
}

function triggerGameOver() {
    gameActive = false;
    screenShake = 20;
    createExplosion(player.x + player.width/2, player.y + player.height/2, player.color);
    
    if (score > highScore) {
        highScore = score;
        localStorage.setItem('neon_highscore', highScore);
        document.getElementById('high-score').innerText = highScore;
    }

    document.getElementById('final-score').innerText = score;
    document.getElementById('game-over-screen').classList.remove('hidden');
}

function resetGame() {
    score = 0;
    speedMultiplier = 1;
    obstacles = [];
    particles = [];
    player.x = canvas.width / 2 - 15;
    document.getElementById('score').innerText = score;
    document.getElementById('game-over-screen').classList.add('hidden');
    gameActive = true;
    animate();
}

// Event Listeners
window.addEventListener('keydown', (e) => keys[e.key] = true);
window.addEventListener('keyup', (e) => keys[e.key] = false);
document.getElementById('restart-btn').addEventListener('click', resetGame);

// Main Engine Loop
function animate() {
    if (!gameActive && screenShake <= 0) return;

    ctx.clearRect(0, 0, canvas.width, canvas.height);

    // Apply screen shake effect matrix if active
    ctx.save();
    if (screenShake > 0) {
        let dx = (Math.random() - 0.5) * screenShake;
        let dy = (Math.random() - 0.5) * screenShake;
        ctx.translate(dx, dy);
        screenShake *= 0.9; // dampening
        if (screenShake < 0.5) screenShake = 0;
    }

    handleParticles();

    if (gameActive) {
        player.update();
        player.draw();
        handleObstacles();
    }

    ctx.restore();
    requestAnimationFrame(animate);
}

// Fire up engine
animate();
