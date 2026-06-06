<template>
  <div class="card" @click.stop="onCardClick">
    <div class="cardIcon" :class="isFolder ? 'folder' : 'file'">
      <el-icon :size="20">
        <component :is="iconName" />
      </el-icon>
    </div>
    <div class="cardName" :title="item.name">{{ item.name }}</div>
    <el-dropdown
      trigger="click"
      @command="onMenuCommand"
      @click.stop
    >
      <span class="cardMenu" @click.stop>
        <el-icon :size="16"><MoreFilled /></el-icon>
      </span>
      <template #dropdown>
        <el-dropdown-menu>
          <el-dropdown-item command="rename">📝 重命名</el-dropdown-item>
          <el-dropdown-item command="copyOrMove">📋 复制/移动</el-dropdown-item>
          <el-dropdown-item v-if="isFolder" command="share" divided>
            <span class="menu-highlight">🔗 分享</span>
          </el-dropdown-item>
          <el-dropdown-item command="delete" divided>
            <span class="menu-danger">🗑 删除</span>
          </el-dropdown-item>
        </el-dropdown-menu>
      </template>
    </el-dropdown>
  </div>
</template>

<script setup>
import { computed } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Folder, Document, MoreFilled } from '@element-plus/icons-vue'
import api from '@/api'
import { useStore } from '@/store'
import { useRouter } from 'vue-router'

const props = defineProps({
  item: { type: Object, required: true },
  parentId: { type: Number, default: null }
})
const emit = defineEmits(['click', 'share', 'deleted', 'renamed'])

const router = useRouter()
const store = useStore()

const isFolder = computed(() => props.item.format === 0 || props.item.format === 'FOLDER' || props.item.format === '0')
const iconName = computed(() => isFolder.value ? Folder : Document)

const onCardClick = () => {
  if (isFolder.value) {
    emit('click', props.item)
  } else {
    // 文件：跳转到编辑器
    router.push(`/${props.item.id}`)
  }
}

const onMenuCommand = async (cmd) => {
  if (cmd === 'rename') {
    try {
      const { value } = await ElMessageBox.prompt('新名称', '重命名', {
        inputValue: props.item.name,
        confirmButtonText: '确认',
        cancelButtonText: '取消'
      })
      if (value && value !== props.item.name) {
        await api.updateFolder({ id: props.item.id, name: value })
        ElMessage.success('重命名成功')
        emit('renamed', { id: props.item.id, name: value })
      }
    } catch (e) { /* 取消 */ }
  } else if (cmd === 'share') {
    emit('share', props.item)
  } else if (cmd === 'delete') {
    try {
      await ElMessageBox.confirm(
        `确认删除「${props.item.name}」？删除后可在回收站恢复。`,
        '删除', { type: 'warning', confirmButtonText: '确认', cancelButtonText: '取消' }
      )
      await api.deleteFolder({ id: props.item.id })
      ElMessage.success('删除成功')
      emit('deleted', props.item.id)
    } catch (e) { /* 取消 */ }
  } else if (cmd === 'copyOrMove') {
    ElMessage.info('复制/移动对话框（v0.7.1 实现）')
  }
}
</script>

<style scoped>
.card {
  position: relative;
  background: #fff;
  border: 1px solid #e5e7eb;
  border-radius: 8px;
  padding: 16px;
  height: 88px;
  display: flex;
  align-items: center;
  gap: 12px;
  cursor: pointer;
  transition: all 0.15s;
}
.card:hover {
  border-color: #3370ff;
  box-shadow: 0 2px 8px rgba(51, 112, 255, 0.08);
  transform: translateY(-1px);
}
.cardIcon {
  width: 36px;
  height: 36px;
  border-radius: 6px;
  display: flex;
  align-items: center;
  justify-content: center;
  flex-shrink: 0;
}
.cardIcon.folder { background: #e8f1ff; color: #3370ff; }
.cardIcon.file { background: #f2f3f5; color: #86909c; }
.cardName {
  font-size: 14px;
  color: #1f2329;
  font-weight: 500;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  flex: 1;
}
.cardMenu {
  color: #86909c;
  padding: 4px 6px;
  border-radius: 4px;
  cursor: pointer;
  opacity: 0.4;
}
.card:hover .cardMenu { opacity: 1; }
.cardMenu:hover { background: #f2f3f5; }
.menu-highlight { color: #3370ff; font-weight: 500; }
.menu-danger { color: #f53f3f; }
</style>
