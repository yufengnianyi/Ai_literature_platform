const DEFAULT_API_BASE_URL = 'http://localhost:8081/api';

export const API_BASE_URL = (import.meta.env.VITE_API_BASE_URL || DEFAULT_API_BASE_URL).replace(/\/$/, '');
export const USER_ROLE_ADMIN = 'admin';
export const USER_ROLE_USER = 'user';