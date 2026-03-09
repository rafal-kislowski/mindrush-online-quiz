export interface AuthUserDto {
  id: number;
  email: string;
  displayName: string;
  roles: string[];
  rankPoints: number;
  xp: number;
  coins: number;
  emailVerified: boolean;
}
