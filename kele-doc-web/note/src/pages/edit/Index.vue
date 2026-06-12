<template>
  <div class="editContainer">
    <div class="header">
      <div class="left">
        <div class="backBtn" @click="onBack">
          <el-icon><ArrowLeft /></el-icon>
        </div>
      </div>
      <div class="center"></div>
      <div class="right">
        <div class="saveBox">
          <span class="saveTip">
            <el-icon
              :class="[saveTipIconIsLoading ? 'is-loading' : '']"
              color="#333"
              ><component :is="saveTipIcon"></component
            ></el-icon>
            <span class="text">{{ saveTip }}</span>
          </span>
          <el-button @click="save">保存</el-button>
          <el-button @click="saveCover">生成封面</el-button>
        </div>
      </div>
    </div>
    <div class="editorBox">
      <div class="toolbarBox"></div>
      <div class="editorWrap">
        <div class="editorContent" ref="editorContentRef">
          <div
            ref="titleRef"
            class="titleContainer"
            :contenteditable="true"
            @blur="onFileNameBlur"
            @paste="onTitleInputPaste"
          ></div>
          <div
            class="editorContainer"
            ref="editorContainerRef"
            @click="onEditorContainerClick"
          ></div>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup>
import { useStore } from '../../store'
import { useRoute } from 'vue-router'
import { computed, onMounted, ref, watch, nextTick } from 'vue'
import {
  ArrowLeft,
  CircleCloseFilled,
  CircleCheckFilled,
  WarningFilled,
  Loading
} from '@element-plus/icons-vue'
import EditorJS from '@editorjs/editorjs'
import DragDrop from 'editorjs-drag-drop'
import Undo from 'editorjs-undo'
import { i18nConfig, toolsConfig } from '@/constant'
import { ElMessage } from 'element-plus'
import { toPng } from 'html-to-image'
import api from '@/api'

const store = useStore()
const route = useRoute()

const onBack = () => {
  if (process.env.NODE_ENV === 'production') {
    location.href = '/'
  } else {
    location.href = 'http://' + location.hostname + ':9090'
  }
}

// 文件名
const fileName = ref('')
const titleRef = ref(null)
const onTitleInputPaste = e => {
  e.preventDefault()
  const paste = (e.clipboardData || window.clipboardData).getData('text')
  const selection = window.getSelection()
  if (!selection.rangeCount) return
  selection.deleteFromDocument()
  selection.getRangeAt(0).insertNode(document.createTextNode(paste))
  selection.collapseToEnd()
  fileName.value = e.target.textContent
}
const onFileNameBlur = () => {
  const text = (titleRef.value?.textContent || '').trim()
  if (text) {
    fileName.value = text
    store.updateFileData({ name: text })
  }
}
watch(
  () => store.fileData,
  val => {
    if (val && val.name) {
      fileName.value = val.name
      nextTick(() => {
        if (titleRef.value) titleRef.value.textContent = val.name
      })
    }
  },
  { deep: true, immediate: true }
)

// 编辑器
let editor = null
const editorContainerRef = ref(null)
const initEditor = initialData => {
  editor = new EditorJS({
    holder: editorContainerRef.value,
    data: initialData,
    minHeight: 50,
    // readOnly: true,
    // autofocus: true,
    tools: toolsConfig,
    i18n: i18nConfig,
    onReady: () => {
      const undo = new Undo({ editor })
      undo.initialize(initialData)
      new DragDrop(editor)
    },
    onChange: (api, event) => {
      onChange()
    }
  })
}
const onEditorContainerClick = e => {
  if (e.target.classList.contains('editorContainer')) {
    editor.focus(true)
  }
}

// 保存
const autoSaveTimer = ref(null)
const saveTipIcon = computed(() => {
  switch (store.autoSaveStatus) {
    case 'wait':
      return WarningFilled
    case 'ing':
      return Loading
    case 'fail':
      return CircleCloseFilled
    case 'success':
      return CircleCheckFilled
    default:
      return ''
  }
})
const saveTipIconIsLoading = computed(() => {
  return store.autoSaveStatus === 'ing'
})
const saveTip = computed(() => {
  switch (store.autoSaveStatus) {
    case 'wait':
      return '有操作尚未保存'
    case 'ing':
      return '正在保存...'
    case 'fail':
      return '保存失败'
    case 'success':
      return '保存成功'
    default:
      return ''
  }
})
const save = async () => {
  try {
    const content = await editor.save()
    store.updateFileData({
      content: JSON.stringify(content)
    })
  } catch (error) {
    store.setAutoSaveStatus('fail')
  }
}
const onChange = () => {
  store.setAutoSaveStatus('wait')
  clearTimeout(autoSaveTimer.value)
  autoSaveTimer.value = setTimeout(() => {
    save()
  }, 3000)
}

