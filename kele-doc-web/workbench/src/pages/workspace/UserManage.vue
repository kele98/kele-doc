<template>
  <div class="collectContainer">
    <div class="header">
      <div class="headerLeft">
        <el-input
          v-model="keyword"
          style="width: 300px; --el-border-radius-base: 16px"
          placeholder="搜索用户账号或昵称"
          clearable
          :prefix-icon="Search"
          @keyup.enter="loadUsers"
          @clear="loadUsers"
        />
      </div>
      <div class="headerRight">
        <Avatar />
      </div>
    </div>
    <div class="content">
      <div class="contentHeader">
        <div class="left">
          <div class="titleInfo">
            <div class="title">用户管理</div>
            <el-button type="primary" size="small" @click="showCreateDialog">新增用户</el-button>
          </div>
        </div>
        <div class="right"></div>
      </div>
      <div class="contentBody">
        <el-table :data="users" v-loading="loading" stripe style="width: 100%">
          <el-table-column prop="id" label="ID" width="80" />
          <el-table-column label="账号" min-width="150">
            <template #default="{ row }">{{ row.account }}</template>
          </el-table-column>
          <el-table-column label="昵称" min-width="150">
            <template #default="{ row }">{{ row.nickname || '-' }}</template>
          </el-table-column>
          <el-table-column label="角色" width="120" align="center">
            <template #default="{ row }">
              <el-tag :type="row.role === 'ADMIN' ? 'danger' : ''" size="small">
                {{ row.role === 'ADMIN' ? '管理员' : '用户' }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column label="状态" width="100" align="center">
            <template #default="{ row }">
              <el-tag :type="statusTagType(row.status)" size="small">
                {{ statusLabel(row.status) }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column label="创建时间" width="170">
            <template #default="{ row }">{{ row.createAt || '-' }}</template>
          </el-table-column>
          <el-table-column label="操作" width="200" align="right">
            <template #default="{ row }">
              <el-button
                v-if="row.account !== 'admin'"
                size="small" link type="primary"
                @click="onToggleRole(row)"
              >
                {{ row.role === 'ADMIN' ? '设为用户' : '设为管理员' }}
              </el-button>
              <el-button
                v-if="row.status === 0 && row.account !== 'admin'"
                size="small" link type="danger"
                @click="onToggleStatus(row, 1)"
              >禁用</el-button>
              <el-button
                v-else-if="row.status === 1"
                size="small" link type="success"
                @click="onToggleStatus(row, 0)"
              >启用</el-button>
            </template>
          </el-table-column>
        </el-table>
      </div>
      <div class="paginationBar">
        <el-pagination
          v-model:current-page="page"
          v-model:page-size="size"
          :total="total"
          :page-sizes="[10, 20, 50]"
          layout="total, sizes, prev, pager, next"
          @current-change="loadUsers"
          @size-change="loadUsers"
        />
      </div>
    </div>

    <!-- 新增用户弹窗 -->
    <el-dialog v-model="createDialogVisible" title="新增用户" width="420">
      <el-form label-width="80px">
        <el-form-item label="账号">
          <el-input v-model="createForm.account" placeholder="输入账号" />
        </el-form-item>
        <el-form-item label="密码">
          <el-input v-model="createForm.password" type="password" show-password placeholder="输入密码" />
        </el-form-item>
        <el-form-item label="昵称">
          <el-input v-model="createForm.userName" placeholder="可选" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="createDialogVisible = false">取消</el-button>
        <el-button type="primary" @click="onCreateUser">创建</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { ref, onMounted } from 'vue'
import { Search } from '@element-plus/icons-vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import Avatar from './components/common/Avatar.vue'
import api from '@/api'

const keyword = ref('')
const users = ref([])
const loading = ref(false)
const page = ref(1)
const size = ref(20)
const total = ref(0)

const statusLabel = (status) => {
  if (status === 0) return '正常'
  if (status === 1) return '已禁用'
  return '已注销'
}

const statusTagType = (status) => {
  if (status === 0) return 'success'
  if (status === 1) return 'danger'
  return 'info'
}

const loadUsers = async () => {
  loading.value = true
  try {
    const { data } = await api.adminUserList({
      keyword: keyword.value || '',
      page: page.value,
      size: size.value
    })
    users.value = data.records || []
    total.value = data.total || 0
  } catch (e) {
    ElMessage.error('加载用户列表失败')
  } finally {
    loading.value = false
  }
}

const onToggleStatus = async (row, status) => {
  const label = status === 0 ? '启用' : '禁用'
  try {
    await ElMessageBox.confirm(`确定${label}用户「${row.account}」？`, '提示', { type: 'warning' })
  } catch (_) { return }
  try {
    await api.adminUpdateUserStatus(row.id, status)
    ElMessage.success(`已${label}`)
    loadUsers()
  } catch (e) {
    ElMessage.error(e?.message || '操作失败')
  }
}

const onToggleRole = async (row) => {
  const newRole = row.role === 'ADMIN' ? 'USER' : 'ADMIN'
  const label = newRole === 'ADMIN' ? '设为管理员' : '设为普通用户'
  try {
    await ElMessageBox.confirm(`确定将「${row.account}」${label}？`, '提示', { type: 'warning' })
  } catch (_) { return }
  try {
    await api.adminUpdateUserRole(row.id, newRole)
    ElMessage.success('已更新')
    loadUsers()
  } catch (e) {
    ElMessage.error(e?.message || '操作失败')
  }
}

// 新增用户
const createDialogVisible = ref(false)
const createForm = ref({ account: '', password: '', userName: '' })

const showCreateDialog = () => {
  createForm.value = { account: '', password: '', userName: '' }
  createDialogVisible.value = true
}

const onCreateUser = async () => {
  if (!createForm.value.account) { ElMessage.warning('账号不能为空'); return }
  if (!createForm.value.password) { ElMessage.warning('密码不能为空'); return }
  try {
    await api.register({
      account: createForm.value.account,
      password: createForm.value.password,
      userName: createForm.value.userName
    })
    ElMessage.success('创建成功')
    createDialogVisible.value = false
    loadUsers()
  } catch (e) {
    ElMessage.error(e?.message || '创建失败')
  }
}

onMounted(() => loadUsers())
</script>

<style lang="less" scoped>
.collectContainer {
  width: 100%;
  height: 100%;
  overflow: hidden;
  display: flex;
  flex-direction: column;

  .header {
    height: 100px;
    display: flex;
    align-items: center;
    justify-content: space-between;
    flex-shrink: 0;
    padding: 0 24px;

    .headerRight {
      display: flex;
      align-items: center;
    }
  }

  .content {
    width: 100%;
    height: 100%;
    overflow: hidden;
    display: flex;
    flex-direction: column;
    padding-bottom: 20px;

    .contentHeader {
      display: flex;
      align-items: center;
      justify-content: space-between;
      flex-shrink: 0;
      margin-bottom: 8px;
      padding: 0 24px;

      .left {
      }

      .right {
        display: flex;
        align-items: center;
      }
    }

    .contentBody {
      width: 100%;
      height: 100%;
      overflow-y: auto;
      padding: 0 14px;
    }

    .paginationBar {
      flex-shrink: 0;
      display: flex;
      justify-content: flex-end;
      padding: 12px 14px 0;
    }
  }
}

.titleInfo {
  display: flex;
  align-items: center;
  gap: 12px;
}

.titleInfo .title {
  font-size: 16px;
  font-weight: 600;
  color: #212930;
}
</style>
