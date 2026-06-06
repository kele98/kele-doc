<template>
  <div class="collectContainer">
    <div class="header">
      <div class="headerLeft">
        <el-input
          v-model="keyword"
          style="width: 300px; --el-border-radius-base: 16px"
          placeholder="搜索群组名称"
          clearable
          :prefix-icon="Search"
          @keyup.enter="loadGroups"
          @clear="loadGroups"
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
            <div class="title">群组管理</div>
            <el-button type="primary" size="small" @click="showCreateDialog">创建群组</el-button>
          </div>
        </div>
        <div class="right"></div>
      </div>
      <div class="contentBody">
        <el-table :data="groups" v-loading="loading" stripe height="100%" style="width: 100%">
          <el-table-column prop="name" label="群组名称" min-width="150" />
          <el-table-column prop="description" label="描述" min-width="200" />
          <el-table-column label="成员" width="80" align="center">
            <template #default="{ row }">
              <el-button size="small" link type="primary" @click="showMembers(row)">查看</el-button>
            </template>
          </el-table-column>
          <el-table-column label="状态" width="100" align="center">
            <template #default="{ row }">
              <el-tag :type="row.status === 1 ? 'success' : 'info'" size="small">
                {{ row.status === 1 ? '正常' : '已解散' }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column label="创建时间" width="170">
            <template #default="{ row }">{{ row.createdAt || '-' }}</template>
          </el-table-column>
          <el-table-column label="操作" width="220" align="right">
            <template #default="{ row }">
              <el-button size="small" link type="primary" @click="showMembers(row)">成员</el-button>
              <el-button size="small" link type="primary" @click="showEditDialog(row)">编辑</el-button>
              <el-button v-if="row.status === 1" size="small" link type="danger" @click="onDissolve(row)">解散</el-button>
              <el-button v-else size="small" link type="success" @click="onRestore(row)">恢复</el-button>
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
          @current-change="loadGroups"
          @size-change="loadGroups"
        />
      </div>
    </div>

    <!-- 创建/编辑群组弹窗 -->
    <el-dialog v-model="dialogVisible" :title="editingGroup ? '编辑群组' : '创建群组'" width="480">
      <el-form label-width="80px">
        <el-form-item label="群组名称">
          <el-input v-model="form.name" placeholder="输入群组名称" />
        </el-form-item>
        <el-form-item label="描述">
          <el-input v-model="form.description" type="textarea" :rows="3" placeholder="可选" />
        </el-form-item>
        <el-form-item v-if="!editingGroup" label="初始成员">
          <el-select
            v-model="form.memberIds"
            multiple
            filterable
            placeholder="搜索并选择成员"
            style="width: 100%"
          >
            <el-option
              v-for="u in allUsers"
              :key="u.id"
              :label="`${u.nickname || u.account} (${u.account})`"
              :value="u.id"
            />
          </el-select>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" @click="onSubmit">{{ editingGroup ? '保存' : '创建' }}</el-button>
      </template>
    </el-dialog>

    <!-- 成员管理弹窗 -->
    <el-dialog v-model="memberDialogVisible" :title="`成员管理 - ${memberGroupName}`" width="560">
      <div style="margin-bottom: 12px; display: flex; align-items: center;">
        <el-select
          v-model="addMemberId"
          filterable
          clearable
          placeholder="搜索并选择用户"
          style="flex: 1; margin-right: 8px"
          @change="onPickUser"
        >
          <el-option
            v-for="u in allUsers"
            :key="u.id"
            :label="`${u.nickname || u.account} (${u.account})`"
            :value="u.id"
          />
        </el-select>
        <el-button type="primary" size="default" :disabled="!addMemberId" @click="onAddMember">添加</el-button>
      </div>
      <el-table :data="members" v-loading="memberLoading" size="small" style="width: 100%">
        <el-table-column label="用户" min-width="200">
          <template #default="{ row }">
            {{ row.nickname || row.account || row.userId }}
          </template>
        </el-table-column>
        <el-table-column label="加入时间" width="170">
          <template #default="{ row }">{{ row.joinedAt || '-' }}</template>
        </el-table-column>
        <el-table-column label="操作" width="80" align="right">
          <template #default="{ row }">
            <el-button size="small" link type="danger" @click="onRemoveMember(row)">移除</el-button>
          </template>
        </el-table-column>
      </el-table>
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
const groups = ref([])
const loading = ref(false)
const page = ref(1)
const size = ref(20)
const total = ref(0)

// 用户列表（一次加载，本地过滤）
const allUsers = ref([])
let usersLoaded = false

const loadAllUsers = async () => {
  if (usersLoaded) return
  try {
    const { data } = await api.searchUsers({ keyword: '', limit: 50 })
    allUsers.value = data || []
    usersLoaded = true
  } catch (_) { allUsers.value = [] }
}

const loadGroups = async () => {
  loading.value = true
  try {
    const { data } = await api.listGroups({ page: page.value, size: size.value, keyword: keyword.value })
    groups.value = data.records || []
    total.value = data.total || 0
  } catch (e) {
    ElMessage.error('加载群组列表失败')
  } finally {
    loading.value = false
  }
}

// 创建/编辑
const dialogVisible = ref(false)
const editingGroup = ref(null)
const form = ref({ name: '', description: '', memberIds: [] })

const showCreateDialog = () => {
  editingGroup.value = null
  form.value = { name: '', description: '', memberIds: [] }
  dialogVisible.value = true
}

const showEditDialog = (row) => {
  editingGroup.value = row
  form.value = { name: row.name, description: row.description || '', memberIds: [] }
  dialogVisible.value = true
}

const onSubmit = async () => {
  if (!form.value.name) { ElMessage.warning('群组名称不能为空'); return }
  try {
    if (editingGroup.value) {
      await api.updateGroup(editingGroup.value.id, { name: form.value.name, description: form.value.description })
      ElMessage.success('已更新')
    } else {
      await api.createGroup({ name: form.value.name, description: form.value.description, memberIds: form.value.memberIds })
      ElMessage.success('已创建')
    }
    dialogVisible.value = false
    loadGroups()
  } catch (e) {
    ElMessage.error(e?.message || '操作失败')
  }
}

const onDissolve = async (row) => {
  try { await ElMessageBox.confirm(`确定解散群组「${row.name}」？`, '提示', { type: 'warning' }) } catch (_) { return }
  try {
    await api.dissolveGroup(row.id)
    ElMessage.success('已解散')
    loadGroups()
  } catch (e) { ElMessage.error(e?.message || '操作失败') }
}

const onRestore = async (row) => {
  try { await ElMessageBox.confirm(`确定恢复群组「${row.name}」？`, '提示', { type: 'info' }) } catch (_) { return }
  try {
    await api.restoreGroup(row.id)
    ElMessage.success('已恢复')
    loadGroups()
  } catch (e) { ElMessage.error(e?.message || '操作失败') }
}

// 成员管理
const memberDialogVisible = ref(false)
const memberGroupName = ref('')
const currentGroupId = ref(null)
const members = ref([])
const memberLoading = ref(false)
const addMemberId = ref(null)

const showMembers = async (row) => {
  currentGroupId.value = row.id
  memberGroupName.value = row.name
  addMemberId.value = null
  memberDialogVisible.value = true
  memberLoading.value = true
  try {
    const { data } = await api.listGroupMembers(row.id)
    members.value = data || []
  } catch (e) { ElMessage.error('加载成员失败') }
  finally { memberLoading.value = false }
}

const onPickUser = (id) => { addMemberId.value = id }

const onAddMember = async () => {
  if (!addMemberId.value) return
  try {
    await api.addGroupMembers(currentGroupId.value, [addMemberId.value])
    ElMessage.success('已添加')
    addMemberId.value = null
    const { data } = await api.listGroupMembers(currentGroupId.value)
    members.value = data || []
    loadGroups()
  } catch (e) { ElMessage.error(e?.message || '添加失败') }
}

const onRemoveMember = async (row) => {
  try { await ElMessageBox.confirm(`确定移除该成员？`, '提示', { type: 'warning' }) } catch (_) { return }
  try {
    await api.removeGroupMember(currentGroupId.value, row.userId)
    ElMessage.success('已移除')
    const { data } = await api.listGroupMembers(currentGroupId.value)
    members.value = data || []
    loadGroups()
  } catch (e) { ElMessage.error(e?.message || '移除失败') }
}

onMounted(() => {
  loadGroups()
  loadAllUsers()
})
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
