import { createRouter, createWebHistory } from 'vue-router';
import BasicLayout from '@/layouts/BasicLayout.vue';
import HomeView from '@/views/HomeView.vue';
import UserLoginPage from '@/pages/user/UserLoginPage.vue';
import UserRegisterPage from '@/pages/user/UserRegisterPage.vue';
import UserManagePage from '@/pages/admin/UserManagePage.vue';
import { useLoginUserStore } from '@/stores/loginUser';
import { pinia } from '@/stores';
import { USER_ROLE_ADMIN } from '@/constants/user';

declare module 'vue-router' {
  interface RouteMeta {
    public?: boolean;
    requiresAdmin?: boolean;
  }
}

const router = createRouter({
  history: createWebHistory(import.meta.env.BASE_URL),
  routes: [
    {
      path: '/user/login',
      name: 'user-login',
      component: UserLoginPage,
      meta: { public: true },
    },
    {
      path: '/user/register',
      name: 'user-register',
      component: UserRegisterPage,
      meta: { public: true },
    },
    {
      path: '/',
      component: BasicLayout,
      children: [
        {
          path: '',
          name: 'home',
          component: HomeView,
        },
        {
          path: 'admin/user-manage',
          name: 'user-manage',
          component: UserManagePage,
          meta: { requiresAdmin: true },
        },
      ],
    },
  ],
});

let bootstrapPromise: Promise<void> | null = null;

router.beforeEach(async (to) => {
  const loginUserStore = useLoginUserStore(pinia);
  if (!loginUserStore.initialized) {
    bootstrapPromise ??= loginUserStore.fetchLoginUser().then(() => undefined).finally(() => {
      bootstrapPromise = null;
    });
    await bootstrapPromise;
  }

  if (to.meta.public) {
    if (loginUserStore.loginUser && to.path === '/user/login') {
      return '/';
    }
    return true;
  }

  if (!loginUserStore.loginUser) {
    return {
      path: '/user/login',
      query: { redirect: to.fullPath },
    };
  }

  if (to.meta.requiresAdmin && loginUserStore.loginUser.userRole !== USER_ROLE_ADMIN) {
    return '/';
  }

  return true;
});

export default router;
