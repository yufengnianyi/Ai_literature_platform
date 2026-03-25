import { defineStore } from 'pinia';
import { getLoginUser } from '@/api/usersController';

interface LoginUserState {
  loginUser: API.LoginUserVO | null;
  initialized: boolean;
}

export const useLoginUserStore = defineStore('login-user', {
  state: (): LoginUserState => ({
    loginUser: null,
    initialized: false,
  }),
  actions: {
    async fetchLoginUser() {
      try {
        const response = await getLoginUser();
        this.loginUser = response.data;
      } catch {
        this.loginUser = null;
      } finally {
        this.initialized = true;
      }
      return this.loginUser;
    },
    setLoginUser(loginUser: API.LoginUserVO | null) {
      this.loginUser = loginUser;
      this.initialized = true;
    },
    clearLoginUser() {
      this.loginUser = null;
      this.initialized = true;
    },
  },
});
