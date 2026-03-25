<template>
  <section class="auth-page">
    <div class="auth-shell">
      <div class="auth-brand">
        <span class="auth-kicker">Create Account</span>
        <h1 class="auth-title">Register a new workspace user</h1>
        <p class="auth-subtitle">
          New accounts immediately participate in the same session model used by chat, conversation history, and admin controls.
        </p>
      </div>

      <a-card class="auth-card" :bordered="false">
        <a-form layout="vertical" :model="formState" @finish="handleSubmit">
          <a-form-item label="User Account" name="userAccount" :rules="accountRules">
            <a-input v-model:value="formState.userAccount" size="large" placeholder="alice01" />
          </a-form-item>

          <a-form-item label="Display Name" name="userName">
            <a-input v-model:value="formState.userName" size="large" placeholder="Alice" />
          </a-form-item>

          <a-form-item label="Password" name="userPassword" :rules="passwordRules">
            <a-input-password v-model:value="formState.userPassword" size="large" />
          </a-form-item>

          <a-form-item
            label="Confirm Password"
            name="checkPassword"
            :rules="[
              { required: true, message: 'Please confirm password' },
              { validator: validateCheckPassword },
            ]"
          >
            <a-input-password v-model:value="formState.checkPassword" size="large" />
          </a-form-item>

          <a-button type="primary" size="large" class="submit-button" html-type="submit" :loading="submitting">
            Register
          </a-button>
        </a-form>

        <div class="auth-footer">
          <span>Already registered?</span>
          <router-link to="/user/login">Go to login</router-link>
        </div>
      </a-card>
    </div>
  </section>
</template>

<script setup lang="ts">
import { reactive, ref } from 'vue';
import { message } from 'ant-design-vue';
import { useRouter } from 'vue-router';
import { register } from '@/api/usersController';

const router = useRouter();
const submitting = ref(false);
const formState = reactive<API.UserRegisterRequest>({
  userAccount: '',
  userPassword: '',
  checkPassword: '',
  userName: '',
});

const accountRules = [
  { required: true, message: 'Please input user account' },
  { min: 4, message: 'User account must be at least 4 characters' },
];

const passwordRules = [
  { required: true, message: 'Please input password' },
  { min: 8, message: 'Password must be at least 8 characters' },
];

const validateCheckPassword = async () => {
  if (formState.checkPassword !== formState.userPassword) {
    throw new Error('The two passwords do not match');
  }
};

const handleSubmit = async () => {
  submitting.value = true;
  try {
    await register(formState);
    message.success('Register successful');
    await router.push('/user/login');
  } catch (error) {
    console.error(error);
    message.error('Register failed');
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
    radial-gradient(circle at top right, rgba(16, 185, 129, 0.1), transparent 28%),
    linear-gradient(135deg, #f7fafc, #eef4ff 58%, #ecfdf5);
}

.auth-shell {
  width: min(980px, 100%);
  display: grid;
  grid-template-columns: 1.1fr 0.9fr;
  gap: 24px;
}

.auth-brand,
.auth-card {
  border-radius: 28px;
  border: 1px solid rgba(148, 163, 184, 0.2);
  background: rgba(255, 255, 255, 0.9);
  backdrop-filter: blur(16px);
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
  color: #0f766e;
}

.auth-title {
  margin: 18px 0 14px;
  font-size: clamp(32px, 4vw, 46px);
  line-height: 1.04;
  color: #0f172a;
}

.auth-subtitle {
  max-width: 420px;
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
