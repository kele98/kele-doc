<template>
  <div class="contentContainer">
    <div class="header">
      <div class="headerLeft">
        <el-input
          v-model="searchText"
          style="width: 300px; --el-border-radius-base: 16px"
          placeholder="搜索文件/文件夹"
          clearable
          :prefix-icon="Search"
          @keyup.enter="onSearch"
          @clear="exitSearch"
        />
      </div>
      <div class="headerRight">
        <Avatar></Avatar>
      </div>
    </div>
    <div class="content">
      <div class="contentHeader">
        <div class="left">
          <!-- 多选操作 -->
          <div class="selectActionBox" v-if="checkedFileList.length > 0">
            <el-checkbox
              v-model="isCheckAll"
              :indeterminate="isIndeterminate"
              label="全选"
              size="large"
              @change="onCheckAllChange"
            />
            <div class="actionBtn" @click="copyOrMoveFiles">
              <span class="iconfont icon-a-yidong2"></span>
              <span class="text">{{ isCurrentFolderOwner ? '移动/复制' : '复制' }}</span>
            </div>
            <div class="actionBtn delete" @click="deleteFiles">
              <span class="iconfont icon-shanchu"></span>
              <span class="text">删除</span>
            </div>
            <div class="actionBtn" @click="exitSelect">
              <span class="iconfont icon-guanbi"></span>
              <span class="text">取消批量操作</span>
            </div>
          </div>
          <!-- 搜索提示 -->
          <div class="searchTip" v-else-if="isSearch">
            <span class="exitSearchBtn" @click="exitSearch">← 返回</span>
            "{{ currentSearchText }}"的搜索结果：
          </div>
          <!-- 根目录：Tab 栏 -->
          <div class="tabBar" v-else-if="isRootView">
            <div
              class="tabItem"
              :class="{ active: activeTab === 'all' }"
              @click="onTabChange('all')"
            >全部</div>
            <div
              class="tabItem"
              :class="{ active: activeTab === 'mine' }"
              @click="onTabChange('mine')"
            >我的</div>
            <div
              class="tabItem"
              :class="{ active: activeTab === 'shared' }"
              @click="onTabChange('shared')"
            >分享给我</div>
          </div>
          <!-- 子文件夹：根目录返回 + 面包屑 -->
          <div class="breadcrumbArea" v-else>
            <span class="backToRoot" @click="clearCurrentNode">← 根目录</span>
            <el-icon class="breadcrumbSeparator"><ArrowRight /></el-icon>
            <FolderPath
              :pathList="currentFolderPath"
              :current="currentFolder"
              @click="onFolderPathClick"
            ></FolderPath>
          </div>
        </div>
        <div class="right">
          <el-button class="marginRight" @click="createFolder" v-if="!isSearch"
            >新建文件夹</el-button
          >
          <TypeFilter
            class="marginRight"
            :filterType="currentFilterType"
            @change="onFilterTypeChange"
          ></TypeFilter>
          <Sort
            v-if="!isSearch"
            class="marginRight"
            :sortField="currentSortField"
            :sortType="currentSortType"
            @changeType="onsortTypeChange"
            @changeField="onSortFieldChange"
          ></Sort>
          <el-tooltip
            effect="light"
            :content="currentLayoutType === 'grid' ? '网格视图' : '列表视图'"
            placement="bottom"
          >
            <IconBtn
              :icon="
                currentLayoutType === 'grid'
                  ? 'icon-shuanglieliebiao'
                  : 'icon-danlieliebiao'
              "
              @click="toggleLayoutType"
            ></IconBtn>
          </el-tooltip>
        </div>
      </div>
      <div class="contentBody" @contextmenu.stop.prevent="onContextMenu">
        <View
          :view="currentLayoutType"
          :fileList="fileList"
          :folderList="folderList"
          :isLoading="isLoading"
          :isSelectMode="checkedFileList.length > 0"
          :fileAdditionalMenuList="fileAdditionalMenuList"
          @folderClick="onFolderClick"
          @renamed="reloadList"
          @moved="reloadList"
          @deleted="reloadList"
        ></View>
        <NoData
          :tip="isSearch ? '搜索无结果' : '点击左上角「创建」吧'"
          :showAddIcon="!isSearch"
          v-if="!isLoading && folderList.length <= 0 && fileList.length <= 0"
        ></NoData>
        <ContextMenu
          ref="ContextMenuRef"
          @createFolder="createFolder"
        ></ContextMenu>
      </div>
    </div>
  </div>
</template>

