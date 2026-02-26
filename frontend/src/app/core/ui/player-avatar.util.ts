export function avatarInitials(displayName: string | null | undefined): string {
  const trimmed = String(displayName ?? '').trim();
  if (!trimmed) return 'U';

  const words = trimmed.split(/\s+/u).filter(Boolean);
  const pickFirstAlphaNum = (value: string): string => {
    const chars = Array.from(value);
    const first = chars.find((ch) => /[\p{L}\p{N}]/u.test(ch));
    return first ?? '';
  };

  let raw = '';

  if (words.length >= 2) {
    raw = `${pickFirstAlphaNum(words[0])}${pickFirstAlphaNum(words[words.length - 1])}`;
  } else {
    const chars = Array.from(words[0] ?? '').filter((ch) => /[\p{L}\p{N}]/u.test(ch));
    raw = chars.slice(0, 2).join('');
  }

  const initials = raw.toUpperCase().slice(0, 2);
  return initials || 'U';
}
