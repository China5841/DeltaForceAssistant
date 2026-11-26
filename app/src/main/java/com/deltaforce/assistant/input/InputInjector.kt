package com.deltaforce.assistant.input

import android.util.Log

class InputInjector {
    companion object {
        private const val TAG = "InputInjector"
        private var sLoaded = false

        fun load() {
            if (!sLoaded) {
                System.loadLibrary("input_injector")
                sLoaded = true
            }
        }
    }

    external fun init(): Boolean
    external fun destroy()
    external fun injectMove(deltaX: Int, deltaY: Int)
    external fun injectClick(button: Int)  // 0=left, 1=right, 2=middle
    external fun injectTouch(x: Int, y: Int, pressure: Int, down: Boolean)
    external fun isAvailable(): Boolean

    fun initialize(): Boolean {
        load()
        return init()
    }

    fun move(deltaX: Float, deltaY: Float) {
        injectMove(deltaX.toInt(), deltaY.toInt())
    }

    fun click(button: Int = 0) {
        injectClick(button)
    }

    fun touchDown(x: Int, y: Int) {
        injectTouch(x, y, 255, true)
    }

    fun touchUp(x: Int, y: Int) {
        injectTouch(x, y, 0, false)
    }

    fun touchMove(x: Int, y: Int) {
        injectTouch(x, y, 255, true)
    }
}