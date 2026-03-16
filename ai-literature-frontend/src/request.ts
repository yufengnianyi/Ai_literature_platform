import axios from 'axios';
import { message } from 'ant-design-vue';
import { API_BASE_URL, DEFAULT_USER } from '@/constants/user';

const myAxios = axios.create({
  baseURL: API_BASE_URL,
  timeout: 60000,
  withCredentials: true,
});

myAxios.interceptors.request.use(
  function (config) {
    const headers = config.headers as { set?: (name: string, value: string) => void } | undefined;
    if (headers && typeof headers.set === 'function') {
      headers.set('X-User-Id', DEFAULT_USER.userId);
    } else {
      config.headers = {
        ...(config.headers as Record<string, unknown> | undefined),
        'X-User-Id': DEFAULT_USER.userId,
      } as any;
    }
    return config;
  },
  function (error) {
    return Promise.reject(error);
  },
);

myAxios.interceptors.response.use(
  function (response) {
    const { data } = response;
    if (data && data.code === 40100) {
      if (
        response.request.responseURL &&
        !response.request.responseURL.includes('user/get/login') &&
        !window.location.pathname.includes('/user/login')
      ) {
        message.warning('Please login first');
      }
    }
    return response;
  },
  function (error) {
    return Promise.reject(error);
  },
);

export default myAxios;
