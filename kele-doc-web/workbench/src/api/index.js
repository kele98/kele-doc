import http from './httpInstance'
import getMockData from './mock'
import config from '@/config'

const isDev = process.env.NODE_ENV !== 'production'
const useMock = import.meta.env.MODE === 'mock'

export default {
  // 上传文件
  uploadFiles(data) {
    if (useMock) {
      return getMockData('uploadFiles', data)
    }
    return http.post('/uploadFiles', data)
  },

  // 退出登录---------------------------
  logout() {
    if (useMock) {
      return getMockData('logout')
    }
    return http.get('/logout')
  },

  // 登录
  login(data) {
    if (useMock) {
      return getMockData('login', data)
    }
    return http.post('/login', data)
  },

  // 注册
  register(data) {
    if (useMock) {
      return getMockData('register', data)
    }
    return http.post('/register', data)
  },

  // 获取用户信息
  getUserInfo() {
    if (useMock) {
      return getMockData('getUserInfo')
    }
    return http.get('/getUserInfo')
  },

  // 更新用户信息
  updateUserInfo(data) {
    if (useMock) {
      return getMockData('updateUserInfo', data)
    }
    return http.post('/updateUserInfo', data)
  },

  // 修改密码
  changePassword(data) {
    if (useMock) {
      return getMockData('changePassword', data)
    }
    return http.post('/changePassword', data)
  },

  // 获取用户配置
  getUserConfig(params) {
    if (useMock) {
      return getMockData('getUserConfig', params)
    }
    return http.get('/getUserConfig', {
      params
    })
  },

  // 更新用户配置
  updateUserConfig(data) {
    if (useMock) {
      return getMockData('updateUserConfig', data)
    }
    return http.post('/updateUserConfig', data)
  },

  // 获取文件夹树，异步树---------------------------
  async getFolderTree(params) {
    if (useMock) {
      return getMockData('getFolderTree', params)
    }
    const { data } = await http.get('/getFolderTree', {
      params
    })
    // 如果没有根节点，那么就创建一个
    if (params.folderId === '' && data.length <= 0) {
      const res = await this.createFolder({
        name: config.rootFolderName,
        parentFolderId: ''
      })
      return {
        data: [
          {
            ...res.data,
            leaf: true
          }
        ]
      }
    } else {
      return {
        data
      }
    }
  },

  // 获取文件夹树（同步树）
  async getAllFolderTree(params) {
    if (useMock) {
      return getMockData('getAllFolderTree', params)
    }
    const { data } = await http.get('/getAllFolderTree', {
      params
    })
    // 如果没有根节点，那么就创建一个
    if (data.length <= 0) {
      const res = await this.createFolder({
        name: config.rootFolderName,
        parentFolderId: ''
      })
      return {
        data: [
          {
            ...res.data,
            leaf: true,
            type: 'folder',
            children: []
          }
        ]
      }
    } else {
      return {
        data
      }
    }
  },

  // v0.7 BUG C fix：列出当前登录用户"可访问的 folder"全集（owner OR 通过 ACL 授权），
  // 不限 parent_id。专门给 Sidebar 的 "分享给我的" section 用。
  // 响应里 isOwner=true → 我的 folder，isOwner=false → 别人授权给我的。
  async getAccessibleFolders() {
    if (useMock) {
      return getMockData('getAccessibleFolders')
    }
    const { data } = await http.get('/folders/accessible')
    return { data }
  },

  // 获取某个文件夹的路径
  getFolderPath(params) {
    if (useMock) {
      return getMockData('getFolderPath', params)
    }
    return http.get('/getFolderPath', {
      params
    })
  },

  // 获取某个文件夹下的文件夹列表和文件列表
  getFolderAndFileList(params) {
    if (useMock) {
      return getMockData('getFolderAndFileList', params)
    }
    return http.get('/getFolderAndFileList', {
      params
    })
  },

  // 搜索文件夹和文件
  searchFolderAndFile(data) {
    if (useMock) {
      return getMockData('searchFolderAndFile', data)
    }
    return http.post('/searchFolderAndFile', data)
  },

  // 创建新文件--------------------
  createFile(data) {
    if (useMock) {
      return getMockData('createFile', data)
    }
    return http.post('/createFile', data)
  },

  // 更新文件
  updateFile(data) {
    if (useMock) {
      return getMockData('updateFile', data)
    }
    return http.post('/updateFile', data)
  },

  // 移动文件
  moveFile(data) {
    if (useMock) {
      return getMockData('moveFile', data)
    }
    return http.post('/moveFile', data)
  },

  // 复制文件
  copyFile(data) {
    if (useMock) {
      return getMockData('copyFile', data)
    }
    return http.post('/copyFile', data)
  },

  // 删除文件
  deleteFile(data) {
    if (useMock) {
      return getMockData('deleteFile', data)
    }
    return http.post('/deleteFile', data)
  },

  // 新建文件夹--------------------
  createFolder(data) {
    if (useMock) {
      return getMockData('createFolder', data)
    }
    return http.post('/createFolder', data)
  },

  // 更新文件夹
  updateFolder(data) {
    if (useMock) {
      return getMockData('updateFolder', data)
    }
    return http.post('/updateFolder', data)
  },

  // 删除文件夹
  deleteFolder(data) {
    if (useMock) {
      return getMockData('deleteFolder', data)
    }
    return http.post('/deleteFolder', data)
  },

  // 移动文件夹
  moveFolder(data) {
    if (useMock) {
      return getMockData('moveFolder', data)
    }
    return http.post('/moveFolder', data)
  },

  // 复制文件夹
  copyFolder(data) {
    if (useMock) {
      return getMockData('copyFolder', data)
    }
    return http.post('/copyFolder', data)
  },

  // 获取收藏的文件列表---------------------
  getCollectFileList(params) {
    if (useMock) {
      return getMockData('getCollectFileList', params)
    }
    return http.get('/getCollectFileList', {
      params
    })
  },

  // 取消收藏
  cancelCollect(data) {
    if (useMock) {
      return getMockData('cancelCollect', data)
    }
    return http.post('/cancelCollect', data)
  },

  // 收藏文件
  collect(data) {
    if (useMock) {
      return getMockData('collect', data)
    }
    return http.post('/collect', data)
  },

  // 获取回收站列表-------------------------------------
  getRecycleFolderAndFileList(params) {
    if (useMock) {
      return getMockData('getRecycleFolderAndFileList', params)
    }
    return http.get('/getRecycleFolderAndFileList', {
      params
    })
  },

  // 从回收站恢复文件夹或文件
  restore(data) {
    if (useMock) {
      return getMockData('restore', data)
    }
    return http.post('/restore', data)
  },

  // 彻底删除文件夹或文件
  completelyDelete(data) {
    if (useMock) {
      return getMockData('completelyDelete', data)
    }
    return http.post('/completelyDelete', data)
  },

  // 清空回收站
  emptyRecycle() {
    if (useMock) {
      return getMockData('emptyRecycle')
    }
    return http.post('/emptyRecycle')
  },

  // ====== v0.7 共享/权限/群组相关 API ======

  // 获取文件夹 ACL 列表（spec §4.2）
  getFolderAcl(folderId) {
    return http.get(`/doc/folders/${folderId}/acl`)
  },

  // 授予文件夹 ACL（spec §4.2；replace=true 全量替换）
  grantFolderAcl(folderId, entries, replace = false) {
    return http.post(`/doc/folders/${folderId}/acl`, { entries, replace })
  },

  // 撤销文件夹 ACL
  revokeFolderAcl(folderId, aclId) {
    return http.delete(`/doc/folders/${folderId}/acl/${aclId}`)
  },

  // 更新文件夹 ACL 权限级别
  updateFolderAcl(folderId, aclId, permission) {
    return http.put(`/doc/folders/${folderId}/acl/${aclId}`, { permission })
  },

  // 转移文件夹所有权
  transferOwner(folderId, newOwnerId) {
    return http.put(`/doc/folders/${folderId}/owner`, { newOwnerId })
  },

  // 搜索用户（ShareDialog 用）
  searchUsers(params) {
    return http.get('/users/search', { params })
  },

  // 列出当前用户所属的组（ShareDialog 用）
  listMyGroups() {
    return http.get('/admin/groups/my')
  },

  // ====== 群组管理 API（仅管理员） ======

  listGroups(params) {
    return http.get('/admin/groups', { params })
  },

  createGroup(data) {
    return http.post('/admin/groups', data)
  },

  updateGroup(id, data) {
    return http.put(`/admin/groups/${id}`, data)
  },

  dissolveGroup(id) {
    return http.delete(`/admin/groups/${id}`)
  },

  restoreGroup(id) {
    return http.post(`/admin/groups/${id}/restore`)
  },

  listGroupMembers(groupId) {
    return http.get(`/admin/groups/${groupId}/members`)
  },

  addGroupMembers(groupId, userIds) {
    return http.post(`/admin/groups/${groupId}/members`, { userIds })
  },

  removeGroupMember(groupId, userId) {
    return http.delete(`/admin/groups/${groupId}/members/${userId}`)
  },

  // ====== 用户管理 API（仅管理员） ======

  adminUserList(params) {
    return http.get('/admin/users', { params })
  },

  adminUpdateUserStatus(id, status) {
    return http.post(`/admin/users/${id}/status`, { status })
  },

  adminUpdateUserRole(id, role) {
    return http.post(`/admin/users/${id}/role`, { role })
  }
}
