<template>
  <div class="sidebarContainer">
    <div class="header">
      <span class="logo">
        <img :src="config.logo" alt="" />
      </span>
      <span class="title">{{ config.name }}</span>
    </div>
    <div class="createBox" @click.stop>
      <div class="createBtn" @click="onCreate">
        <span class="iconfont icon-jia"></span>
        <span class="text">创建</span>
      </div>
      <div class="createTypeList" v-show="createTypeListVisible">
        <div
          class="createTypeItem"
          v-for="item in config.createTypeList"
          :key="item.value"
          @click="createAndOpenNewFile(item.value)"
        >
          <span
            class="iconfont"
            :class="[item.icon]"
            :style="{ color: item.color }"
          ></span>
          <span class="text">{{ item.name }}</span>
        </div>
      </div>
    </div>
    <div class="menuList">
      <div class="sectionHeader" style="margin-top: 4px;">
        <span class="text" style="font-size: 12px; color: #909399; padding-left: 24px;">文件</span>
      </div>
      <div
        class="menuItem"
        :class="{ isActive: route.name === 'Collect' }"
        @click="toCollect"
      >
        <span class="iconfont icon-shoucang"></span>
        <span class="text">我的收藏</span>
      </div>
      <div
        class="menuItem"
        :class="{ isActive: route.name === 'Recycle' }"
        @click="toRecycle"
      >
        <span class="iconfont icon-shanchu"></span>
        <span class="text">回收站</span>
      </div>
      <div class="folderTree">
        <FolderTree
          v-if="isLoadTree"
          ref="FolderTreeRef"
          :isNotSetCurrentNode="isNotSetCurrentNode"
          :currentNodeKey="currentFolder ? currentFolder.id : ''"
          @currentChange="onCurrentChange"
        ></FolderTree>
      </div>
      <!-- v0.7 BUG C fix：分享给我的 section -->
      <div v-if="sharedFolders.length > 0" class="sharedWithMe">
        <div class="sectionHeader">
          <el-icon :size="16"><Share /></el-icon>
          <span class="text">分享给我的</span>
        </div>
        <div
          v-for="folder in sharedFolders"
          :key="folder.id"
          class="sharedItem"
          :class="{ isActive: currentFolder && currentFolder.id === folder.id }"
          @click="onSharedFolderClick(folder)"
        >
          <span class="iconfont icon-wenjianjia1"></span>
          <span class="text">{{ folder.name }}</span>
        </div>
      </div>
      <div
        class="menuItem"
        :class="{ isActive: route.name === 'Panorama' }"
        @click="toPanorama"
      >
        <span class="iconfont icon-siweidaotu1"></span>
        <span class="text">文件全景图</span>
      </div>
      <!-- 管理员专属 -->
      <template v-if="isAdmin">
        <div class="sectionHeader" style="margin-top: 16px;">
          <span class="text" style="font-size: 12px; color: #909399; padding-left: 24px;">管理</span>
        </div>
        <div
          class="menuItem"
          :class="{ isActive: route.name === 'GroupManage' }"
          @click="toGroupManage"
        >
          <el-icon :size="18"><UserFilled /></el-icon>
          <span class="text">群组管理</span>
        </div>
        <div
          class="menuItem"
          :class="{ isActive: route.name === 'UserManage' }"
          @click="toUserManage"
        >
          <el-icon :size="18"><User /></el-icon>
          <span class="text">用户管理</span>
        </div>
      </template>
    </div>
  </div>
</template>

<script setup>
import { nextTick, ref, watch, computed, onMounted, onUnmounted } from 'vue'
import config from '@/config'
import useFileHandle from '@/hooks/useFileHandle'
import { useStore } from '@/store'
import FolderTree from '../common/FolderTree.vue'
import emitter from '@/utils/eventBus'
import { useRouter, useRoute } from 'vue-router'
import { ElMessage } from 'element-plus'
import { UserFilled, User, Share } from '@element-plus/icons-vue'
import api from '@/api'

const store = useStore()
const route = useRoute()
const router = useRouter()

// 创建列表的显示
const createTypeListVisible = ref(false)
const hideCreateTypeList = () => {
  createTypeListVisible.value = false
}
window.addEventListener('click', hideCreateTypeList)

// 创建新文件
const { createAndOpenNewFile } = useFileHandle()

// 点击创建按钮
const onCreate = () => {
  const len = config.createTypeList.length
  if (len <= 0) {
    ElMessage.warning('没有可创建的文件')
    return
  }
  if (len === 1) {
    // 如果只有一种文档类型，直接创建即可
    createAndOpenNewFile(config.createTypeList[0].value)
  } else {
    createTypeListVisible.value = !createTypeListVisible.value
  }
}

