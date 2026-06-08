<template>
  <el-dialog
    :model-value="modelValue"
    width="600"
    title="分享文件夹"
    :close-on-click-modal="false"
    @update:model-value="onDialogUpdate"
  >
    <div v-if="loading" v-loading="true" class="loadingBox"></div>

    <template v-else>
      <!-- 文件夹信息卡片 -->
      <div class="folderCard">
        <div class="folderIcon">
          <svg width="22" height="22" viewBox="0 0 24 24" fill="#ffb133">
            <path d="M10 4H4c-1.1 0-2 .9-2 2v12c0 1.1.9 2 2 2h16c1.1 0 2-.9 2-2V8c0-1.1-.9-2-2-2h-8l-2-2z"/>
          </svg>
        </div>
        <div class="folderMeta">
          <div class="folderName">{{ folderName || '（根目录）' }}</div>
          <div class="folderSub">
            <span class="ownerDot"></span>
            <span>所有者：{{ aclData.ownerName || aclData.ownerId }}</span>
            <span v-if="isOwner" class="meBadge">我</span>
          </div>
        </div>
        <div class="myPermBadge" :class="myPermClass">
          <span class="dot"></span>
          {{ myPermLabel }}
        </div>
      </div>

      <!-- 添加成员 -->
      <div class="sectionTitle">
        <span>添加成员</span>
        <span v-if="!canManage" class="sectionHint">仅所有者和管理者可添加</span>
      </div>
      <div class="addBox" :class="{ disabled: !canManage }">
        <div class="addRow">
          <!-- 统一搜索 -->
          <div class="searchBox" ref="searchBoxRef">
            <span class="searchIcon">🔍</span>
            <input
              v-model="searchKeyword"
              class="searchInput"
              placeholder="搜索用户或群组..."
              :disabled="!canManage"
              @focus="searchDropdownOpen = true"
              @input="onSearchInput"
            />
            <span class="searchArrow">{{ searchDropdownOpen ? '▴' : '▾' }}</span>

            <div v-if="searchDropdownOpen && canManage" class="searchDropdown">
              <template v-if="searchKeyword.trim()">
                <template v-if="filteredUsers.length || filteredGroups.length">
                  <div v-if="filteredUsers.length" class="dropdownGroupTitle">用户</div>
                  <div
                    v-for="u in filteredUsers"
                    :key="'u-' + u.id"
                    class="dropdownItem"
                    @click="pickSearchItem('USER', u)"
                  >
                    <div class="dropdownAvatar user">{{ avatarChar(u.nickname || u.account) }}</div>
                    <div class="dropdownInfo">
                      <div class="dropdownName" v-html="highlight(u.nickname || u.account, searchKeyword)"></div>
                      <div class="dropdownAccount" v-html="highlight(u.account, searchKeyword)"></div>
                    </div>
                  </div>
                  <div v-if="filteredGroups.length" class="dropdownGroupTitle">群组</div>
                  <div
                    v-for="g in filteredGroups"
                    :key="'g-' + g.id"
                    class="dropdownItem"
                    @click="pickSearchItem('GROUP', g)"
                  >
                    <div class="dropdownAvatar group">👥</div>
                    <div class="dropdownInfo">
                      <div class="dropdownName" v-html="highlight(g.name, searchKeyword)"></div>
                      <div class="dropdownAccount">群组</div>
                    </div>
                  </div>
                </template>
                <div v-else class="dropdownEmpty">未找到匹配的用户或群组</div>
              </template>
              <template v-else>
                <div v-if="allUsers.length" class="dropdownGroupTitle">用户</div>
                <div
                  v-for="u in allUsers.slice(0, 5)"
                  :key="'u-' + u.id"
                  class="dropdownItem"
                  @click="pickSearchItem('USER', u)"
                >
                  <div class="dropdownAvatar user">{{ avatarChar(u.nickname || u.account) }}</div>
                  <div class="dropdownInfo">
                    <div class="dropdownName">{{ u.nickname || u.account }}</div>
                    <div class="dropdownAccount">{{ u.account }}</div>
                  </div>
                </div>
                <div v-if="(store.myGroups || []).length" class="dropdownGroupTitle">群组</div>
                <div
                  v-for="g in (store.myGroups || []).slice(0, 5)"
                  :key="'g-' + g.id"
                  class="dropdownItem"
                  @click="pickSearchItem('GROUP', g)"
                >
                  <div class="dropdownAvatar group">👥</div>
                  <div class="dropdownInfo">
                    <div class="dropdownName">{{ g.name }}</div>
                    <div class="dropdownAccount">群组</div>
                  </div>
                </div>
                <div
                  v-if="!allUsers.length && !(store.myGroups || []).length"
                  class="dropdownEmpty"
                >暂无可选用户或群组</div>
              </template>
            </div>
          </div>

          <!-- 权限选择器 -->
          <div class="permSelect" ref="permSelectRef">
            <div
              class="permSelectTrigger"
              :class="[permClass(newPermission), { clickable: canManage }]"
              @click="onPermTriggerClick"
            >
              <span class="dot"></span>
              <span class="text">{{ permLabel(newPermission) }}</span>
              <span class="arrow">▾</span>
            </div>
            <div v-if="permDropdownOpen && canManage" class="permSelectDropdown">
              <div
                v-for="p in permOptions"
                :key="p.value"
                class="permOption"
                @click="pickPermission(p.value)"
              >
                <div class="permOptionIcon" :class="p.iconClass">{{ p.icon }}</div>
                <div class="permOptionBody">
                  <div class="permOptionName">
                    {{ p.label }}
                    <span v-if="p.value === newPermission" class="check">✓</span>
                  </div>
                  <div class="permOptionDesc">{{ p.desc }}</div>
                </div>
              </div>
            </div>
          </div>

          <el-button
            type="primary"
            :disabled="!canManage || !pickedItem"
            class="addBtn"
            @click="onAdd"
          >添加</el-button>
        </div>
      </div>

      <!-- 已分享成员 -->
      <div class="sectionTitle">
        <span>已分享成员</span>
        <span class="sectionCount">{{ displayEntries.length }} 人</span>
      </div>
      <div v-if="displayEntries.length === 0" class="emptyState">
        <div class="emptyIcon">👥</div>
        <div class="emptyTitle">还没有分享给任何人</div>
        <div class="emptyText">添加第一个成员，开始协作</div>
      </div>
      <div v-else class="memberList">
        <div v-for="row in displayEntries" :key="row.uid" class="memberItem">
          <div
            class="memberAvatar"
            :class="row.principalType === 'GROUP' ? 'group' : 'user'"
          >
            <template v-if="row.principalType === 'GROUP'">👥</template>
            <template v-else>{{ avatarChar(row.principalName) }}</template>
          </div>
          <div class="memberInfo">
            <div class="memberName">
              {{ row.principalName || row.principalId }}
              <span v-if="row.isOwner" class="memberTag ownerTag">所有者</span>
              <span v-else-if="row.principalType === 'GROUP'" class="memberTag">群组</span>
              <span v-else class="memberTag">用户</span>
              <span v-if="row.principalId === currentUserId && !row.isOwner" class="memberTag meTag">我</span>
            </div>
            <div class="memberAccount">
              <template v-if="row.principalType === 'GROUP'">群组</template>
              <template v-else>{{ row.principalAccount || row.principalName || row.principalId }}</template>
            </div>
          </div>
          <div class="memberActions">
            <!-- 所有者：不可改权限 -->
            <div v-if="row.isOwner" class="memberPerm" :class="permClass(row.permission)">
              {{ permIcon(row.permission) }} {{ permLabel(row.permission) }}
            </div>
            <!-- 普通成员 + 当前用户有管理权：可点击切换 -->
            <el-popover
              v-else-if="canManage"
              placement="bottom-end"
              :width="260"
              trigger="click"
              popper-class="shareDialogMemberPermPopover"
            >
              <template #reference>
                <div class="memberPerm clickable" :class="permClass(row.permission)">
                  {{ permIcon(row.permission) }} {{ permLabel(row.permission) }}
                </div>
              </template>
              <div class="permOptionPopover">
                <div
                  v-for="p in permOptions"
                  :key="p.value"
                  class="permOption"
                  @click="onUpdatePerm(row, p.value)"
                >
                  <div class="permOptionIcon" :class="p.iconClass">{{ p.icon }}</div>
                  <div class="permOptionBody">
                    <div class="permOptionName">
                      {{ p.label }}
                      <span v-if="p.value === row.permission" class="check">✓</span>
                    </div>
                    <div class="permOptionDesc">{{ p.desc }}</div>
                  </div>
                </div>
              </div>
            </el-popover>
            <!-- 普通成员 + 当前用户无管理权：只读显示 -->
            <div v-else class="memberPerm" :class="permClass(row.permission)">
              {{ permIcon(row.permission) }} {{ permLabel(row.permission) }}
            </div>

            <span
              v-if="canManage && !row.isOwner"
              class="memberRemove"
              @click="onRevoke(row)"
            >移除</span>
          </div>
        </div>
      </div>

      <!-- 转让所有权（折叠，仅 owner 可见） -->
      <div v-if="isOwner" class="transferZone">
        <div
          class="transferTrigger"
          :class="{ open: transferOpen }"
          @click="transferOpen = !transferOpen"
        >
          <span :class="{ danger: transferOpen }">⚠ 转让所有权给其他用户</span>
          <span class="arrow">▸</span>
        </div>
        <div v-if="transferOpen" class="transferPanel">
          <div class="transferWarn">
            <span class="warnIcon">⚠</span>
            <div>转让后您将失去此文件夹的管理权，且不能撤销。新所有者将获得完全控制权限。</div>
          </div>
          <div class="transferRow">
            <el-select
              v-model="transferTargetId"
              filterable
              clearable
              placeholder="搜索并选择新所有者..."
              style="flex: 1"
            >
              <el-option
                v-for="u in allUsers"
                :key="u.id"
                :label="`${u.nickname || u.account} (${u.account})`"
                :value="u.id"
                :disabled="u.id === aclData.ownerId"
              />
            </el-select>
            <el-button
              type="danger"
              :disabled="!transferTargetId || transferTargetId === aclData.ownerId"
              @click="onTransfer"
            >确认转让</el-button>
          </div>
        </div>
      </div>
    </template>
  </el-dialog>
