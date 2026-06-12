<template>
  <div class="loginContainer">
    <div class="infoBox">
      <div class="logoBox">
        <span class="logo">
          <img :src="config.logo" alt="" />
        </span>
        <span class="title">{{ config.name }}</span>
      </div>
      <img class="illustration" src="@/assets/img/login.svg" alt="" />
    </div>
    <div class="formBox">
      <div class="formWrap">
        <div class="title">欢迎使用{{ config.name }}</div>
        <!-- 登录 -->
        <el-form ref="loginFormRef" :model="loginForm" :rules="loginRules">
          <el-form-item label="" prop="account">
            <el-input
              v-model="loginForm.account"
              placeholder="请输入用户名"
              style="height: 50px"
            />
          </el-form-item>
          <el-form-item label="" prop="password">
            <el-input
              v-model="loginForm.password"
              placeholder="请输入密码"
              style="height: 50px"
              show-password
            />
          </el-form-item>
        </el-form>
        <el-button
          type="primary"
          style="width: 100%; height: 50px"
          @click="confirm"
          >登录</el-button
        >
      </div>
    </div>
  </div>
</template>

<script setup>
import { reactive, ref } from 'vue'
import config from '@/config'
import api from '@/api'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import {
  validateAccount,
  validatePassword
} from '@/utils'
import { useStore } from '../../store'

const store = useStore()
const router = useRouter()

const init = async () => {
  try {
    // 获取到用户信息则视为已登陆，跳转到工作台页面
    const userInfo = await store.getUserInfo()
    if (userInfo) {
      router.replace('/')
      return
    }
  } catch (error) {
    console.log(error)
  }
}

init()

const loginFormRef = ref(null)
const loginForm = reactive({
  account: '',
  password: ''
})
const loginRules = reactive({
  account: [
    { required: true, message: '请输入用户名', trigger: 'blur' },
    { validator: validateAccount, trigger: 'blur' }
  ],
  password: [
    { required: true, message: '请输入密码', trigger: 'blur' },
    { validator: validatePassword, trigger: 'blur' }
  ]
})

const login = async () => {
  await api.login({
    account: loginForm.account.trim(),
    password: loginForm.password.trim()
  })
  ElMessage.success('登录成功')
  router.push({
    name: 'List'
  })
}

const confirm = () => {
  loginFormRef.value.validate(async valid => {
    if (valid) {
      try {
        await login()
      } catch (error) {
        console.log(error)
      }
    }
  })
}
</script>

<style lang="less" scoped>
.loginContainer {
  display: flex;
  width: 100%;
  height: 100%;
  overflow: hidden;

  .infoBox {
    width: 30%;
    height: 100%;
    display: flex;
    justify-content: center;
    align-items: center;
    flex-direction: column;
    background-color: #eef1f3;

    .logoBox {
      display: flex;
      align-items: center;
      height: 100px;
      justify-content: center;
      margin-bottom: 20px;

      .logo {
        width: 60px;
        margin-right: 10px;

        img {
          width: 100%;
        }
      }

      .title {
        font-size: 40px;
        font-weight: bold;
        color: var(--theme-color);
      }
    }

    .illustration {
      width: 70%;
    }
  }

  .formBox {
    width: 70%;
    height: 100%;
    display: flex;
    justify-content: center;
    align-items: center;

    .formWrap {
      width: 400px;

      /deep/ .el-form-item__error {
        white-space: nowrap;
      }

      .title {
        font-size: 30px;
        color: #212930;
        margin-bottom: 40px;
      }
    }
  }
}
</style>