// 进入收藏页面
const toCollect = () => {
  clearCurrentNode()
  router.push({
    name: 'Collect'
  })
}

// 进入回收站页面
const toRecycle = () => {
  clearCurrentNode()
  router.push({
    name: 'Recycle'
  })
}

// 进入文件全景图页面
const toPanorama = () => {
  clearCurrentNode()
  router.push({
    name: 'Panorama'
  })
}

const isAdmin = computed(() => store.userInfo && store.userInfo.role === 'ADMIN')

const toGroupManage = () => {
  clearCurrentNode()
  router.push({ name: 'GroupManage' })
}

const toUserManage = () => {
  clearCurrentNode()
  router.push({ name: 'UserManage' })
}

// 文件夹树
const isLoadTree = ref(true)
const isNotSetCurrentNode = ref(route.name !== 'List')
const FolderTreeRef = ref(null)

// v0.7 BUG C fix：分享给我的 folder 列表（isOwner=false 的可访问 folder）
const sharedFolders = ref([])

// 加载分享给我的列表
const loadSharedFolders = async () => {
  try {
    const { data } = await api.getAccessibleFolders()
    // 只保留别人分享给我的（isOwner=false），排除自己拥有的
    const shared = (data || []).filter(f => f.isOwner === false)
    // 过滤掉祖先已在列表中的 folder（只保留顶层入口）
    const idSet = new Set(shared.map(f => f.id))
    sharedFolders.value = shared.filter(f => {
      // 沿 parent 链向上找，如果任何祖先也在列表中，则跳过
      let pid = f.parentId
      while (pid && pid !== 0) {
        if (idSet.has(pid)) return false
        // 找到 parentId 对应的 folder，继续往上
        const parent = shared.find(s => s.id === pid)
        pid = parent ? parent.parentId : 0
      }
      return true
    })
  } catch (e) {
    // 静默失败：不影响主流程
    sharedFolders.value = []
  }
}

// 点击"分享给我的" folder：直接 setCurrentFolder + 跳 List（不知道 parent chain，
// 不算路径，只算当前位置）
const onSharedFolderClick = (folder) => {
  clearCurrentNode()
  store.setCurrentFolder(folder)
  if (route.name !== 'List') {
    router.push({ name: 'List' })
  }
}

onMounted(() => {
  loadSharedFolders()
})
// 监听当前所在文件夹，改变了刷新列表数据
const currentFolder = computed(() => {
  return store.currentFolder
})
watch(
  () => {
    return currentFolder.value
  },
  () => {
    FolderTreeRef.value.setCurrentKey(
      currentFolder.value ? currentFolder.value.id : null
    )
  }
)

// 修改当前文件夹、路径
const onCurrentChange = (data, node) => {
  if (!data || !node) return
  if (route.name !== 'List') {
    router.push({
      name: 'List'
    })
  }
  const pathList = [{ ...data }]
  let parent = node.parent
  while (parent && parent.level > 0) {
    pathList.unshift({ ...parent.data })
    parent = parent.parent
  }
  store.setCurrentFolderPath(pathList)
  store.setCurrentFolder({ ...data })
}

// 清空当前所在的文件夹信息
const clearCurrentNode = () => {
  store.setCurrentFolderPath([])
  store.setCurrentFolder(null)
}

// 重新加载树
const reloadTree = () => {
  isNotSetCurrentNode.value = true
  isLoadTree.value = false
  nextTick(() => {
    isLoadTree.value = true
  })
}
emitter.on('reload_sidebar_tree', reloadTree)
emitter.on('move_folder_success', reloadTree)
emitter.on('copy_folder_success', reloadTree)

// 刷新指定节点的父节点数据
const refreshParentNode = id => {
  const tree = FolderTreeRef.value.getTree()
  const node = tree.getNode(id)
  if (node) {
    const parent = node.parent
    if (parent.loaded) {
      FolderTreeRef.value.updateNodeById(parent.data.id)
    }
  }
}
emitter.on('delete_folder_success', refreshParentNode)
emitter.on('update_folder_success', refreshParentNode)

// 刷新指定节点数据
const refreshNode = id => {
  const tree = FolderTreeRef.value.getTree()
  const node = tree.getNode(id)
  if (node) {
    // 如果之前没有子节点，那么需要修改是否是叶子节点状态
    if (node.isLeaf) {
      node.isLeaf = false
      node.isLeafByUser = false
      node.data.leaf = false
    }
    // 如果该节点还没有加载过，那么直接返回
    if (!node.loaded) return
    // 如果已经加载过了，那么就更新子节点
    FolderTreeRef.value.updateNodeById(id, () => {
      nextTick(() => {
        if (!node.expanded) {
          node.expanded = true
        }
        nextTick(() => {
          FolderTreeRef.value.setCurrentKey(
            currentFolder.value ? currentFolder.value.id : null
          )
        })
      })
    })
  }
}
emitter.on('create_folder_success', refreshNode)