</template>

<script setup>
import { computed, nextTick, onBeforeUnmount, onMounted, reactive, ref, watch } from 'vue'
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

const searchBoxRef = ref(null)
const permSelectRef = ref(null)

const searchKeyword = ref('')
const searchDropdownOpen = ref(false)
const pickedItem = ref(null)
const newPermission = ref('WRITE')
const permDropdownOpen = ref(false)

const transferOpen = ref(false)
const transferTargetId = ref(null)

const allUsers = ref([])
let usersLoaded = false

const currentUserId = computed(() => store.userInfo && store.userInfo.id)
const isOwner = computed(() => aclData.ownerId === currentUserId.value)
const canManage = computed(
  () =>
    isOwner.value ||
    (store.folderPermissionMap[props.folderId] || {}).level === 'MANAGE'
)

const permRank = { READ: 1, WRITE: 2, MANAGE: 3, NONE: 0 }

const permOptions = [
  { value: 'READ', label: '只读', icon: '👁', iconClass: 'read', desc: '仅可查看文件夹内容，不能修改' },
  { value: 'WRITE', label: '可写', icon: '✏', iconClass: 'write', desc: '可上传、编辑、删除文件和子文件夹' },
  { value: 'MANAGE', label: '管理', icon: '⚙', iconClass: 'manage', desc: '可管理成员和权限分配' }
]

