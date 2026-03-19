const DEFAULT_API_BASE_URL = 'http://localhost:8081/api';

export const API_BASE_URL = (import.meta.env.VITE_API_BASE_URL || DEFAULT_API_BASE_URL).replace(/\/$/, '');

export const DEFAULT_USER = {
  userId: '69544454-d59e-4baa-8bbb-95b117f12335',
  username: 'alice'
} as const;
