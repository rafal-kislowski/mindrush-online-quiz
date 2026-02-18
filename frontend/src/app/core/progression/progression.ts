export interface LevelProgress {
  level: number; // 0..99
  maxLevel: boolean;
  xp: number;
  levelXpStart: number;
  levelXpEnd: number; // xp required to reach next level (or same for max)
  xpInLevel: number;
  xpToNext: number;
  progress: number; // 0..1
}

export interface LevelTheme {
  ringStrong: string;
  ringDim: string;
  text: string;
}

export interface RankInfo {
  name: string;
  color: string;
}

const MAX_LEVEL = 99;

function clamp(n: number, min: number, max: number): number {
  return Math.max(min, Math.min(max, n));
}

function hexToRgb(hex: string): { r: number; g: number; b: number } | null {
  const h = hex.replace('#', '').trim();
  if (h.length !== 6) return null;
  const n = Number.parseInt(h, 16);
  if (!Number.isFinite(n)) return null;
  return { r: (n >> 16) & 255, g: (n >> 8) & 255, b: n & 255 };
}

function rgba(hex: string, a: number): string {
  const rgb = hexToRgb(hex);
  if (!rgb) return `rgba(255,153,52,${clamp(a, 0, 1)})`;
  return `rgba(${rgb.r},${rgb.g},${rgb.b},${clamp(a, 0, 1)})`;
}

function xpDeltaForNextLevel(level: number): number {
  // Not too easy: scaling accelerates significantly at higher levels.
  const l = level + 1;
  const delta = 250 + 75 * Math.pow(l, 1.6);
  return Math.max(250, Math.round(delta));
}

function xpToReachLevel(level: number): number {
  // level 0 => 0 xp
  if (level <= 0) return 0;
  let total = 0;
  for (let i = 0; i < level; i++) {
    total += xpDeltaForNextLevel(i);
  }
  return total;
}

export function computeLevelProgress(xpRaw: number): LevelProgress {
  const xp = Math.max(0, Math.floor(xpRaw || 0));

  let level = 0;
  for (let l = 0; l < MAX_LEVEL; l++) {
    const start = xpToReachLevel(l);
    const end = xpToReachLevel(l + 1);
    if (xp < end) {
      level = l;
      break;
    }
    level = l + 1;
  }

  level = clamp(level, 0, MAX_LEVEL);
  const maxLevel = level >= MAX_LEVEL;

  const levelXpStart = xpToReachLevel(level);
  const levelXpEnd = maxLevel ? levelXpStart : xpToReachLevel(level + 1);
  const xpInLevel = maxLevel
    ? 0
    : clamp(xp - levelXpStart, 0, levelXpEnd - levelXpStart);
  const xpToNext = maxLevel ? 0 : Math.max(0, levelXpEnd - xp);
  const denom = Math.max(1, levelXpEnd - levelXpStart);
  const progress = maxLevel ? 1 : clamp(xpInLevel / denom, 0, 1);

  return {
    level,
    maxLevel,
    xp,
    levelXpStart,
    levelXpEnd,
    xpInLevel,
    xpToNext,
    progress,
  };
}

export function levelTheme(levelRaw: number): LevelTheme {
  const level = clamp(Math.floor(levelRaw || 0), 0, MAX_LEVEL);

  // Staged colors as you level up
  const base =
    level < 10
      ? '#ff9a35' // orange
      : level < 20
        ? '#ffd24a' // gold
        : level < 40
          ? '#31d1a0' // green
          : level < 60
            ? '#4aa8ff' // blue
            : level < 80
              ? '#ff6bd6' // purple
              : '#ff6b6b'; // pink

  return {
    ringStrong: rgba(base, 0.95),
    ringDim: rgba(base, 0.14),
    text: base,
  };
}

export function rankForPoints(rpRaw: number): RankInfo {
  const rp = Math.max(0, Math.floor(rpRaw || 0));

  const tiers: Array<{ min: number; name: string; color: string }> = [
    { min: 0, name: 'Rookie', color: '#9AA4B2' },

    // Bronze
    { min: 100, name: 'Bronze III', color: '#CD7F32' },
    { min: 200, name: 'Bronze II', color: '#CD7F32' },
    { min: 300, name: 'Bronze I', color: '#CD7F32' },

    // Silver
    { min: 450, name: 'Silver III', color: '#C0C0C0' },
    { min: 600, name: 'Silver II', color: '#C0C0C0' },
    { min: 750, name: 'Silver I', color: '#C0C0C0' },

    // Gold
    { min: 950, name: 'Gold III', color: '#E6B800' },
    { min: 1150, name: 'Gold II', color: '#E6B800' },
    { min: 1350, name: 'Gold I', color: '#E6B800' },

    // Platinum
    { min: 1600, name: 'Platinum III', color: '#00C2A8' },
    { min: 1850, name: 'Platinum II', color: '#00C2A8' },
    { min: 2100, name: 'Platinum I', color: '#00C2A8' },

    // Diamond
    { min: 2400, name: 'Diamond III', color: '#3AA0FF' },
    { min: 2700, name: 'Diamond II', color: '#3AA0FF' },
    { min: 3000, name: 'Diamond I', color: '#3AA0FF' },

    // Master (deep purple)
    { min: 3400, name: 'Master III', color: '#7B3FE4' },
    { min: 3800, name: 'Master II', color: '#7B3FE4' },
    { min: 4200, name: 'Master I', color: '#7B3FE4' },

    // Legend (royal indigo / premium, zamiast różu)
    { min: 4700, name: 'Legend III', color: '#4B2EFF' },
    { min: 5200, name: 'Legend II', color: '#4B2EFF' },
    { min: 5800, name: 'Legend I', color: '#4B2EFF' },

    // Mythic (top)
    { min: 6500, name: 'Mythic', color: '#E10600' },
  ];

  let current = tiers[0];
  for (const t of tiers) {
    if (rp >= t.min) current = t;
  }
  return { name: current.name, color: current.color };
}

export const progressionConstants = {
  MAX_LEVEL,
};
