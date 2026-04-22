import { AuthUserDto } from '../models/auth.models';

export interface ActiveBonusItem {
  key: 'premium' | 'xp' | 'rankPoints' | 'coins';
  label: string;
  percent: number | null;
  expiresAt: string | null;
  expiresAtMs: number | null;
}

const BOOST_PERCENT = 100;
const MINUTE_MS = 60_000;
const HOUR_MS = 60 * MINUTE_MS;
const DAY_MS = 24 * HOUR_MS;

const BONUS_ICON_BY_KEY: Record<ActiveBonusItem['key'], string> = {
  premium: '/shop/Premium_account_icon.png',
  xp: '/shop/Experience_booster_icon.png',
  rankPoints: '/shop/RP_booster_icon.png',
  coins: '/shop/Coins_booster_icon.png',
};

function timestamp(value: string | null | undefined): number | null {
  const raw = String(value ?? '').trim();
  if (!raw) return null;
  const parsed = Date.parse(raw);
  if (!Number.isFinite(parsed)) return null;
  return parsed;
}

export function buildActiveBonuses(user: AuthUserDto | null | undefined, nowMs = Date.now()): ActiveBonusItem[] {
  if (!user) return [];

  const out: ActiveBonusItem[] = [];
  const premiumRole = (user.roles ?? []).some((role) => String(role ?? '').trim().toUpperCase() === 'PREMIUM');

  const premiumExpiresAtMs = timestamp(user.premiumExpiresAt);
  if (premiumRole && (premiumExpiresAtMs == null || premiumExpiresAtMs > nowMs)) {
    out.push({
      key: 'premium',
      label: 'Premium access',
      percent: null,
      expiresAt: premiumExpiresAtMs == null ? null : String(user.premiumExpiresAt ?? null),
      expiresAtMs: premiumExpiresAtMs,
    });
  }

  const pushTimedBoost = (
    key: ActiveBonusItem['key'],
    label: string,
    expiresAt: string | null | undefined
  ) => {
    const expiresAtMs = timestamp(expiresAt);
    if (expiresAtMs == null || expiresAtMs <= nowMs) return;
    out.push({
      key,
      label,
      percent: BOOST_PERCENT,
      expiresAt: String(expiresAt),
      expiresAtMs,
    });
  };

  pushTimedBoost('xp', 'XP boost', user.xpBoostExpiresAt);
  pushTimedBoost('rankPoints', 'Rank points boost', user.rankPointsBoostExpiresAt);
  pushTimedBoost('coins', 'Coins boost', user.coinsBoostExpiresAt);

  out.sort((a, b) => {
    const aa = a.expiresAtMs ?? Number.MAX_SAFE_INTEGER;
    const bb = b.expiresAtMs ?? Number.MAX_SAFE_INTEGER;
    if (aa !== bb) return aa - bb;
    return a.label.localeCompare(b.label);
  });

  return out;
}

export function activeBonusIconPath(key: ActiveBonusItem['key']): string {
  return BONUS_ICON_BY_KEY[key] ?? BONUS_ICON_BY_KEY.premium;
}

export function activeBonusRemainingLabel(expiresAtMs: number | null, nowMs = Date.now()): string {
  if (expiresAtMs == null || !Number.isFinite(expiresAtMs)) return 'No expiry';
  const leftMs = Math.floor(expiresAtMs - nowMs);
  if (leftMs <= 0) return 'Expired';

  const days = Math.floor(leftMs / DAY_MS);
  const hours = Math.floor((leftMs % DAY_MS) / HOUR_MS);
  const minutes = Math.floor((leftMs % HOUR_MS) / MINUTE_MS);

  if (days > 0) {
    return `Ends in ${days}d ${hours}h`;
  }
  if (hours > 0) {
    return `Ends in ${hours}h ${minutes}m`;
  }
  if (minutes > 0) {
    return `Ends in ${minutes}m`;
  }
  return 'Ends in <1m';
}