<script setup>
import { ref, computed, watch, onUnmounted } from 'vue'
import { Search, ArrowRight } from '@element-plus/icons-vue'
import Avatar from './components/common/Avatar.vue'
import { useStore } from '@/store'
import { ElMessage, ElMessageBox } from 'element-plus'
import api from '@/api'
import NoData from './components/common/NoData.vue'
import ContextMenu from './components/content/ContextMenu.vue'
import emitter from '@/utils/eventBus'
import IconBtn from './components/common/IconBtn.vue'
import TypeFilter from './components/content/TypeFilter.vue'
import Sort from './components/content/Sort.vue'
import View from './components/content/View.vue'
import useLayoutChange from '@/hooks/useLayoutChange'
import { emitContextmenuEvent } from '@/hooks/useContextMenuEvent'
import { RESOURCE_TYPES } from '@/constant'
import FolderPath from './components/content/FolderPath.vue'

const store = useStore()

// ==================== 根目录 Tab ====================
const activeTab = ref('all')
const isRootView = computed(() => !currentFolder.value)

const onTabChange = (tab) => {
  activeTab.value = tab
  loadRootView()
}

// ==================== 搜索 ====================
const isSearch = ref(false)
const searchText = ref('')
const currentSearchText = ref('')
const fileAdditionalMenuList = computed(() => {
  return isSearch.value
    ? [
        {
          name: '打开所在文件夹',
          value: 'locationFolder',
          icon: 'icon-wenjianjia1',
          onClick: item => {
            onFolderClick(item, true)
          }
        }
      ]
    : []
})

const onSearch = () => {
  const text = searchText.value.trim()
  if (text) {
    isSearch.value = true
    currentSearchText.value = text
    clearCurrentNode()
    searchFolderAndFileList()
  }
}
const resetSearch = () => {
  isSearch.value = false
  searchText.value = ''
  currentSearchText.value = ''
}
const exitSearch = () => {
  resetSearch()
  loadRootView()
}
const searchFolderAndFileList = async () => {
  try {
    folderList.value = []
    fileList.value = []
    isLoading.value = true
    const { data } = await api.searchFolderAndFile({
      name: currentSearchText.value,
      fileType: currentFilterType.value === 'all' ? '' : currentFilterType.value
    })
    folderList.value = data.folderList || []
    fileList.value = data.fileList || []
    isLoading.value = false
  } catch (error) {
    console.log(error)
    isLoading.value = false
  }
}

// ==================== 数据加载 ====================
const currentFolder = computed(() => store.currentFolder)
const isCurrentFolderOwner = computed(() => {
  const folder = store.currentFolder
  if (!folder || !store.userInfo) return true
  return folder.isOwner !== false
})
const currentFolderPath = computed(() => store.currentFolderPath)
const folderList = ref([])
const fileList = ref([])
const isLoading = ref(true)
const currentFilterType = ref('all')
const currentSortField = ref('createAt')
const currentSortType = ref('desc')

// 刷新当前列表
const reloadList = () => {
  if (isSearch.value) {
    searchFolderAndFileList()
  } else if (isRootView.value) {
    loadRootView()
  } else {
    getFolderAndFileList()
  }
}

// --- 根目录视图：合并自有 + 共享数据 ---
let cachedOwnedFolders = []
let cachedOwnedFiles = []
let cachedSharedFolders = []
let rootFolderId = null

const loadRootView = async () => {
  folderList.value = []
  fileList.value = []
  isLoading.value = true
  try {
    const requests = []
    const needOwned = activeTab.value !== 'shared'
    const needShared = activeTab.value !== 'mine'

    if (needOwned) {
      requests.push(
        (async () => {
          // 先获取根文件夹 ID
          if (!rootFolderId) {
            const { data: treeData } = await api.getFolderTree({ folderId: '' })
            if (treeData && treeData.length > 0) {
              rootFolderId = treeData[0].id
            }
          }
          if (!rootFolderId) return
          const { data } = await api.getFolderAndFileList({
            folderId: rootFolderId,
            fileType: currentFilterType.value === 'all' ? '' : currentFilterType.value,
            sortField: currentSortField.value,
            sortType: currentSortType.value
          })
          cachedOwnedFolders = data.folderList || []
          cachedOwnedFiles = (data.fileList || []).map(item => ({ ...item, checked: false }))
        })()
      )
    }
    if (needShared) {
      requests.push(
        api.getAccessibleFolders().then(({ data }) => {
          const shared = (data || []).filter(f => f.isOwner === false && !f.isOrgPublic)
          cachedSharedFolders = dedupSharedFolders(shared)
        })
      )
    }
    await Promise.all(requests)

    // 根据 Tab 合并数据
    if (activeTab.value === 'all') {
      const ownedIds = new Set(cachedOwnedFolders.map(f => f.id))
      folderList.value = [
        ...cachedOwnedFolders.map(f => ({ ...f })),
        // 去重 + 标记已接收（同时清除 isShared，避免双标签）
        ...cachedSharedFolders.filter(f => !ownedIds.has(f.id)).map(f => ({ ...f, isReceived: true, isShared: false }))
      ]
      fileList.value = cachedOwnedFiles
    } else if (activeTab.value === 'mine') {
      folderList.value = cachedOwnedFolders.map(f => ({ ...f }))
      fileList.value = cachedOwnedFiles
    } else {
      folderList.value = cachedSharedFolders.map(f => ({ ...f, isReceived: true, isShared: false }))
      fileList.value = []
    }
  } catch (error) {
    console.log(error)
  } finally {
    isLoading.value = false
  }
}

