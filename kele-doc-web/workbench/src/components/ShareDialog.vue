<template>
  <el-dialog
    :model-value="modelValue"
    width="640"
    title="分享文件夹"
    :close-on-click-modal="false"
    @update:model-value="onDialogUpdate"
  >
    <!-- 加载中 -->
    <div v-if="loading" v-loading="true" class="loadingBox"></div>

    <template v-else>
      <!-- 头部：文件夹名 + 所有者 -->
      <div class="headBox">
        <div class="folderName">{{ folderName || '（根目录）' }}</div>
        <div class="ownerRow">
          <span class="label">所有者：</span>
          <span class="value">{{ aclData.ownerName || aclData.ownerId }}</span>
          <el-tag v-if="aclData.ownerId === currentUserId" type="success" size="small">我</el-tag>
        </div>
        <div class="myPermRow">
          <span class="label">我的权限：</span>
          <el-tag :type="myPermTagType" size="small">{{ myPermLabel }}</el-tag>
        </div>
      </div>

      <el-tabs v-model="activeTab" class="tabsBox">
        <!-- 标签 1：添加成员 -->
        <el-tab-pane label="添加成员" name="add">
          <div class="addBox">
            <div class="row">
              <span class="label">用户：</span>
              <el-select
                v-model="pickedUserId"
                filterable
                clearable
                placeholder="搜索并选择用户"
                style="flex: 1"
                value-key="id"
                @change="onUserPick"
              >
                <el-option
                  v-for="u in allUsers"
                  :key="u.id"
                  :label="`${u.nickname || u.account} (${u.account})`"
                  :value="u.id"
                />
              </el-select>
              <el-select v-model="newEntry.permission" style="width: 110px; margin-left: 8px">
                <el-option label="只读" value="READ" />
                <el-option label="可写" value="WRITE" />
                <el-option label="管理" value="MANAGE" />
              </el-select>
              <el-button
                type="primary"
                :disabled="!newEntry.principalId || !newEntry.principalType"
                style="margin-left: 8px"
                @click="onAdd"
              >添加</el-button>
            </div>

            <div class="row" style="margin-top: 12px">
              <span class="label">用户组：</span>
              <el-select
                v-model="groupPickId"
                filterable
                clearable
                placeholder="选择组（可选）"
                style="flex: 1"
                @change="onGroupPick"
              >
                <el-option
                  v-for="g in (store.myGroups || [])"
                  :key="g.id"
                  :label="g.name"
                  :value="g.id"
                />
              </el-select>
            </div>
          </div>
        </el-tab-pane>

        <!-- 标签 2：管理现有 -->
        <el-tab-pane :label="`管理现有（${aclData.entries ? aclData.entries.length : 0}）`" name="manage">
          <el-table :data="aclData.entries || []" size="small" style="width: 100%">
            <el-table-column label="主体" min-width="220">
              <template #default="{ row }">
                <span v-if="row.principalType === 'USER'">
                  {{ row.principalName || row.principalId }}
                  <el-tag v-if="row.principalId === currentUserId" size="small" type="success" style="margin-left: 4px">我</el-tag>
                </span>
                <span v-else-if="row.principalType === 'GROUP'">
                  <el-tag size="small">组</el-tag>
                  {{ row.principalName || row.principalId }}
                </span>
              </template>
            </el-table-column>
            <el-table-column label="权限" width="160">
              <template #default="{ row }">
                <el-select
                  :model-value="row.permission"
                  size="small"
                  :disabled="!canManage"
                  @change="(v) => onUpdatePerm(row, v)"
                >
                  <el-option label="只读" value="READ" />
                  <el-option label="可写" value="WRITE" />
                  <el-option label="管理" value="MANAGE" />
                </el-select>
              </template>
            </el-table-column>
            <el-table-column label="操作" width="100" align="right">
              <template #default="{ row }">
                <el-button
                  size="small"
                  type="danger"
                  link
                  :disabled="!canManage"
                  @click="onRevoke(row)"
                >移除</el-button>
              </template>
            </el-table-column>
          </el-table>
          <div v-if="!aclData.entries || aclData.entries.length === 0" class="emptyTip">
            还没有分享给任何人
          </div>
        </el-tab-pane>

        <!-- 标签 3：转让所有权（仅 owner 可见） -->
        <el-tab-pane v-if="isOwner" label="转让所有权" name="owner">
          <div class="ownerBox">
            <el-alert
              type="warning"
              :closable="false"
              show-icon
              title="转让后您将失去此文件夹的管理权，且不能撤销。"
            />
            <div class="row" style="margin-top: 12px">
              <span class="label">新所有者：</span>
              <el-select
                v-model="transferTargetId"
                filterable
                clearable
                placeholder="搜索并选择用户"
                style="flex: 1"
              >
                <el-option
                  v-for="u in allUsers"
                  :key="u.id"
                  :label="`${u.nickname || u.account} (${u.account})`"
                  :value="u.id"
                />
              </el-select>
            </div>
            <el-button
              type="danger"
              :disabled="!transferTargetId || transferTargetId === aclData.ownerId"
              style="margin-top: 12px"
              @click="onTransfer"
            >确认转让</el-button>
          </div>
        </el-tab-pane>
      </el-tabs>
    </template>
  </el-dialog>
