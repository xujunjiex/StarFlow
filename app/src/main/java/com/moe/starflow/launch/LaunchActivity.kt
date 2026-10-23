/*
 * Copyright (C) 2024 murangogo
 *
 * This library is free software; you can redistribute it and/or modify it under
 * the terms of the GNU Lesser General Public License as published by the Free
 * Software Foundation; either version 3 of the License, or (at your option)
 * any later version.
 *
 * This library is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS
 * FOR A PARTICULAR PURPOSE. See the GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License along
 * with this library; if not, write to the Free Software Foundation, Inc.,
 * 59 Temple Place, Suite 330, Boston, MA 02111-1307 USA
 */

package com.moe.starflow.launch
import com.moe.starflow.translate.widget.*

import android.content.Intent
import android.os.Bundle
import android.widget.ImageView
import android.widget.Toast
import androidx.lifecycle.lifecycleScope
import com.moe.starflow.BaseActivity
import com.moe.starflow.MainActivity
import com.moe.starflow.R
import com.moe.starflow.utils.AppPathManager
import com.moe.starflow.utils.CustomPreference
import com.moe.starflow.utils.UtilTools
import com.moe.starflow.utils.LogCollector
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

class LaunchActivity : BaseActivity() {
    private lateinit var prefs: CustomPreference

    // 使用lifecycleScope替代MainScope
    private val activityScope = lifecycleScope

    override fun onCreate(savedInstanceState: Bundle?) {
        com.moe.starflow.utils.StartupOrientation.apply(this)
        super.onCreate(savedInstanceState)

        prefs = CustomPreference.getInstance(this)

        // 初始化路径管理类
        AppPathManager.init(this)
        // 初始化工具类
        UtilTools.init(this)

        setContentView(R.layout.activity_launch)

        val logoImageView = findViewById<ImageView>(R.id.logo_name)

        if (shouldShowChineseLogo()) {
            logoImageView.setImageResource(R.drawable.logo_design)
        } else {
            logoImageView.setImageResource(R.drawable.logo_design_en)
        }

        applySystemBarsPadding(findViewById(R.id.launch_layout), true, true)

        activityScope.launch {
            try {
                delay(1500)

                if (prefs.getBoolean("Is_First_Run", true)) {
                    prefs.setBoolean("Is_First_Run", false)
                    startActivity(Intent(this@LaunchActivity, FirstLaunchPage::class.java))
                } else {
                    startActivity(Intent(this@LaunchActivity, MainActivity::class.java))
                }

                finish()
            } catch (e: CancellationException) {
                // 转屏重建会取消旧 Activity 的延迟跳转，由新实例接管启动。
                throw e
            } catch (e: Exception) {
                LogCollector.e("LaunchActivity", "Initialization failed", e)
                Toast.makeText(
                    this@LaunchActivity,
                    getString(R.string.init_error, e),
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    fun shouldShowChineseLogo(): Boolean {
        val locale = Locale.getDefault()
        return locale.language == "zh"
    }

    override fun onDestroy() {
        super.onDestroy()
        activityScope.cancel()
    }
}