// 去重：移除祖先已在列表中的 folder（只保留顶层入口）
const dedupSharedFolders = (folders) => {
  const idSet = new Set(folders.map(f => f.id))
  return folders.filter(f => {
    let pid = f.parentId
    while (pid && pid !== 0) {
      if (idSet.has(pid)) return false
      const parent = folders.find(s => s.id === pid)
      pid = parent ? parent.parentId : 0
    }
    return true
  })
}

// --- 子文件夹视图：原有逻辑 ---
const getFolderAndFileList = async () => {
  try {
    if (isSearch.value || !currentFolder.value) return
    folderList.value = []
    fileList.value = []
    isLoading.value = true
    const { data } = await api.getFolderAndFileList({
      folderId: currentFolder.value.id,
      fileType: currentFilterType.value === 'all' ? '' : currentFilterType.value,
      sortField: currentSortField.value,
      sortType: currentSortType.value
    })
    folderList.value = data.folderList || []
    fileList.value = (data.fileList || []).map(item => ({
      ...item,
      checked: false
    }))
    isLoading.value = false
  } catch (error) {
    console.log(error)
    isLoading.value = false
  }
}

emitter.on('refresh_list', reloadList)

// 监听当前所在文件夹，改变了刷新列表数据
watch(
  () => currentFolder.value,
  () => {
    if (isSearch.value) {
      resetSearch()
    }
    if (currentFolder.value) {
      getFolderAndFileList()
    } else {
      loadRootView()
    }
  },
  { immediate: true }
)

// 过滤/排序
const onFilterTypeChange = val => {
  currentFilterType.value = val
  reloadList()
}
const onSortFieldChange = val => {
  currentSortField.value = val
  reloadList()
}
const onsortTypeChange = val => {
  currentSortType.value = val
  reloadList()
}

// ==================== 导航 ====================
// 文件夹路径点击
const onFolderPathClick = folder => {
  store.setCurrentFolder(folder)
  const index = currentFolderPath.value.findIndex(item => item.id === folder.id)
  store.setCurrentFolderPath(currentFolderPath.value.slice(0, index + 1))
}

// 文件夹点击（进入子目录）
const onFolderClick = async (folder, isFile = false) => {
  try {
    let path = []
    if (isSearch.value) {
      const { data } = await api.getFolderPath({ folderId: folder.id })
      if (isFile) {
        path = data.slice(0, -1)
        folder = path[path.length - 1]
      } else {
        path = data
      }
    }
    store.setCurrentFolder(folder)
    store.setCurrentFolderPath(
      isSearch.value ? path : [...currentFolderPath.value, folder]
    )
  } catch (error) {
    console.log(error)
  }
}

const clearCurrentNode = () => {
  store.setCurrentFolderPath([])
  store.setCurrentFolder(null)
}

