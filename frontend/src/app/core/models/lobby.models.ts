export interface LobbyPlayerDto {
  displayName: string;
  joinedAt: string;
}

export interface LobbyDto {
  code: string;
  status?: string;
  maxPlayers?: number;
  players?: LobbyPlayerDto[];
  hasPassword: boolean;
  createdAt?: string;
  isOwner?: boolean;
  isParticipant?: boolean;
}
