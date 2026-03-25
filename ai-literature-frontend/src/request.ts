import axios from 'axios';
import { message } from 'ant-design-vue';
import { API_BASE_URL } from '@/constants/user';

const PUBLIC_PATHS = new Set(['/user/login', '/user/register']);

const getRelativePath = () => {
  if (typeof window === 'undefined') {
    return '/';
  }
  const basePath = import.meta.env.BASE_URL.replace(/\/$/, '');
  const pathname = window.location.pathname;
  if (basePath && basePath !== '/' && pathname.startsWith(basePath)) {
    return pathname.slice(basePath.length) || '/';
  }
  return pathname;
};

export const redirectToLogin = () => {
  if (typeof window === 'undefined') {
    return;
  }
  if (PUBLIC_PATHS.has(getRelativePath())) {
    return;
  }
  const redirect = `${getRelativePath()}${window.location.search}`;
  const loginUrl = `${import.meta.env.BASE_URL}user/login?redirect=${encodeURIComponent(redirect)}`;
  window.location.assign(loginUrl);
};

const myAxios = axios.create({
  baseURL: API_BASE_URL,
  timeout: 60000,
  withCredentials: true,
});

myAxios.interceptors.response.use(
  (response) => response,
  (error) => {
    if (error?.response?.status === 401) {
      if (typeof window !== 'undefined' && !PUBLIC_PATHS.has(getRelativePath())) {
        message.warning('Please login first');
      }
      redirectToLogin();
    }
    return Promise.reject(error);
  },
);

export default myAxios;