function permLabel(p) {
  return { READ: '只读', WRITE: '可写', MANAGE: '管理', NONE: '无' }[p] || ''
}
function permClass(p) {
  return ({ READ: 'read', WRITE: 'write', MANAGE: 'manage' })[p] || 'read'
}
function permIcon(p) {
  return { READ: '👁', WRITE: '✏', MANAGE: '⚙' }[p] || ''
}

const myPermLabel = computed(() => {
  if (isOwner.value) return '所有者'
  const p = (store.folderPermissionMap[props.folderId] || {}).level || 'NONE'
  return { NONE: '无权限', READ: '只读', WRITE: '可写', MANAGE: '管理' }[p] || '无权限'
})
const myPermClass = computed(() => {
  if (isOwner.value) return 'owner'
  const p = (store.folderPermissionMap[props.folderId] || {}).level || 'NONE'
  return permClass(p)
})

const displayEntries = computed(() => {
  const meId = currentUserId.value
  const showOwnerVirtual = !!aclData.ownerId
  const ownerEntry = showOwnerVirtual
    ? [{
        uid: '__owner__',
        id: '__owner__',
        isOwner: true,
        principalType: 'USER',
        principalId: aclData.ownerId,
        principalName: aclData.ownerName,
        principalAccount: '',
        permission: 'MANAGE'
      }]
    : []
  const normal = (aclData.entries || [])
    .filter(e => {
      if (!isOwner.value) return true
      return !(e.principalType === 'USER' && e.principalId === meId)
    })
    .map(e => ({ ...e, uid: `${e.principalType}-${e.principalId}`, isOwner: false }))
  return [...ownerEntry, ...normal]
})

