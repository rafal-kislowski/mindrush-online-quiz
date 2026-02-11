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
  pin?: string | null;
  createdAt?: string;
  isOwner?: boolean;
  isParticipant?: boolean;
  selectedQuizId?: number | null;
}
