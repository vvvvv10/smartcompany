/**
 * 入口——从 workbench-android 的 WorkbenchNavHost.kt 移植。
 *
 * 只做登录态分流，不引入路由栈：这个 App 的顶层结构是
 * 「登录页 ↔ 一个带底部 Tab 的主页」，Tab 之间是平级切换而不是压栈，
 * 用状态分流比造路由表更直白，也省掉路由参数对不上的那类问题。
 */

import React, { useEffect } from 'react'
import { ActivityIndicator, StyleSheet, View } from 'react-native'
import { StatusBar } from 'expo-status-bar'
import { AuthProvider, useAuth } from './src/feature/auth/AuthContext'
import { LoginScreen } from './src/feature/auth/LoginScreen'
import { MainScaffold } from './src/feature/main/MainScaffold'
import { loadNow } from './src/store/servers'

// 冷启动读一次服务器配置（只发生一次，之后走内存）
void loadNow()

function Root() {
    const { status } = useAuth()

    if (status === 'unknown') {
        // 冷启动读本地 token，读完才有结论
        return (
            <View style={styles.center}>
                <ActivityIndicator size="large" color="#0F766E" />
            </View>
        )
    }

    return status === 'loggedIn' ? <MainScaffold /> : <LoginScreen />
}

export default function App() {
    return (
        <AuthProvider>
            <StatusBar style="dark" />
            <Root />
        </AuthProvider>
    )
}

const styles = StyleSheet.create({
    center: {
        flex: 1,
        alignItems: 'center',
        justifyContent: 'center',
        backgroundColor: '#fff'
    }
})