const filteredUsers = computed(() => {
  const kw = (searchKeyword.value || '').trim().toLowerCase()
  if (!kw) return []
  return allUsers.value
    .filter(u => {
      const name = (u.nickname || u.account || '').toLowerCase()
      const acc = (u.account || '').toLowerCase()
      return name.includes(kw) || acc.includes(kw)
    })
    .slice(0, 20)
})
const filteredGroups = computed(() => {
  const kw = (searchKeyword.value || '').trim().toLowerCase()
  if (!kw) return []
  return (store.myGroups || [])
    .filter(g => (g.name || '').toLowerCase().includes(kw))
    .slice(0, 20)
})

watch(
  [() => props.modelValue, () => props.folderId],
  ([visible, fid]) => {
    if (visible && fid) {
      ensureMyGroups()
      loadAllUsers()
      nextTick(() => onOpen())
    }
  },
  { immediate: true }
)

function onDialogUpdate(v) {
  emit('update:modelValue', v)
}

async function loadAllUsers() {
  if (usersLoaded) return
  try {
    const { data } = await api.searchUsers({ keyword: '', limit: 200 })
    allUsers.value = data || []
    usersLoaded = true
  } catch (_) {
    allUsers.value = []
  }
}

let reqId = 0

async function onOpen() {
  if (!props.folderId) return
  const me = ++reqId
  loading.value = true
  searchKeyword.value = ''
  searchDropdownOpen.value = false
  pickedItem.value = null
  newPermission.value = 'WRITE'
  permDropdownOpen.value = false
  transferOpen.value = false
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
      const match =
        (e.principalType === 'USER' && e.principalId === meUser) ||
        (e.principalType === 'GROUP' && myGroupIds.includes(e.principalId))
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
    try {
      await store.loadMyGroups()
    } catch (_) {
      /* 静默 */
    }
  }
}

function onSearchInput() {
  pickedItem.value = null
  searchDropdownOpen.value = true
}

function pickSearchItem(type, item) {
  if (type === 'USER') {
    pickedItem.value = {
      type: 'USER',
      id: item.id,
      name: item.nickname || item.account,
      account: item.account
    }
    searchKeyword.value = item.nickname || item.account
  } else {
    pickedItem.value = {
      type: 'GROUP',
      id: item.id,
      name: item.name
    }
    searchKeyword.value = item.name
  }
  searchDropdownOpen.value = false
}

function onPermTriggerClick() {
  if (!canManage.value) return
  permDropdownOpen.value = !permDropdownOpen.value
}

function pickPermission(p) {
  newPermission.value = p
  permDropdownOpen.value = false
}

async function onAdd() {
  if (!pickedItem.value || !canManage.value) return
  try {
    await api.grantFolderAcl(
      props.folderId,
      [
        {
          principalType: pickedItem.value.type,
          principalId: pickedItem.value.id,
          permission: newPermission.value
        }
      ],
      false
    )
    ElMessage.success('已添加')
    pickedItem.value = null
    searchKeyword.value = ''
    newPermission.value = 'WRITE'
    await refreshAcl()
    emit('changed')
  } catch (e) {
    ElMessage.error((e && e.message) || '添加失败')
  }
}

async function onUpdatePerm(row, newPerm) {
  if (row.isOwner) return
  if (newPerm === row.permission) return
  try {
    await api.updateFolderAcl(props.folderId, row.id, newPerm)
    ElMessage.success('已更新')
    await refreshAcl()
    emit('changed')
  } catch (e) {
    ElMessage.error((e && e.message) || '更新失败')
  }
}

