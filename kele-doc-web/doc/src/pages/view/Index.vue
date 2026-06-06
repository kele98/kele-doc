<template>
  <div class="viewContainer">
    <!-- 顶栏 -->
    <div class="topbar">
      <div class="logo">kele-doc</div>
      <div class="breadcrumb">
        <span v-for="(crumb, i) in breadcrumb" :key="crumb.id || 'root'">
          <span class="crumb" :class="{ active: i === breadcrumb.length - 1 }" @click="onCrumbClick(crumb)">
            {{ crumb.name }}
          </span>
          <span v-if="i < breadcrumb.length - 1" class="sep">/</span>
        </span>
      </div>
      <div class="user">
        <div class="avatar">{{ avatarLetter }}</div>
        <span class="userName">{{ userName }}</span>
        <span v-if="isAdmin" class="adminTag">ADMIN</span>
      </div>
    </div>

    <div class="main">
      <!-- 侧边栏 -->
      <aside class="sidebar">
        <div class="sidebarTitle">我的工作台</div>
        <el-tree
          ref="treeRef"
          :data="folderTree"
          :props="{ label: 'name', children: 'children' }"
          node-key="id"
          highlight-current
          :default-expand-all="false"
          :expand-on-click-node="false"
          @node-click="onTreeNodeClick"
        >
          <template #default="{ node, data }">
            <span class="treeNode">
              <el-icon style="margin-right: 4px;"><Folder /></el-icon>
              {{ node.label }}
            </span>
          </template>
        </el-tree>
      </aside>

      <!-- 内容区 -->
      <main class="content">
        <div class="contentHeader">
          <h1 class="contentTitle">{{ currentFolderName }}</h1>
          <div class="actions">
            <el-button @click="onNewFolder">＋ 新建文件夹</el-button>
            <el-button @click="onUpload">↑ 上传</el-button>
            <el-button @click="onRefresh">🔄 刷新</el-button>
          </div>
        </div>
        <div class="contentSub">{{ breadcrumbText }}</div>

        <div v-if="loading" v-loading="true" class="loadingBox"></div>
        <div v-else-if="items.length === 0" class="empty">
          <el-icon :size="48" color="#c9cdd4"><Folder /></el-icon>
          <p>此文件夹为空</p>
        </div>
        <div v-else class="grid">
          <FolderCard
            v-for="item in items"
            :key="item.id"
            :item="item"
            @click="onCardClick"
            @share="onShare"
            @renamed="onItemChanged"
            @deleted="onItemChanged"
          />
        </div>
      </main>
    </div>

    <!-- 分享对话框 -->
    <ShareDialog
      v-if="shareDialog.folderId"
      v-model="shareDialog.visible"
      :folder-id="shareDialog.folderId"
      :folder-name="shareDialog.folderName"
      @changed="onShareChanged"
    />
  </div>
</template>

<script setup>
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Folder } from '@element-plus/icons-vue'
import api from '@/api'
import { useStore } from '@/store'
import FolderCard from '@/components/FolderCard.vue'
import ShareDialog from '@/components/ShareDialog.vue'

const store = useStore()
const treeRef = ref()

const folderTree = ref([])
const items = ref([])
const breadcrumb = ref([{ id: null, name: '根目录' }])
const currentFolderId = ref(null)
const currentFolderName = ref('根目录')
const loading = ref(false)

const shareDialog = reactive({ visible: false, folderId: null, folderName: '' })

// 用户信息
const avatarLetter = computed(() => {
  const name = store.userInfo?.userName || store.userInfo?.account || '?'
  return name.charAt(0).toUpperCase()
})
const userName = computed(() => store.userInfo?.userName || store.userInfo?.account || '未登录')
const isAdmin = computed(() => store.userInfo?.role === 'ADMIN')
const breadcrumbText = computed(() =>
  breadcrumb.value.map(c => c.name).join(' / ')
)

onMounted(async () => {
  // 预加载当前用户的群组（ShareDialog 用）
  store.loadMyGroups().catch(() => {})
  await loadRoot()
})

async function loadRoot() {
  currentFolderId.value = null
  currentFolderName.value = '根目录'
  breadcrumb.value = [{ id: null, name: '根目录' }]
  await Promise.all([loadTree(), loadItems(null)])
}

async function loadTree() {
  try {
    const { data } = await api.getAllFolderTree()
    folderTree.value = data || []
  } catch (e) {
    console.error('加载文件夹树失败', e)
  }
}

async function loadItems(folderId) {
  loading.value = true
  try {
    const { data } = await api.getFolderAndFileList({
      folderId: folderId == null ? 0 : folderId,
      sortField: 'updateAt',
      sortType: 'desc'
    })
    items.value = [
      ...(data?.folderList || []),
      ...(data?.fileList || [])
    ]
  } catch (e) {
    console.error('加载子项失败', e)
    items.value = []
  } finally {
    loading.value = false
  }
}

async function onTreeNodeClick(data) {
  currentFolderId.value = data.id
  currentFolderName.value = data.name
  await loadBreadcrumb(data.id)
  await loadItems(data.id)
}

