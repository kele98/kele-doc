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
      <div class="sectionHeader" style="margin-bottom: 4px;">
        <span class="text" style="font-size: 12px; color: #909399; padding-left: 24px;">文件</span>
      </div>
      <div
        class="menuItem"
        :class="{ isActive: isRootList }"
        @click="toMyFiles"
      >
        <span class="iconfont icon-wenjianjia1"></span>
        <span class="text">我的文件</span>
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
import { ref, computed } from 'vue'
import config from '@/config'
import useFileHandle from '@/hooks/useFileHandle'
import { useStore } from '@/store'
import { useRouter, useRoute } from 'vue-router'
import { ElMessage } from 'element-plus'
import { UserFilled, User } from '@element-plus/icons-vue'

const store = useStore()
const route = useRoute()
const router = useRouter()

const isAdmin = computed(() => store.userInfo && store.userInfo.role === 'ADMIN')
const isRootList = computed(() => route.name === 'List' && !store.currentFolder)

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
    createAndOpenNewFile(config.createTypeList[0].value)
  } else {
    createTypeListVisible.value = !createTypeListVisible.value
  }
}

// 回到我的文件（根目录）
const toMyFiles = () => {
  store.setCurrentFolderPath([])
  store.setCurrentFolder(null)
  if (route.name !== 'List') {
    router.push({ name: 'List' })
  }
}

// 进入收藏页面
const toCollect = () => {
  store.setCurrentFolderPath([])
  store.setCurrentFolder(null)
  router.push({ name: 'Collect' })
}

// 进入回收站页面
const toRecycle = () => {
  store.setCurrentFolderPath([])
  store.setCurrentFolder(null)
  router.push({ name: 'Recycle' })
}

// 进入文件全景图页面
const toPanorama = () => {
  store.setCurrentFolderPath([])
  store.setCurrentFolder(null)
  router.push({ name: 'Panorama' })
}

const toGroupManage = () => {
  store.setCurrentFolderPath([])
  store.setCurrentFolder(null)
  router.push({ name: 'GroupManage' })
}

const toUserManage = () => {
  store.setCurrentFolderPath([])
  store.setCurrentFolder(null)
  router.push({ name: 'UserManage' })
}
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
  }
}
</style>