async function onRevoke(row) {
  if (row.isOwner) return
  try {
    await ElMessageBox.confirm(
      `确定移除「${row.principalName || row.principalId}」的访问权限？`,
      '提示',
      { type: 'warning' }
    )
  } catch (_) {
    return
  }
  try {
    await api.revokeFolderAcl(props.folderId, row.id)
    ElMessage.success('已移除')
    await refreshAcl()
    emit('changed')
  } catch (e) {
    ElMessage.error((e && e.message) || '移除失败')
  }
}

async function onTransfer() {
  if (!transferTargetId.value || transferTargetId.value === aclData.ownerId) return
  try {
    await ElMessageBox.confirm(
      '转让后您将不再是所有者，且不能撤销。是否继续？',
      '警告',
      { type: 'error', confirmButtonText: '确认转让' }
    )
  } catch (_) {
    return
  }
  try {
    await api.transferOwner(props.folderId, transferTargetId.value)
    ElMessage.success('已转让')
    transferTargetId.value = null
    transferOpen.value = false
    await refreshAcl()
    emit('changed')
  } catch (e) {
    ElMessage.error((e && e.message) || '转让失败')
  }
}

function onClickOutside(e) {
  if (searchBoxRef.value && !searchBoxRef.value.contains(e.target)) {
    searchDropdownOpen.value = false
  }
  if (permSelectRef.value && !permSelectRef.value.contains(e.target)) {
    permDropdownOpen.value = false
  }
}

function avatarChar(s) {
  if (!s) return '?'
  return String(s).slice(0, 1).toUpperCase()
}

function escapeHtml(s) {
  return String(s).replace(
    /[&<>"']/g,
    c => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c])
  )
}

function highlight(text, kw) {
  if (!text) return ''
  const esc = escapeHtml(text)
  const trimKw = (kw || '').trim()
  if (!trimKw) return esc
  const escKw = escapeHtml(trimKw)
  try {
    return esc.replace(
      new RegExp(escKw.replace(/[.*+?^${}()|[\]\\]/g, '\\$&'), 'gi'),
      m => `<span style="color:#1ea59a;font-weight:600">${m}</span>`
    )
  } catch (_) {
    return esc
  }
}

onMounted(() => {
  document.addEventListener('click', onClickOutside, true)
})
onBeforeUnmount(() => {
  document.removeEventListener('click', onClickOutside, true)
})
</script>

<style scoped>
.loadingBox {
  height: 320px;
}

