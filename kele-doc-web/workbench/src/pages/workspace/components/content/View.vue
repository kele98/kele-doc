<template>
  <div class="viewContainer">
    <template v-if="view === 'grid'">
      <!-- 文件夹列表 -->
      <div
        class="listHeader"
        v-if="showTitle && !isLoading && folderList.length > 0"
      >
        <div class="title">文件夹</div>
      </div>
      <GridView
        :type="RESOURCE_TYPES.FOLDER"
        :list="folderList"
        :enableDrag="enableDrag"
        :coverFolderMenuList="effectiveFolderMenuList"
        @moved="onMoved"
        @click="onFolderClick"
        @actionClick="onActionClick($event, RESOURCE_TYPES.FOLDER)"
      ></GridView>
      <!-- 文件列表 -->
      <div
        class="listHeader"
        v-if="showTitle && !isLoading && fileList.length > 0"
      >
        <div class="title">文件</div>
      </div>
      <GridView
        :type="RESOURCE_TYPES.FILE"
        :list="fileList"
        :isSelectMode="isSelectMode"
        :showCheckbox="showCheckbox"
        :enableDrag="enableDrag"
        :fileAdditionalMenuList="fileAdditionalMenuList"
        :showCollectBtn="showCollectBtn"
        :coverFileMenuList="effectiveFileMenuList"
        @click="onFileClick"
        @actionClick="onActionClick($event, RESOURCE_TYPES.FILE)"
      ></GridView>
    </template>
    <ListView
      v-else-if="!isLoading && (folderList.length > 0 || fileList.length > 0)"
      style="padding: 0 12px"
      :folderList="folderList"
      :fileList="fileList"
      :showCheckbox="showCheckbox"
      :coverFolderMenuList="effectiveFolderMenuList"
      :coverFileMenuList="effectiveFileMenuList"
      :fileAdditionalMenuList="fileAdditionalMenuList"
      :showCollectBtn="showCollectBtn"
      @folderClick="onFolderClick"
      @fileClick="onFileClick"
      @actionClick="onActionClick"
    ></ListView>

    <!-- 分享对话框（spec §4.2，FolderCard 菜单 'share' 触发） -->
    <ShareDialog
      v-if="shareDialog.folderId"
      v-model="shareDialog.visible"
      :folderId="shareDialog.folderId"
      :folderName="shareDialog.folderName"
      @changed="onShareChanged"
    />
  </div>
</template>

<script setup>
import { reactive, computed } from 'vue'
import GridView from './GridView.vue'
import ListView from './ListView.vue'
import ShareDialog from '@/components/ShareDialog.vue'
import useFileHandle from '@/hooks/useFileHandle'
import emitter from '@/utils/eventBus'
import { ElMessage, ElMessageBox } from 'element-plus'
import api from '@/api'
import { RESOURCE_TYPES } from '@/constant'
import { useStore } from '@/store'

const props = defineProps({
  // 是否显示标题
  showTitle: {
    type: Boolean,
    default: true
  },
  // 是否显示多选框
  showCheckbox: {
    type: Boolean,
    default: true
  },
  // 是否允许拖拽
  enableDrag: {
    type: Boolean,
    default: true
  },
  // 视图类型
  view: {
    type: String,
    default: 'grid' // list
  },
  // 是否正在加载中
  isLoading: {
    type: Boolean,
    default: false
  },
  // 文件夹列表
  folderList: {
    type: Array,
    default() {
      return []
    }
  },
  // 文件列表
  fileList: {
    type: Array,
    default() {
      return []
    }
  },
  // 是否是多选模式
  isSelectMode: {
    type: Boolean,
    default: false
  },
  // 文件附加的菜单列表
  fileAdditionalMenuList: {
    type: Array,
    default() {
      return []
    }
  },
  // 覆盖原有的菜单列表
  coverFileMenuList: {
    type: Array,
    default() {
      return []
    }
  },
  coverFolderMenuList: {
    type: Array,
    default() {
      return []
    }
  },
  // 是否显示收藏按钮
  showCollectBtn: {
    type: Boolean,
    default: true
  },
  // 禁用文件编辑，即跳转到编辑页面
  disabledFileEdit: {
    type: Boolean,
    default: false
  }
})
const emits = defineEmits(['renamed', 'moved', 'deleted', 'folderClick'])
const fileHandle = useFileHandle()
const store = useStore()

// 当前文件夹是否是自己的（非 owner 的共享文件夹，隐藏复制/移动、分享菜单）
const isCurrentFolderOwner = computed(() => {
  const folder = store.currentFolder
  if (!folder || !store.userInfo) return true
  return folder.isOwner !== false
})

