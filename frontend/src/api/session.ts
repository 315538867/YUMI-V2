import { apiFetch } from './client';

export interface Session {
  id: number;
  username: string;
}

export function login(username: string, password: string): Promise<Session> {
  return apiFetch<Session>('/api/session', {
    method: 'POST',
    body: JSON.stringify({ username, password }),
  });
}

export function currentSession(): Promise<Session> {
  return apiFetch<Session>('/api/session');
}

export async function logout(): Promise<void> {
  await apiFetch('/api/session', { method: 'DELETE' });
}
