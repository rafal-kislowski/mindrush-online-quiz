export interface LobbyPlayerDto {
  displayName: string;
  joinedAt: string;
  ready?: boolean;
  away?: boolean;
  isOwner?: boolean;
  isYou?: boolean;
}

export interface LobbyDto {
  code: string;
  status?: string;
  maxPlayers?: number;
  players?: LobbyPlayerDto[];
  hasPassword: boolean;
  pin?: string | null;
  createdAt?: string;
  isOwner?: boolean;
  isParticipant?: boolean;
  selectedQuizId?: number | null;
}

export type LobbyOwnerType = 'GUEST' | 'AUTHENTICATED';

export interface ActiveLobbyDto {
  code: string;
  status: string;
  createdAt: string;
  hasPassword: boolean;
  maxPlayers: number;
  playerCount: number;
  leaderDisplayName: string;
  ownerType: LobbyOwnerType;
  isOwner: boolean;
  isParticipant: boolean;
}
