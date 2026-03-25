<template>
  <section class="auth-page">
    <div class="auth-shell">
      <div class="auth-brand">
        <span class="auth-kicker">User Module</span>
        <h1 class="auth-title">Sign in to your research workspace</h1>
        <p class="auth-subtitle">
          Session-based access keeps conversation history, admin actions, and AI workflows scoped to the current account.
        </p>
      </div>

      <a-card class="auth-card" :bordered="false">
        <a-form layout="vertical" :model="formState" @finish="handleSubmit">
          <a-form-item
            label="User Account"
            name="userAccount"
            :rules="[{ required: true, message: 'Please input user account' }]"
          >
            <a-input v-model:value="formState.userAccount" size="large" />
          </a-form-item>

          <a-form-item
            label="Password"
            name="userPassword"
            :rules="[{ required: true, message: 'Please input password' }]"
          >
            <a-input-password v-model:value="formState.userPassword" size="large" />
          </a-form-item>

          <a-button type="primary" size="large" class="submit-button" html-type="submit" :loading="submitting">
            Login
          </a-button>
        </a-form>

        <div class="auth-footer">
          <span>No account yet?</span>
          <router-link to="/user/register">Create one</router-link>
        </div>
      </a-card>
    </div>
  </section>
</template>

<script setup lang="ts">
import { reactive, ref } from 'vue';
import { message } from 'ant-design-vue';
import { useRouter, useRoute } from 'vue-router';
import { login } from '@/api/usersController';
import { useLoginUserStore } from '@/stores/loginUser';

const DEFAULT_LOGIN_FORM: API.UserLoginRequest = {
  userAccount: '',
  userPassword: '',
};

const router = useRouter();
const route = useRoute();
const loginUserStore = useLoginUserStore();
const submitting = ref(false);
const formState = reactive<API.UserLoginRequest>({ ...DEFAULT_LOGIN_FORM });

const handleSubmit = async () => {
  submitting.value = true;
  try {
    const response = await login(formState);
    loginUserStore.setLoginUser(response.data);
    message.success('Login successful');
    const redirect = typeof route.query.redirect === 'string' ? route.query.redirect : '/';
    await router.push(redirect);
  } catch (error) {
    console.error(error);
    message.error('Login failed');
  } finally {
    submitting.value = false;
  }
};

</script>

<style scoped>
.auth-page {
  min-height: 100vh;
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 24px;
  background:
    radial-gradient(circle at top left, rgba(37, 99, 235, 0.12), transparent 30%),
    linear-gradient(135deg, #eef4ff, #f8fbff 55%, #eef6f0);
}

.auth-shell {
  width: min(960px, 100%);
  display: grid;
  grid-template-columns: 1.15fr 0.85fr;
  gap: 24px;
}

.auth-brand,
.auth-card {
  border-radius: 28px;
  border: 1px solid rgba(148, 163, 184, 0.2);
  background: rgba(255, 255, 255, 0.84);
  backdrop-filter: blur(18px);
  box-shadow: 0 18px 60px rgba(15, 23, 42, 0.08);
}

.auth-brand {
  padding: 36px;
}

.auth-kicker {
  display: inline-block;
  font-size: 12px;
  text-transform: uppercase;
  letter-spacing: 0.18em;
  color: #2563eb;
}

.auth-title {
  margin: 18px 0 14px;
  font-size: clamp(32px, 4vw, 48px);
  line-height: 1.02;
  color: #0f172a;
}

.auth-subtitle {
  max-width: 440px;
  font-size: 15px;
  line-height: 1.8;
  color: #475569;
}

.auth-card {
  padding: 12px;
}

.submit-button {
  width: 100%;
  margin-top: 10px;
}

.auth-footer {
  margin-top: 18px;
  display: flex;
  justify-content: space-between;
  font-size: 13px;
  color: #64748b;
}

@media (max-width: 900px) {
  .auth-shell {
    grid-template-columns: 1fr;
  }

  .auth-brand {
    padding: 28px;
  }
}
</style>
