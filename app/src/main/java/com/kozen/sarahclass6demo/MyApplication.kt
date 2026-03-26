package com.kozen.sarahclass6demo

import android.app.Application
import android.util.Log
import com.kozen.component.engine.InitListener
import com.kozen.component.secondaryScreen.ISecondaryScreen
import com.kozen.component_client.ComponentEngine
import com.kozen.terminalmanager.InitCallBack
import com.kozen.terminalmanager.TerminalManager
import com.kozen.terminalmanager.device.IDeviceManager

class MyApplication : Application() {

    companion object {
        lateinit var instance: MyApplication
        var deviceManager: IDeviceManager? = null
        var secondaryScreenManager: ISecondaryScreen? = null

        var terminalInitSuccess = false
        var componentInitSuccess = false
    }

    override fun onCreate() {
        super.onCreate()
        instance = this

        TerminalManager.init(this, object : InitCallBack {
            override fun onInitResult(code: Int, msg: String?) {
                if (code == 0) {
                    terminalInitSuccess = true
                    deviceManager = TerminalManager.deviceManager
                }
            }
        })

        ComponentEngine.init(this, object : InitListener {
            override fun onResult(code: Int, msg: String?) {
                if (code == 0) {
                    componentInitSuccess = true
                    secondaryScreenManager = ComponentEngine.getSecondaryScreenManager()
                }
            }
        })
    }
}