async function onCrumbClick(crumb) {
  if (crumb.id == null) {
    await loadRoot()
  } else {
    currentFolderId.value = crumb.id
    currentFolderName.value = crumb.name
    breadcrumb.value = [
      { id: null, name: '根目录' },
      ...breadcrumb.value.slice(1).filter(c => c.id != null),
      crumb
    ]
    await loadItems(crumb.id)
  }
}

async function loadBreadcrumb(folderId) {
  try {
    const { data } = await api.getFolderPath(folderId)
    const list = data || []
    breadcrumb.value = [
      { id: null, name: '根目录' },
      ...list
    ]
  } catch (e) {
    breadcrumb.value = [
      { id: null, name: '根目录' },
      { id: folderId, name: currentFolderName.value }
    ]
  }
}

async function onCardClick(item) {
  if (!item) return
  currentFolderId.value = item.id
  currentFolderName.value = item.name
  await loadBreadcrumb(item.id)
  await loadItems(item.id)
}

function onShare(item) {
  shareDialog.folderId = item.id
  shareDialog.folderName = item.name
  shareDialog.visible = true
}

function onShareChanged() {
  // 列表数据没变（用户自己的可见列表不变），不需要刷新
  // 但可能影响 folderPermissionMap，调用方如有需要可自行处理
}

function onItemChanged() {
  // 重命名/删除后刷新当前列表
  loadItems(currentFolderId.value)
  loadTree()
}

async function onNewFolder() {
  try {
    const { value } = await ElMessageBox.prompt('文件夹名称', '新建文件夹', {
      confirmButtonText: '创建',
      cancelButtonText: '取消'
    })
    if (!value || !value.trim()) return
    await api.createFolder({
      parentId: currentFolderId.value || 0,
      name: value.trim(),
      format: 0
    })
    ElMessage.success('创建成功')
    onItemChanged()
  } catch (e) { /* 取消 */ }
}

function onUpload() {
  ElMessage.info('上传功能（v0.7.1）')
}

function onRefresh() {
  onItemChanged()
}
</script>

<style lang="less" scoped>
.viewContainer {
  display: flex;
  flex-direction: column;
  height: 100vh;
  background: #fff;
}

.topbar {
  height: 56px;
  background: #fff;
  border-bottom: 1px solid #e5e7eb;
  display: flex;
  align-items: center;
  padding: 0 24px;
  gap: 24px;
  flex-shrink: 0;
}
.logo {
  font-size: 20px;
  font-weight: 700;
  color: #3370ff;
  letter-spacing: -0.5px;
}
.breadcrumb {
  color: #86909c;
  font-size: 13px;
  flex: 1;
  display: flex;
  align-items: center;
  overflow: hidden;
  white-space: nowrap;
}
.crumb {
  cursor: pointer;
  padding: 0 4px;
  border-radius: 4px;
}
.crumb:hover { color: #3370ff; background: #f2f3f5; }
.crumb.active { color: #1f2329; font-weight: 500; cursor: default; }
.crumb.active:hover { background: transparent; }
.sep { margin: 0 4px; color: #c9cdd4; }
.user {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 4px 8px;
  border-radius: 6px;
  cursor: default;
}
.avatar {
  width: 28px;
  height: 28px;
  border-radius: 50%;
  background: linear-gradient(135deg, #3370ff, #5b8def);
  color: #fff;
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 13px;
  font-weight: 600;
}
.userName { font-size: 13px; color: #1f2329; }
.adminTag {
  background: #e8f1ff;
  color: #3370ff;
  font-size: 11px;
  padding: 2px 6px;
  border-radius: 4px;
  font-weight: 500;
}

.main { display: flex; flex: 1; overflow: hidden; }
.sidebar {
  width: 240px;
  background: #fafbfc;
  border-right: 1px solid #e5e7eb;
  padding: 12px 8px;
  overflow-y: auto;
  flex-shrink: 0;
}
.sidebarTitle {
  font-size: 12px;
  color: #86909c;
  padding: 8px 12px;
  font-weight: 500;
  text-transform: uppercase;
  letter-spacing: 0.5px;
}
.treeNode {
  display: inline-flex;
  align-items: center;
  font-size: 13px;
  color: #4e5969;
  width: 100%;
  overflow: hidden;
  text-overflow: ellipsis;
}

.content {
  flex: 1;
  overflow-y: auto;
  padding: 24px 32px;
  background: #fff;
}
.contentHeader {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 4px;
}
.contentTitle {
  font-size: 22px;
  font-weight: 600;
  margin: 0;
}
.actions { display: flex; gap: 8px; }
.contentSub {
  font-size: 12px;
  color: #86909c;
  margin-bottom: 24px;
}

.grid {
  display: grid;
  grid-template-columns: repeat(4, 1fr);
  gap: 12px;
}
.empty {
  text-align: center;
  padding: 80px 0;
  color: #86909c;
  p { margin-top: 12px; font-size: 14px; }
}
.loadingBox {
  height: 240px;
}
</style>