// 文件内容
const getFileData = async () => {
  try {
    const res = await store.getFileData(route.params.id)
    initEditor(res.content ? JSON.parse(res.content) : {})
  } catch (error) {
    console.log(error)
  }
}

window.onbeforeunload = function () {
  if (store.autoSaveStatus !== 'success') {
    return '存在未保存的数据'
  }
}

onMounted(() => {
  getFileData()
})
const editorContentRef = ref(null)
const saveCover = async () => {
  const overlay = document.createElement('div')
  overlay.style.cssText = 'position:fixed;top:0;left:0;width:100vw;height:100vh;background:rgba(255,255,255,0.7);z-index:9999;display:flex;align-items:center;justify-content:center;font-size:16px;color:#333;'
  overlay.textContent = '正在生成封面...'
  document.body.appendChild(overlay)
  try {
    const el = editorContentRef.value
    const prevMargin = el.style.margin
    el.style.margin = '0'
    const imgData = await toPng(el, {
      quality: 0.9,
      backgroundColor: '#ffffff',
      width: el.scrollWidth,
      height: el.scrollHeight
    })
    el.style.margin = prevMargin
    // 等 margin 恢复后 DOM 稳定，再关遮罩
    await new Promise(r => setTimeout(r, 300))
    document.body.removeChild(overlay)
    if (!imgData.startsWith('data:image/')) {
      ElMessage.warning('截图失败，请重试')
      return
    }
    const { data } = await api.uploadImg({ imgData })
    await store.updateFileData({ img: data })
    ElMessage.success('封面生成成功')
  } catch (error) {
    console.log(error)
    await new Promise(r => setTimeout(r, 300))
    if (document.body.contains(overlay)) document.body.removeChild(overlay)
    ElMessage.warning('封面生成失败')
  }
}
</script>

<style lang="less" scoped>
.editContainer {
  width: 100%;
  height: 100%;
  display: flex;
  overflow: hidden;
  flex-direction: column;

  .header {
    width: 100%;
    height: 50px;
    overflow: hidden;
    flex-shrink: 0;
    display: flex;
    align-items: center;
    justify-content: space-between;
    padding: 0 12px;

    .left {
      flex: 1;

      .backBtn {
        width: 30px;
        height: 40px;
        display: flex;
        align-items: center;
        justify-content: center;
        font-size: 30px;
        cursor: pointer;
        border-radius: 5px;
        margin-right: 8px;

        &:hover {
          background-color: #f5f5f5;
        }
      }
    }

    .center {
      flex: 1;
      display: flex;
      justify-content: center;

      .fileNameInput {
        width: 300px;
      }
    }

    .right {
      flex: 1;
      display: flex;
      align-items: center;
      margin-top: 5px;
      justify-content: flex-end;

      .saveBox {
        display: flex;
        align-items: center;

        .saveTip {
          display: flex;
          align-items: center;
          margin-right: 4px;
          color: #333;

          .text {
            margin-left: 4px;
            font-size: 12px;
          }
        }
      }
    }
  }

  .editorBox {
    width: 100%;
    height: 100%;
    overflow: hidden;
    display: flex;
    flex-direction: column;
    background-color: rgb(245, 245, 245);

    .toolbarBox {
      width: 100%;
      flex-shrink: 0;
      display: flex;
      justify-content: center;
      border-bottom: 1px solid #e8e8e8;
      background-color: #fff;
    }

    .editorWrap {
      height: 100%;
      width: 100%;
      overflow-y: auto;
      padding: 20px 0;

      .editorContent {
        margin: 0 auto;
        width: 800px;
        background-color: #fff;
        border: 1px solid #e8e8e8;
        box-shadow: 0 2px 10px rgb(0 0 0 / 12%);

        .titleContainer {
          font-size: 30px;
          outline: none;
          text-align: center;
          margin-bottom: 12px;
          margin: 0 50px;
          height: 80px;
          line-height: 80px;
        }

        .editorContainer {
          min-height: 900px;
        }
      }
    }
  }
}
</style>