// 非 owner 时，过滤掉 copyOrMove 和 share 的默认菜单项
const ownerOnlyActions = ['copyOrMove', 'share']

// 计算覆盖后的文件夹菜单列表
const effectiveFolderMenuList = computed(() => {
  if (isCurrentFolderOwner.value) return props.coverFolderMenuList
  const base = props.coverFolderMenuList.length > 0
    ? [...props.coverFolderMenuList]
    : [
        { name: '重命名', value: 'rename', icon: 'icon-zhongmingming' },
        { name: '复制/移动', value: 'copyOrMove', icon: 'icon-a-yidong2' },
        { name: '分享', value: 'share', elIcon: 'Share' },
        { name: '删除', value: 'delete', icon: 'icon-shanchu' }
      ]
  return base.filter(item => !ownerOnlyActions.includes(item.value))
})

// 计算覆盖后的文件菜单列表
const effectiveFileMenuList = computed(() => {
  if (isCurrentFolderOwner.value) return props.coverFileMenuList
  const base = props.coverFileMenuList.length > 0
    ? [...props.coverFileMenuList]
    : [
        { name: '重命名', value: 'rename', icon: 'icon-zhongmingming' },
        { name: '复制/移动', value: 'copyOrMove', icon: 'icon-a-yidong2' },
        { name: '删除', value: 'delete', icon: 'icon-shanchu' }
      ]
  return base.filter(item => !ownerOnlyActions.includes(item.value))
})

// 分享对话框状态
const shareDialog = reactive({ visible: false, folderId: null, folderName: '' })

// 文件夹点击
const onFolderClick = (...args) => {
  emits('folderClick', ...args)
}

// 文件点击
const onFileClick = item => {
  if (props.disabledFileEdit) return
  fileHandle.openEditPage(item.type, item.id)
}

// 操作点击
const onActionClick = (payload, type) => {
  const action = payload.action
  const { id, name } = payload.data
  if (action === 'rename') {
    // 重命名
    emitter.emit('show_name_edit_dialog', {
      type,
      id,
      name,
      callback: () => {
        emits('renamed')
      }
    })
  } else if (action === 'copyOrMove') {
    // 复制或移动
    emitter.emit('show_move_dialog', {
      type,
      name,
      ids: [id],
      callback: () => {
        onMoved()
      }
    })
  } else if (action === 'share') {
    // 分享（spec §4.2）—— 当前仅支持文件夹
    if (type === RESOURCE_TYPES.FOLDER) {
      shareDialog.folderId = id
      shareDialog.folderName = name
      shareDialog.visible = true
    } else {
      ElMessage.warning('文件级分享将在后续版本提供')
    }
  } else if (action === 'delete') {
    // 删除
    if (type === RESOURCE_TYPES.FOLDER) {
      deleteFolder(id, name)
    } else {
      deleteFile(id, name)
    }
  }
}

// 移动了文件或文件夹
const onMoved = () => {
  emits('moved')
}

// 分享对话框的 ACL 变更回调
const onShareChanged = () => {
  // 列表数据本身不变（仍是自己可见），但可能需要刷新「共享给我的」面板
  emits('moved')
}

// 删除文件夹
const deleteFolder = async (id, name) => {
  ElMessageBox.confirm(`是否确认删除【${name}】？`, '删除文件夹', {
    confirmButtonText: '确认',
    cancelButtonText: '取消',
    type: 'warning'
  }).then(async () => {
    try {
      await api.deleteFolder({
        id
      })
      emits('deleted')
      emitter.emit('delete_folder_success', id)
      ElMessage.success('删除成功')
    } catch (error) {
      console.log(error)
    }
  })
}

// 删除文件夹
const deleteFile = async (id, name) => {
  ElMessageBox.confirm(`是否确认删除【${name}】？`, '删除文件', {
    confirmButtonText: '确认',
    cancelButtonText: '取消',
    type: 'warning'
  }).then(async () => {
    try {
      await api.deleteFile({
        ids: [id]
      })
      emits('deleted')
      ElMessage.success('删除成功')
    } catch (error) {
      console.log(error)
    }
  })
}
</script>

<style lang="less" scoped>
.viewContainer {
  .listHeader {
    display: flex;
    align-items: center;
    height: 40px;

    .title {
      font-size: 12px;
      color: #9aa5b8;
      height: 20px;
      line-height: 20px;
      padding: 0 10px;
    }
  }
}
</style>
