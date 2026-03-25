<template>
  <div class="manage-page">
    <div class="manage-header">
      <div>
        <span class="manage-kicker">Admin Console</span>
        <h2 class="manage-title">User management</h2>
      </div>
      <div class="manage-meta">{{ pageData.totalRow || 0 }} users</div>
    </div>

    <a-card class="query-card" :bordered="false">
      <a-form layout="inline" :model="searchParams" class="query-form">
        <a-form-item label="Account">
          <a-input v-model:value="searchParams.userAccount" allow-clear placeholder="Search account" />
        </a-form-item>
        <a-form-item label="Name">
          <a-input v-model:value="searchParams.userName" allow-clear placeholder="Search name" />
        </a-form-item>
        <a-form-item label="Role">
          <a-select v-model:value="searchParams.userRole" allow-clear placeholder="All roles" style="width: 140px">
            <a-select-option value="admin">Admin</a-select-option>
            <a-select-option value="user">User</a-select-option>
          </a-select>
        </a-form-item>
        <a-form-item>
          <a-space>
            <a-button type="primary" :loading="loading" @click="loadData">Search</a-button>
            <a-button @click="resetSearch">Reset</a-button>
          </a-space>
        </a-form-item>
      </a-form>
    </a-card>

    <a-card class="table-card" :bordered="false">
      <a-table
        :loading="loading"
        :columns="columns"
        :data-source="pageData.records"
        row-key="userId"
        :pagination="pagination"
        @change="handleTableChange"
      >
        <template #bodyCell="{ column, record }">
          <template v-if="column.dataIndex === 'userAvatar'">
            <a-avatar :src="record.userAvatar">
              <template #icon><UserOutlined /></template>
            </a-avatar>
          </template>

          <template v-else-if="column.dataIndex === 'userRole'">
            <a-tag :color="record.userRole === 'admin' ? 'blue' : 'default'">
              {{ record.userRole }}
            </a-tag>
          </template>

          <template v-else-if="column.dataIndex === 'action'">
            <a-button type="link" danger @click="confirmDelete(record)">Delete</a-button>
          </template>
        </template>
      </a-table>
    </a-card>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue';
import { message, Modal } from 'ant-design-vue';
import type { TablePaginationConfig } from 'ant-design-vue';
import { UserOutlined } from '@ant-design/icons-vue';
import { deleteUser, listUserByPageVo } from '@/api/usersController';

const loading = ref(false);
const searchParams = reactive<API.UserQueryRequest>({
  pageNum: 1,
  pageSize: 10,
  userAccount: '',
  userName: '',
  userRole: undefined,
});

const pageData = reactive<API.PageUserVO>({
  pageNumber: 1,
  pageSize: 10,
  totalPage: 0,
  totalRow: 0,
  records: [],
});

const columns = [
  { title: 'ID', dataIndex: 'userId', width: 180, ellipsis: true },
  { title: 'Account', dataIndex: 'userAccount', width: 160 },
  { title: 'Name', dataIndex: 'userName', width: 160 },
  { title: 'Avatar', dataIndex: 'userAvatar', width: 90 },
  { title: 'Profile', dataIndex: 'userProfile', ellipsis: true },
  { title: 'Role', dataIndex: 'userRole', width: 110 },
  { title: 'Created At', dataIndex: 'createdAt', width: 190 },
  { title: 'Action', dataIndex: 'action', width: 100, fixed: 'right' as const },
];

const pagination = computed<TablePaginationConfig>(() => ({
  current: pageData.pageNumber,
  pageSize: pageData.pageSize,
  total: pageData.totalRow,
  showSizeChanger: true,
  showTotal: (total) => `Total ${total} items`,
}));

const loadData = async () => {
  loading.value = true;
  try {
    const response = await listUserByPageVo(searchParams);
    Object.assign(pageData, response.data);
  } catch (error) {
    console.error(error);
    message.error('Failed to load users');
  } finally {
    loading.value = false;
  }
};

const resetSearch = async () => {
  searchParams.pageNum = 1;
  searchParams.pageSize = 10;
  searchParams.userAccount = '';
  searchParams.userName = '';
  searchParams.userRole = undefined;
  await loadData();
};

const handleTableChange = (pager: TablePaginationConfig) => {
  searchParams.pageNum = pager.current || 1;
  searchParams.pageSize = pager.pageSize || 10;
  void loadData();
};

const confirmDelete = (record: API.UserVO) => {
  Modal.confirm({
    title: `Delete user ${record.userAccount}?`,
    content: 'This action will remove the user from the active account list.',
    okText: 'Delete',
    cancelText: 'Cancel',
    okType: 'danger',
    onOk: async () => {
      try {
        await deleteUser({ userId: record.userId });
        message.success('User deleted');
        await loadData();
      } catch (error) {
        console.error(error);
        message.error('Delete failed');
      }
    },
  });
};

onMounted(() => {
  void loadData();
});
</script>

<style scoped>
.manage-page {
  min-height: 0;
  display: flex;
  flex-direction: column;
  gap: 16px;
  padding: 18px;
  background:
    linear-gradient(180deg, rgba(248, 250, 252, 0.96), rgba(255, 255, 255, 0.96)),
    radial-gradient(circle at top right, rgba(37, 99, 235, 0.08), transparent 26%);
}

.manage-header {
  display: flex;
  align-items: flex-end;
  justify-content: space-between;
  gap: 16px;
}

.manage-kicker {
  display: inline-block;
  font-size: 11px;
  text-transform: uppercase;
  letter-spacing: 0.18em;
  color: #2563eb;
}

.manage-title {
  margin: 10px 0 0;
  font-size: 30px;
  color: #0f172a;
}

.manage-meta {
  font-size: 13px;
  color: #64748b;
}

.query-card,
.table-card {
  border-radius: 20px;
  box-shadow: 0 12px 36px rgba(15, 23, 42, 0.05);
}

.query-form {
  display: flex;
  gap: 8px 0;
}

@media (max-width: 960px) {
  .manage-header {
    flex-direction: column;
    align-items: flex-start;
  }

  .query-form {
    display: grid;
    grid-template-columns: 1fr;
  }
}
</style>
