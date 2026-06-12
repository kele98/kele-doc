<template>
  <div
    class="folderCardContainer"
    :style="{ width: width + 'px' }"
    :class="{ isOnDragOver: isOnDragOver }"
    @click.stop="onClick"
    @contextmenu.stop.prevent="onContextmenu"
    :draggable="enableDrag"
    @dragover.prevent
    @drop.stop="onDrop"
    @dragenter.stop="onDragenter"
    @dragleave.stop="onDragleave"
    @dragstart.stop="onDragstart"
    @dragend.prevent="onDragend"
  >
    <span class="icon iconfont icon-wenjianjia"></span>
    <span class="text" :title="data.name">{{ data.name }}</span>
    <span v-if="data.isShared" class="sharedTag shared">已分享</span>
    <span v-if="data.isReceived" class="sharedTag received">已接收</span>
    <el-popover
      placement="bottom"
      :width="160"
      trigger="click"
      popper-style="padding: 4px;"
      v-model:visible="menuListVisible"
    >
      <template #reference>
        <span class="btn iconfont icon-icmore" @click.stop></span>
      </template>
      <Menu :list="menuList" @click="onMenuClick"></Menu>
    </el-popover>
  </div>
</template>

<script setup>
import { ref, onUnmounted, computed } from 'vue'
import Menu from '../common/Menu.vue'
import { ElMessage } from 'element-plus'
import api from '@/api'
import { useStore } from '@/store'
import { useCardContextMenu } from '@/hooks/useContextMenuEvent'
import { RESOURCE_TYPES } from '@/constant'
import emitter from '@/utils/eventBus'

const props = defineProps({
  // 是否允许拖拽
  enableDrag: {
    type: Boolean,
    default: true
  },
  data: {
    type: Object,
    default() {
      return null
    }
  },
  width: {
    type: Number,
    default: 0
  },
  // 覆盖原有菜单列表
  coverFolderMenuList: {
    type: Array,
    default() {
      return []
    }
  }
})
const emits = defineEmits(['click', 'actionClick', 'moved'])
const store = useStore()

const menuList = computed(() => {
  if (props.coverFolderMenuList.length > 0) return props.coverFolderMenuList

  // spec §10.6：分享/管 ACL 需要 MANAGE 权限；非 Owner 且非管理员不显示「分享」
  // isOwner === false 表示"别人共享给我的"，此时只有 READ/WRITE 的不应显示分享
  // isOwner 缺失（undefined）= 来自常规目录列表，用户天然有 MANAGE
  const isReceived = props.data.isReceived === true
  const notOwner = props.data.isOwner === false
  const showShare = !(isReceived && notOwner)

  const items = [
    {
      name: '重命名',
      value: 'rename',
      icon: 'icon-zhongmingming'
    },
    {
      name: '复制/移动',
      value: 'copyOrMove',
      icon: 'icon-a-yidong2'
    }
  ]
  if (showShare) {
    items.push({
      name: '分享',
      value: 'share',
      elIcon: 'Share'
    })
  }
  items.push({
    name: '删除',
    value: 'delete',
    icon: 'icon-shanchu'
  })
  return items
})

const onClick = () => {
  emits('click')
}

const onMenuClick = item => {
  emits('actionClick', item.value)
  if (typeof item.onClick === 'function') {
    item.onClick(props.data, RESOURCE_TYPES.FOLDER)
  }
}

// 移动文件或文件夹
const isOnDragOver = ref(false)
const onDrop = async () => {
  onDragleave()
  try {
    if (store.currentDragData) {
      const { type, data } = store.currentDragData
      if (type === RESOURCE_TYPES.FILE) {
        await api.moveFile({
          ids: [data.id],
          newFolderId: props.data.id
        })
        ElMessage.success('移动成功')
        emits('moved')
      } else {
        if (data.id !== props.data.id) {
          await api.moveFolder({
            id: data.id,
            newFolderId: props.data.id
          })
          ElMessage.success('移动成功')
          emitter.emit('move_folder_success')
          emits('moved')
        }
      }
    }
  } catch (error) {
    console.log(error)
  }
}
const onDragenter = e => {
  isOnDragOver.value = true
}
const onDragleave = e => {
  isOnDragOver.value = false
}
// 开始拖拽
const onDragstart = () => {
  store.setCurrentDragData({
    type: RESOURCE_TYPES.FOLDER,
    data: props.data
  })
}
const onDragend = () => {
  store.setCurrentDragData(null)
}

// 右键显示菜单
const { onContextmenu, menuListVisible, unBindContextmenuEvent } =
  useCardContextMenu()

onUnmounted(() => {
  unBindContextmenuEvent()
})
</script>

<style lang="less" scoped>
.folderCardContainer {
  height: 40px;
  border-radius: 4px;
  border: 1px solid transparent;
  box-sizing: border-box;
  background: #fff;
  box-shadow: 0 1px 1px rgba(0, 0, 0, 0.16);
  display: flex;
  align-items: center;
  padding: 0 10px;
  cursor: pointer;
  transition: all 0.3s;

  &:hover {
    border-color: var(--theme-color);
    transform: translateY(-2px);
  }

  &.isOnDragOver {
    transform: translateY(-2px);
    border-color: var(--theme-color);

    .icon,
    .text,
    .btn {
      pointer-events: none;
    }
  }

  .icon {
    color: var(--folder-color);
    font-size: 24px;
  }

  .text {
    margin-left: 4px;
    overflow: hidden;
    white-space: nowrap;
    text-overflow: ellipsis;
    color: #212930;
    font-size: 14px;
  }

  .sharedTag {
    flex-shrink: 0;
    font-size: 11px;
    line-height: 1;
    padding: 3px 6px;
    border-radius: 8px;
    margin-left: 6px;
    margin-right: 0;

    &.shared {
      background: #ecf5ff;
      color: #409eff;
      border: 1px solid #d9ecff;
    }

    &.received {
      background: #fff7e6;
      color: #fa8c16;
      border: 1px solid #ffe7ba;
    }
  }

  .btn {
    margin-left: auto;
    color: #6c7d8f;
    width: 24px;
    height: 24px;
    display: flex;
    align-items: center;
    justify-content: flex-end;
  }

  .sharedIcon {
    color: #409eff;
    margin-left: 4px;
    flex-shrink: 0;
  }
}
</style>