</template>

<script setup>
import { computed, nextTick, reactive, ref, watch } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import api from '@/api'
import { useStore } from '../store'

const props = defineProps({
  modelValue: { type: Boolean, default: false },
  folderId: { type: Number, default: null },
  folderName: { type: String, default: '' }
})
const emit = defineEmits(['update:modelValue', 'changed'])

const store = useStore()

const loading = ref(false)
const aclData = reactive({ ownerId: null, ownerName: '', entries: [] })
const activeTab = ref('add')

// 添加成员
const newEntry = reactive({ principalType: null, principalId: null, permission: 'READ' })
const groupPickId = ref(null)
const pickedUserId = ref(null)

// 用户列表（打开时一次性加载，本地过滤）
const allUsers = ref([])
let usersLoaded = false

const transferTargetId = ref(null)

const currentUserId = computed(() => store.userInfo && store.userInfo.id)
const isOwner = computed(() => aclData.ownerId === currentUserId.value)
const canManage = computed(() => isOwner.value
  || (store.folderPermissionMap[props.folderId] || {}).level === 'MANAGE')

const permRank = { READ: 1, WRITE: 2, MANAGE: 3, NONE: 0 }
const myPermLabel = computed(() => {
  if (isOwner.value) return '所有者（管理）'
  const p = (store.folderPermissionMap[props.folderId] || {}).level || 'NONE'
  return { NONE: '无', READ: '只读', WRITE: '可写', MANAGE: '管理' }[p] || '无'
})
const myPermTagType = computed(() => {
  if (isOwner.value) return 'success'
  const p = (store.folderPermissionMap[props.folderId] || {}).level || 'NONE'
  return { NONE: 'info', READ: '', WRITE: 'warning', MANAGE: 'success' }[p] || 'info'
})

watch([() => props.modelValue, () => props.folderId], ([visible, fid]) => {
  if (visible && fid) {
    ensureMyGroups()
    loadAllUsers()
    nextTick(() => onOpen())
  }
}, { immediate: true })

function onDialogUpdate(v) {
  emit('update:modelValue', v)
}

// 一次性加载用户列表
async function loadAllUsers() {
  if (usersLoaded) return
  try {
    const { data } = await api.searchUsers({ keyword: '', limit: 50 })
    allUsers.value = data || []
    usersLoaded = true
  } catch (_) { allUsers.value = [] }
}

let reqId = 0

async function onOpen() {
  if (!props.folderId) return
  const me = ++reqId
  loading.value = true
  activeTab.value = 'add'
  newEntry.principalType = null
  newEntry.principalId = null
  newEntry.permission = 'READ'
  groupPickId.value = null
  pickedUserId.value = null
  transferTargetId.value = null
  await refreshAcl(me)
}

