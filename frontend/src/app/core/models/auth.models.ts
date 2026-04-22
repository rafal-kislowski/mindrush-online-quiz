export interface AuthUserDto {
  id: number;
  email: string;
  displayName: string;
  roles: string[];
  rankPoints: number;
  xp: number;
  coins: number;
  emailVerified: boolean;
  createdAt?: string | null;
  lastLoginAt?: string | null;
  lastDisplayNameChangeAt?: string | null;
  premiumActivatedAt?: string | null;
  premiumExpiresAt?: string | null;
  xpBoostExpiresAt?: string | null;
  rankPointsBoostExpiresAt?: string | null;
  coinsBoostExpiresAt?: string | null;
}