/* 文件夹信息卡片 */
.folderCard {
  background: linear-gradient(135deg, #e8f5f3 0%, #f5fbfa 100%);
  border: 1px solid #a5dbd7;
  border-radius: 8px;
  padding: 14px 16px;
  display: flex;
  align-items: center;
  gap: 12px;
  margin-bottom: 20px;
}
.folderIcon {
  width: 40px;
  height: 40px;
  background: #fff;
  border-radius: 8px;
  display: flex;
  align-items: center;
  justify-content: center;
  flex-shrink: 0;
  box-shadow: 0 2px 6px rgba(30, 165, 154, 0.15);
}
.folderMeta {
  flex: 1;
  min-width: 0;
}
.folderName {
  font-size: 15px;
  font-weight: 600;
  color: #212930;
  overflow: hidden;
  white-space: nowrap;
  text-overflow: ellipsis;
}
.folderSub {
  font-size: 12px;
  color: #909399;
  margin-top: 3px;
  display: flex;
  align-items: center;
  gap: 6px;
}
.ownerDot {
  width: 6px;
  height: 6px;
  border-radius: 50%;
  background: #67c23a;
  flex-shrink: 0;
}
.meBadge {
  font-size: 10px;
  padding: 1px 5px;
  border-radius: 3px;
  background: #f0f9eb;
  color: #67c23a;
  font-weight: 500;
}

.myPermBadge {
  flex-shrink: 0;
  padding: 5px 12px;
  border-radius: 6px;
  background: #fff;
  border: 1px solid #1ea59a;
  color: #1ea59a;
  font-size: 12px;
  font-weight: 500;
  display: flex;
  align-items: center;
  gap: 4px;
}
.myPermBadge .dot {
  width: 6px;
  height: 6px;
  border-radius: 50%;
  background: currentColor;
}
.myPermBadge.owner {
  border-color: #1ea59a;
  color: #1ea59a;
}
.myPermBadge.write {
  border-color: #4bb7ae;
  color: #4bb7ae;
}
.myPermBadge.read {
  border-color: #dcdfe6;
  color: #909399;
}

/* 区块标题 */
.sectionTitle {
  font-size: 13px;
  font-weight: 600;
  color: #606266;
  margin-bottom: 10px;
  display: flex;
  align-items: center;
  gap: 8px;
}
.sectionCount {
  background: #f0f2f5;
  color: #606266;
  padding: 1px 8px;
  border-radius: 10px;
  font-size: 11px;
  font-weight: normal;
}
.sectionHint {
  font-size: 12px;
  font-weight: normal;
  color: #c0c4cc;
}

/* 添加成员区 */
.addBox {
  background: #fafbfc;
  border: 1px solid #f0f2f5;
  border-radius: 8px;
  padding: 12px;
  margin-bottom: 20px;
}
.addBox.disabled {
  opacity: 0.5;
  pointer-events: none;
}
.addRow {
  display: flex;
  align-items: center;
  gap: 8px;
}

.searchBox {
  flex: 1;
  position: relative;
}
.searchInput {
  width: 100%;
  height: 36px;
  border: 1px solid #dcdfe6;
  border-radius: 6px;
  padding: 0 32px 0 36px;
  font-size: 13px;
  background: #fff;
  outline: none;
  transition: border-color 0.2s;
  font-family: inherit;
  color: inherit;
}
.searchInput:focus {
  border-color: #1ea59a;
}
.searchIcon {
  position: absolute;
  left: 12px;
  top: 50%;
  transform: translateY(-50%);
  color: #c0c4cc;
  font-size: 14px;
  pointer-events: none;
}
.searchArrow {
  position: absolute;
  right: 12px;
  top: 50%;
  transform: translateY(-50%);
  color: #c0c4cc;
  font-size: 12px;
  pointer-events: none;
}

.searchDropdown {
  position: absolute;
  top: calc(100% + 4px);
  left: 0;
  right: 0;
  background: #fff;
  border: 1px solid #ebeef5;
  border-radius: 6px;
  box-shadow: 0 6px 24px rgba(0, 0, 0, 0.1);
  max-height: 280px;
  overflow-y: auto;
  z-index: 10;
}
.dropdownGroupTitle {
  font-size: 11px;
  color: #c0c4cc;
  padding: 8px 12px 4px;
  font-weight: 600;
  text-transform: uppercase;
  letter-spacing: 0.5px;
}
.dropdownItem {
  padding: 8px 12px;
  display: flex;
  align-items: center;
  gap: 10px;
  cursor: pointer;
  transition: background 0.15s;
}
.dropdownItem:hover {
  background: #e8f5f3;
}
.dropdownAvatar {
  width: 28px;
  height: 28px;
  border-radius: 50%;
  display: flex;
  align-items: center;
  justify-content: center;
  color: #fff;
  font-size: 12px;
  font-weight: 500;
  flex-shrink: 0;
}
.dropdownAvatar.user {
  background: linear-gradient(135deg, #1ea59a, #4bb7ae);
}
.dropdownAvatar.group {
  background: linear-gradient(135deg, #ffb133, #ffc966);
}
.dropdownInfo {
  flex: 1;
  min-width: 0;
}
.dropdownName {
  font-size: 13px;
  color: #212930;
}
.dropdownAccount {
  font-size: 11px;
  color: #909399;
  margin-top: 1px;
}
.dropdownEmpty {
  text-align: center;
  padding: 24px 12px;
  font-size: 12px;
  color: #909399;
}

/* 权限选择器 */
.permSelect {
  position: relative;
  flex-shrink: 0;
}
.permSelectTrigger {
  height: 36px;
  padding: 0 10px 0 12px;
  border: 1px solid #dcdfe6;
  border-radius: 6px;
  background: #fff;
  display: flex;
  align-items: center;
  gap: 6px;
  font-size: 13px;
  min-width: 92px;
  transition: all 0.15s;
}
.permSelectTrigger.clickable {
  cursor: pointer;
}
.permSelectTrigger.clickable:hover {
  border-color: #1ea59a;
  background: #e8f5f3;
}
.permSelectTrigger .dot {
  width: 8px;
  height: 8px;
  border-radius: 50%;
  flex-shrink: 0;
}
.permSelectTrigger .arrow {
  margin-left: auto;
  color: #c0c4cc;
  font-size: 10px;
}
.permSelectTrigger.read {
  color: #909399;
}
.permSelectTrigger.read .dot {
  background: #909399;
}
.permSelectTrigger.write {
  color: #1ea59a;
  font-weight: 500;
}
.permSelectTrigger.write .dot {
  background: #1ea59a;
}
.permSelectTrigger.manage {
  color: #67c23a;
  font-weight: 500;
}
.permSelectTrigger.manage .dot {
  background: #67c23a;
}

.permSelectDropdown {
  position: absolute;
  top: calc(100% + 6px);
  right: 0;
  width: 260px;
  background: #fff;
  border: 1px solid #ebeef5;
  border-radius: 8px;
  box-shadow: 0 8px 28px rgba(0, 0, 0, 0.12);
  overflow: hidden;
  z-index: 30;
}
.permOption {
  padding: 10px 12px;
  display: flex;
  align-items: flex-start;
  gap: 10px;
  cursor: pointer;
  transition: background 0.15s;
  border-bottom: 1px solid #f0f2f5;
}
.permOption:last-child {
  border-bottom: none;
}
.permOption:hover {
  background: #e8f5f3;
}
.permOptionIcon {
  width: 24px;
  height: 24px;
  border-radius: 5px;
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 13px;
  flex-shrink: 0;
  margin-top: 1px;
}
.permOptionIcon.read {
  background: #f0f2f5;
  color: #909399;
}
.permOptionIcon.write {
  background: #e8f5f3;
  color: #1ea59a;
}
.permOptionIcon.manage {
  background: #f0f9eb;
  color: #67c23a;
}
.permOptionBody {
  flex: 1;
  min-width: 0;
}
.permOptionName {
  font-size: 13px;
  font-weight: 500;
  color: #212930;
  display: flex;
  align-items: center;
  gap: 6px;
}
.permOptionName .check {
  margin-left: auto;
  color: #1ea59a;
  font-size: 13px;
}
.permOptionDesc {
  font-size: 11px;
  color: #909399;
  margin-top: 3px;
  line-height: 1.5;
}

.addBtn {
  flex-shrink: 0;
}

/* 成员列表 */
.memberList {
  border: 1px solid #f0f2f5;
  border-radius: 8px;
  overflow: hidden;
}
.memberItem {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 12px 14px;
  background: #fff;
  border-bottom: 1px solid #f0f2f5;
  transition: background 0.15s;
}
.memberItem:last-child {
  border-bottom: none;
}
.memberItem:hover {
  background: #fafbfc;
}
.memberAvatar {
  width: 32px;
  height: 32px;
  border-radius: 50%;
  color: #fff;
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 13px;
  font-weight: 500;
  flex-shrink: 0;
}
.memberAvatar.user {
  background: linear-gradient(135deg, #1ea59a, #4bb7ae);
}
.memberAvatar.group {
  background: linear-gradient(135deg, #ffb133, #ffc966);
}
.memberInfo {
  flex: 1;
  min-width: 0;
}
.memberName {
  font-size: 13px;
  font-weight: 500;
  color: #212930;
  display: flex;
  align-items: center;
  gap: 6px;
  flex-wrap: wrap;
}
.memberAccount {
  font-size: 12px;
  color: #909399;
  margin-top: 2px;
}
.memberTag {
  font-size: 10px;
  padding: 1px 5px;
  border-radius: 3px;
  background: #e8f5f3;
  color: #1ea59a;
  font-weight: normal;
}
.memberTag.ownerTag {
  background: #f0f9eb;
  color: #67c23a;
}
.memberTag.meTag {
  background: #f0f9eb;
  color: #67c23a;
}
.memberActions {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-shrink: 0;
}
.memberPerm {
  font-size: 12px;
  padding: 4px 10px;
  border-radius: 4px;
  display: flex;
  align-items: center;
  gap: 4px;
  border: 1px solid transparent;
  white-space: nowrap;
}
.memberPerm.clickable {
  cursor: pointer;
  transition: opacity 0.15s;
}
.memberPerm.clickable:hover {
  opacity: 0.85;
}
.memberPerm.read {
  background: #f0f2f5;
  color: #909399;
}
.memberPerm.write {
  background: #e8f5f3;
  color: #1ea59a;
}
.memberPerm.manage {
  background: #f0f9eb;
  color: #67c23a;
}
.memberRemove {
  color: #f56c6c;
  font-size: 12px;
  cursor: pointer;
  padding: 4px 6px;
  opacity: 0.7;
  transition: opacity 0.15s;
}
.memberRemove:hover {
  opacity: 1;
}

/* 空状态 */
.emptyState {
  text-align: center;
  padding: 40px 0;
}
.emptyIcon {
  width: 56px;
  height: 56px;
  margin: 0 auto 12px;
  background: #e8f5f3;
  border-radius: 50%;
  display: flex;
  align-items: center;
  justify-content: center;
  color: #1ea59a;
  font-size: 24px;
}
.emptyTitle {
  font-size: 14px;
  color: #606266;
  font-weight: 500;
}
.emptyText {
  font-size: 12px;
  color: #909399;
  margin-top: 4px;
}

/* 转让所有权 */
.transferZone {
  margin-top: 20px;
  padding-top: 16px;
  border-top: 1px dashed #dcdfe6;
}
.transferTrigger {
  display: flex;
  align-items: center;
  justify-content: space-between;
  cursor: pointer;
  color: #909399;
  font-size: 13px;
  padding: 6px 0;
}
.transferTrigger:hover {
  color: #f56c6c;
}
.transferTrigger .arrow {
  transition: transform 0.2s;
}
.transferTrigger.open .arrow {
  transform: rotate(90deg);
}
.transferTrigger .danger {
  color: #f56c6c;
}
.transferPanel {
  margin-top: 12px;
  padding: 14px;
  background: #fef0f0;
  border: 1px solid #fde2e2;
  border-radius: 6px;
}
.transferWarn {
  display: flex;
  gap: 8px;
  align-items: flex-start;
  color: #f56c6c;
  font-size: 12px;
  margin-bottom: 10px;
}
.transferWarn .warnIcon {
  font-size: 14px;
  flex-shrink: 0;
}
.transferRow {
  display: flex;
  gap: 8px;
  margin-top: 10px;
}
</style>

<style>
/* 成员权限 popover（非 scoped，因为 popper 渲染到 body） */
.shareDialogMemberPermPopover {
  padding: 4px !important;
}
.shareDialogMemberPermPopover .permOption {
  padding: 10px 12px;
  display: flex;
  align-items: flex-start;
  gap: 10px;
  cursor: pointer;
  transition: background 0.15s;
  border-bottom: 1px solid #f0f2f5;
}
.shareDialogMemberPermPopover .permOption:last-child {
  border-bottom: none;
}
.shareDialogMemberPermPopover .permOption:hover {
  background: #e8f5f3;
}
.shareDialogMemberPermPopover .permOptionIcon {
  width: 24px;
  height: 24px;
  border-radius: 5px;
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 13px;
  flex-shrink: 0;
  margin-top: 1px;
}
.shareDialogMemberPermPopover .permOptionIcon.read {
  background: #f0f2f5;
  color: #909399;
}
.shareDialogMemberPermPopover .permOptionIcon.write {
  background: #e8f5f3;
  color: #1ea59a;
}
.shareDialogMemberPermPopover .permOptionIcon.manage {
  background: #f0f9eb;
  color: #67c23a;
}
.shareDialogMemberPermPopover .permOptionBody {
  flex: 1;
  min-width: 0;
}
.shareDialogMemberPermPopover .permOptionName {
  font-size: 13px;
  font-weight: 500;
  color: #212930;
  display: flex;
  align-items: center;
  gap: 6px;
}
.shareDialogMemberPermPopover .permOptionName .check {
  margin-left: auto;
  color: #1ea59a;
  font-size: 13px;
}
.shareDialogMemberPermPopover .permOptionDesc {
  font-size: 11px;
  color: #909399;
  margin-top: 3px;
  line-height: 1.5;
}
</style>