async function refreshAcl(me) {
  if (!props.folderId) return
  if (me === undefined) me = ++reqId
  try {
    const { data } = await api.getFolderAcl(props.folderId)
    if (me !== reqId) return
    aclData.ownerId = data.ownerId
    aclData.ownerName = data.ownerName
    aclData.entries = (data.entries || []).filter(e => !e.revoked)
    const meUser = currentUserId.value
    let level = 'NONE'
    let source = 'NONE'
    const myGroupIds = (store.myGroups || []).map(g => g.id)
    if (data.isOrgPublic === true) {
      const orgPerm = data.orgPublicPermission || 'READ'
      if ((permRank[orgPerm] || 0) > (permRank[level] || 0)) {
        level = orgPerm
        source = 'ORG'
      }
    }
    for (const e of aclData.entries) {
      const match = (e.principalType === 'USER' && e.principalId === meUser)
        || (e.principalType === 'GROUP' && myGroupIds.includes(e.principalId))
      if (match && (permRank[e.permission] || 0) > (permRank[level] || 0)) {
        level = e.permission
        source = e.principalType === 'USER' ? 'DIRECT' : 'GROUP'
      }
    }
    store.folderPermissionMap[props.folderId] = { level, source, isOwner: isOwner.value }
  } catch (e) {
    if (me !== reqId) return
    ElMessage.error('加载权限列表失败')
  } finally {
    if (me === reqId) {
      loading.value = false
    }
  }
}

async function ensureMyGroups() {
  if (!store.myGroups) {
    try { await store.loadMyGroups() } catch (_) { /* 静默 */ }
  }
}

function onUserPick(id) {
  if (!id) {
    newEntry.principalType = null
    newEntry.principalId = null
    return
  }
  newEntry.principalType = 'USER'
  newEntry.principalId = id
}

function onGroupPick(id) {
  if (!id) {
    newEntry.principalType = null
    newEntry.principalId = null
    return
  }
  newEntry.principalType = 'GROUP'
  newEntry.principalId = id
}

async function onAdd() {
  if (!newEntry.principalId || !newEntry.principalType) return
  try {
    await api.grantFolderAcl(props.folderId, [{
      principalType: newEntry.principalType,
      principalId: newEntry.principalId,
      permission: newEntry.permission
    }], false)
    ElMessage.success('已添加')
    newEntry.principalType = null
    newEntry.principalId = null
    newEntry.permission = 'READ'
    groupPickId.value = null
    pickedUserId.value = null
    await refreshAcl()
    emit('changed')
  } catch (e) {
    ElMessage.error(e && e.message ? e.message : '添加失败')
  }
}

async function onUpdatePerm(row, newPerm) {
  try {
    await api.updateFolderAcl(props.folderId, row.id, newPerm)
    ElMessage.success('已更新')
    await refreshAcl()
    emit('changed')
  } catch (e) {
    ElMessage.error(e && e.message ? e.message : '更新失败')
  }
}

async function onRevoke(row) {
  try {
    await ElMessageBox.confirm(`确定移除「${row.principalName || row.principalId}」的访问权限？`, '提示', {
      type: 'warning'
    })
  } catch (_) { return }
  try {
    await api.revokeFolderAcl(props.folderId, row.id)
    ElMessage.success('已移除')
    await refreshAcl()
    emit('changed')
  } catch (e) {
    ElMessage.error(e && e.message ? e.message : '移除失败')
  }
}

async function onTransfer() {
  if (!transferTargetId.value || transferTargetId.value === aclData.ownerId) return
  try {
    await ElMessageBox.confirm('转让后您将不再是所有者，且不能撤销。是否继续？', '警告', {
      type: 'error',
      confirmButtonText: '确认转让'
    })
  } catch (_) { return }
  try {
    await api.transferOwner(props.folderId, transferTargetId.value)
    ElMessage.success('已转让')
    transferTargetId.value = null
    await refreshAcl()
    emit('changed')
  } catch (e) {
    ElMessage.error(e && e.message ? e.message : '转让失败')
  }
}
</script>

<style scoped>
.headBox {
  border-bottom: 1px solid #ebeef5;
  padding-bottom: 12px;
  margin-bottom: 12px;
}
.folderName {
  font-size: 16px;
  font-weight: 600;
  margin-bottom: 6px;
}
.ownerRow, .myPermRow {
  font-size: 13px;
  color: #606266;
  margin-top: 4px;
}
.ownerRow .label, .myPermRow .label {
  color: #909399;
  margin-right: 4px;
}
.tabsBox {
  margin-top: 4px;
}
.addBox .row, .ownerBox .row {
  display: flex;
  align-items: center;
}
.addBox .label, .ownerBox .label {
  width: 60px;
  color: #606266;
  font-size: 13px;
}
.emptyTip {
  text-align: center;
  color: #909399;
  font-size: 13px;
  padding: 20px 0;
}
.loadingBox {
  height: 240px;
}
</style>