onUnmounted(() => {
  emitter.off('reload_sidebar_tree', reloadTree)
  emitter.off('move_folder_success', reloadTree)
  emitter.off('copy_folder_success', reloadTree)
  emitter.off('delete_folder_success', refreshParentNode)
  emitter.off('update_folder_success', refreshParentNode)
  emitter.off('create_folder_success', refreshNode)
})
</script>

<style lang="less" scoped>
.sidebarContainer {
  width: 250px;
  height: 100%;
  background-color: #fff;
  border-right: 1px solid #e9edf2;
  flex-shrink: 0;
  display: flex;
  flex-direction: column;
  padding-bottom: 12px;

  .header {
    display: flex;
    align-items: center;
    height: 100px;
    justify-content: center;
    flex-shrink: 0;

    .logo {
      width: 50px;
      height: 50px;
      margin-right: 10px;

      img {
        width: 100%;
      }
    }

    .title {
      font-size: 30px;
      font-weight: bold;
      color: var(--theme-color);
    }
  }

  .createBox {
    padding: 0 12px;
    position: relative;
    z-index: 2;
    flex-shrink: 0;

    .createBtn {
      height: 35px;
      display: flex;
      align-items: center;
      justify-content: center;
      background-color: var(--theme-color);
      border-radius: 4px;
      color: #fff;
      cursor: pointer;
      user-select: none;

      &:hover {
        opacity: 0.8;
      }

      &:active {
        opacity: 0.6;
      }

      .text {
        margin-left: 12px;
      }
    }

    .createTypeList {
      position: absolute;
      left: 12px;
      top: 38px;
      width: 272px;
      padding: 16px 20px 0;
      background: #fff;
      box-shadow: 0 6px 16px 1px rgba(0, 0, 0, 0.08),
        0 9px 28px 8px rgba(0, 0, 0, 0.05);
      border-radius: 8px;
      border: 1px solid #e9edf2;
      display: flex;
      flex-wrap: wrap;

      .createTypeItem {
        display: flex;
        flex-direction: column;
        justify-content: center;
        align-items: center;
        width: 70px;
        height: 65px;
        border-radius: 4px;
        cursor: pointer;
        margin-bottom: 16px;
        margin-right: 10px;
        font-weight: bold;

        &:nth-of-type(3n) {
          margin-right: 0;
        }

        &:hover {
          background: #f3f5f9;
        }

        .iconfont {
          font-size: 24px;
        }

        .text {
          color: #6c7d8f;
          font-size: 12px;
          margin-top: 4px;
          user-select: none;
        }
      }
    }
  }

  .menuList {
    margin-top: 20px;
    height: 100%;
    overflow-y: auto;

    .menuItem {
      height: 32px;
      display: flex;
      align-items: center;
      font-size: 16px;
      color: #212930;
      padding-left: 24px;
      cursor: pointer;
      user-select: none;

      &:hover {
        background-color: var(--el-fill-color-light);
      }

      &.isActive {
        background-color: var(--el-color-primary-light-9);
      }

      .iconfont {
        font-size: 18px;
      }

      .text {
        margin-left: 6px;
      }
    }

    .folderTree {
      .customFolderTreeNode {
        display: flex;
        align-items: center;
        font-size: 16px;
        color: #212930;

        .iconfont {
          font-size: 18px;
        }

        .text {
          margin-left: 6px;
        }
      }
    }

    // v0.7 BUG C fix：分享给我的 section 样式
    .sharedWithMe {
      .sectionHeader {
        height: 32px;
        display: flex;
        align-items: center;
        font-size: 14px;
        font-weight: bold;
        color: #6c7d8f;
        padding-left: 24px;
        margin-top: 12px;

        .iconfont {
          font-size: 16px;
          margin-right: 6px;
        }
      }

      .sharedItem {
        height: 30px;
        display: flex;
        align-items: center;
        font-size: 15px;
        color: #212930;
        padding-left: 32px;
        cursor: pointer;
        user-select: none;

        &:hover {
          background-color: var(--el-fill-color-light);
        }

        &.isActive {
          background-color: var(--el-color-primary-light-9);
        }

        .iconfont {
          font-size: 16px;
          color: #6c7d8f;
          margin-right: 6px;
        }

        .text {
          white-space: nowrap;
          overflow: hidden;
          text-overflow: ellipsis;
          flex: 1;
        }
      }
    }
  }
}
</style>