// ==================== 多选 ====================
const isCheckAll = ref(false)
const checkedFileList = computed(() => {
  return fileList.value
    .filter(item => item.checked)
    .map(item => ({ ...item }))
})
watch(
  () => checkedFileList.value.length,
  val => {
    const allLength = fileList.value.length
    isCheckAll.value = allLength > 0 && val >= allLength
  }
)
const isIndeterminate = computed(() => {
  const checkedLength = checkedFileList.value.length
  const allLength = fileList.value.length
  return allLength > 0 && checkedLength > 0 && checkedLength < allLength
})
const onCheckAllChange = val => {
  fileList.value.forEach(item => { item.checked = val })
}
const exitSelect = () => { onCheckAllChange(false) }
const copyOrMoveFiles = async () => {
  try {
    emitter.emit('show_move_dialog', {
      type: RESOURCE_TYPES.FILE,
      name: '所选文件',
      ids: checkedFileList.value.map(item => item.id),
      callback: () => { reloadList() }
    })
  } catch (error) {
    console.log(error)
  }
}
const deleteFiles = async () => {
  ElMessageBox.confirm(`是否确认删除【所选文件】？`, '删除文件', {
    confirmButtonText: '确认',
    cancelButtonText: '取消',
    type: 'warning'
  }).then(async () => {
    try {
      await api.deleteFile({
        ids: checkedFileList.value.map(item => item.id)
      })
      ElMessage({ type: 'success', message: '删除成功' })
      reloadList()
    } catch (error) {
      console.log(error)
    }
  })
}

// ==================== 右键菜单 ====================
const ContextMenuRef = ref(null)
const onContextMenu = e => {
  emitContextmenuEvent()
  if (ContextMenuRef.value && !isSearch.value) {
    ContextMenuRef.value.show(e)
  }
}
const hideContextMenu = () => {
  if (ContextMenuRef.value) ContextMenuRef.value.hide()
}
emitter.on('contextmenu', hideContextMenu)

// ==================== 创建文件夹 ====================
const createFolder = () => {
  // 根目录时临时设置 currentFolder 为根文件夹，让 NameEditDialog 能拿到 parentFolderId
  const wasRootView = isRootView.value
  if (wasRootView && rootFolderId) {
    store.setCurrentFolder({ id: rootFolderId, name: '根目录' })
  }
  emitter.emit('show_name_edit_dialog', {
    type: RESOURCE_TYPES.FOLDER,
    callback: data => {
      if (wasRootView) {
        // 创建后回到根目录并刷新列表
        store.setCurrentFolder(null)
        store.setCurrentFolderPath([])
        rootFolderId = null // 清缓存，强制重新加载
        loadRootView()
      } else {
        onFolderClick(data)
      }
    }
  })
}

// ==================== 布局 ====================
const { currentLayoutType, toggleLayoutType } = useLayoutChange()

onUnmounted(() => {
  emitter.off('refresh_list', reloadList)
  emitter.off('contextmenu', hideContextMenu)
})
</script>

<style lang="less" scoped>
.contentContainer {
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
        overflow: hidden;
        margin-right: 12px;

        .searchTip {
          color: #212930;
          height: 40px;
          display: flex;
          align-items: center;

          .exitSearchBtn {
            color: var(--theme-color);
            cursor: pointer;
            margin-right: 12px;
            font-size: 14px;

            &:hover {
              opacity: 0.8;
            }
          }
        }

        .tabBar {
          display: flex;
          align-items: center;
          height: 40px;
          gap: 8px;

          .tabItem {
            padding: 6px 20px;
            font-size: 14px;
            color: #606266;
            cursor: pointer;
            user-select: none;
            border-radius: 16px;
            transition: all 0.2s;

            &:hover {
              color: var(--theme-color);
              background-color: var(--el-fill-color-light);
            }

            &.active {
              color: #fff;
              font-weight: 600;
              background-color: var(--theme-color);
            }
          }
        }

        .breadcrumbArea {
          display: flex;
          align-items: center;
          height: 40px;
          gap: 4px;

          .backToRoot {
            color: var(--theme-color);
            font-size: 14px;
            cursor: pointer;
            user-select: none;
            white-space: nowrap;
            flex-shrink: 0;

            &:hover {
              opacity: 0.8;
            }
          }

          .breadcrumbSeparator {
            color: #c0c4cc;
            flex-shrink: 0;
          }
        }

        .selectActionBox {
          display: flex;
          align-items: center;

          .actionBtn {
            display: flex;
            align-items: center;
            color: var(--theme-color);
            margin-left: 15px;
            cursor: pointer;
            user-select: none;

            &:first-of-type {
              margin-left: 20px;
            }

            &:hover {
              &.delete {
                color: #f56c6c;
              }
            }

            .text {
              font-size: 14px;
            }
          }
        }
      }

      .right {
        display: flex;
        align-items: center;
        flex-shrink: 0;

        .marginRight {
          margin-right: 12px;
        }
      }
    }

    .contentBody {
      width: 100%;
      height: 100%;
      overflow-y: auto;
      padding: 0 14px;
    }
  }
}
</style>
