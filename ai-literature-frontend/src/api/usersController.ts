// @ts-ignore
/* eslint-disable */
import request from '@/request';

export async function register(body: API.UserRegisterRequest, options?: { [key: string]: any }) {
  return request<string>('/user/register', {
    method: 'POST',
    data: body,
    ...(options || {}),
  });
}

export async function login(body: API.UserLoginRequest, options?: { [key: string]: any }) {
  return request<API.LoginUserVO>('/user/login', {
    method: 'POST',
    data: body,
    skipGlobalErrorMessage: true,
    ...(options || {}),
  });
}

export async function getLoginUser(options?: { [key: string]: any }) {
  return request<API.LoginUserVO>('/user/get/login', {
    method: 'GET',
    ...(options || {}),
  });
}

export async function logout(options?: { [key: string]: any }) {
  return request<boolean>('/user/logout', {
    method: 'POST',
    ...(options || {}),
  });
}

export async function deleteUser(body: API.UserDeleteRequest, options?: { [key: string]: any }) {
  return request<boolean>('/user/delete', {
    method: 'POST',
    data: body,
    ...(options || {}),
  });
}

export async function listUserByPageVo(body: API.UserQueryRequest, options?: { [key: string]: any }) {
  return request<API.PageUserVO>('/user/list/page/vo', {
    method: 'POST',
    data: body,
    ...(options || {}),
  });
}
