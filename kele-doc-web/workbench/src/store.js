import { createPinia, defineStore } from 'pinia'
import api from './api'
import config from '@/config'

export const pinia = createPinia()

export const useStore = defineStore('main', {
  state: () => {
    return {
      // 用户信息
      userInfo: null,
      // 用户配置
      userConfig: null,
      // 当前所在文件夹
      currentFolder: null,
      // 当前文件夹路径
      currentFolderPath: [],
      // 当前被拖拽的文件或文件夹
      currentDragData: null,
      // v0.7 文件夹权限缓存：folderId -> { level, source, isOwner }
      folderPermissionMap: {},
      // v0.7 当前用户所属组（ShareDialog 用）
      myGroups: null
    }
  },
  actions: {
    // 获取用户信息
    async getUserInfo() {
      if (this.userInfo) {
        return this.userInfo
      }
      const { data } = await api.getUserInfo()
      this.userInfo = data
      return data
    },

    // 获取用户配置
    async getUserConfig() {
      if (this.userConfig) {
        return this.userConfig
      }
      const { data } = await api.getUserConfig({
        configType: config.configType
      })
      this.userConfig = data ? JSON.parse(data) : {}
      return this.userConfig
    },

    // 更新用户配置
    async updateUserConfig(data) {
      const newConfig = {
        ...this.userConfig,
        ...data
      }
      await api.updateUserConfig({
        configType: config.configType,
        configContent: JSON.stringify(newConfig)
      })
      this.userConfig = newConfig
    },

    // 设置当前所在文件夹
    setCurrentFolder(data) {
      if (this.currentFolder && data && data.id === this.currentFolder.id)
        return
      this.currentFolder = data
    },

    // 设置当前所在文件夹路径
    setCurrentFolderPath(data) {
      this.currentFolderPath = data
    },

    // 设置当前被拖拽的数据
    setCurrentDragData(data) {
      this.currentDragData = data
    },

    // v0.7：加载文件夹权限（从 ACL 列表推断当前用户的实际权限等级）
    // owner 拥有 MANAGE；ACL entries 中包含当前用户或所属的组，取最高级别
    // v0.7 修复：ORG-public 条目原代码漏判——现优先看 ORG
    async loadFolderPermission(folderId) {
      const { data } = await api.getFolderAcl(folderId)
      const me = this.userInfo && this.userInfo.id
      const myGroupIds = (this.myGroups || []).map(g => g.id)
      let level = 'NONE'
      let source = 'NONE'
      if (data.ownerId === me) {
        level = 'MANAGE'
        source = 'OWNER'
      } else {
        const rank = { READ: 1, WRITE: 2, MANAGE: 3 }
        // 先看 ORG-public（所有登录用户都命中）
        if (data.isOrgPublic === true) {
          const orgPerm = data.orgPublicPermission || 'READ'
          if ((rank[orgPerm] || 0) > (rank[level] || 0)) {
            level = orgPerm
            source = 'ORG'
          }
        }
        // 再看 USER / GROUP 条目
        if (data.entries) {
          for (const e of data.entries) {
            const match = (e.principalType === 'USER' && e.principalId === me)
              || (e.principalType === 'GROUP' && myGroupIds.includes(e.principalId))
            if (match && (rank[e.permission] || 0) > (rank[level] || 0)) {
              level = e.permission
              source = e.principalType === 'USER' ? 'DIRECT' : 'GROUP'
            }
          }
        }
      }
      this.folderPermissionMap[folderId] = { level, source, isOwner: data.ownerId === me }
      return this.folderPermissionMap[folderId]
    },

    // v0.7：加载当前用户所属的组（ShareDialog 选择组时下拉用）
    async loadMyGroups() {
      const { data } = await api.listMyGroups()
      this.myGroups = data || []
      return this.myGroups
    }
  }
})
