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

package com.moe.starflow.me.settings
import com.moe.starflow.translate.widget.*
import com.moe.starflow.translate.autotranslate.*
import com.moe.starflow.translate.screenshot.*

import android.annotation.SuppressLint
import android.content.pm.ActivityInfo
import androidx.appcompat.app.AppCompatActivity
import android.os.Bundle
import android.widget.Toast
import com.moe.starflow.BaseActivity
import com.moe.starflow.databinding.ActivitySettingPageBinding
import com.moe.starflow.me.about.Developer
import com.moe.starflow.me.about.FAQPage
import com.moe.starflow.me.model.ModelManagementFragment
import com.moe.starflow.me.apiconfig.APIConfig

class SettingPageActivity : BaseActivity() {

    companion object {
        const val EXTRA_FRAGMENT_TYPE = "fragment_type"
        const val TYPE_FRAGMENT_TRANSLATE_MODE = 1
        const val TYPE_FRAGMENT_API_CONFIG = 2
        const val TYPE_FRAGMENT_PERSONALIZATION = 3
        const val TYPE_FRAGMENT_FAQ = 5
        const val TYPE_FRAGMENT_DEVELOPER = 7
        const val TYPE_FRAGMENT_MODEL_MANAGEMENT = 8

        /**
         * 打开模型管理页时**直接落在「超分」Tab**（2026-10）。
         * 个性化设置的「超分模型管理」、阅读器面板的超分入口都带这个 extra 过来。
         */
        const val EXTRA_MODEL_SHOW_SR = "model_show_sr"
    }

    private lateinit var binding: ActivitySettingPageBinding
    @SuppressLint("CommitTransaction")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT) //锁定竖屏
        binding = ActivitySettingPageBinding.inflate(layoutInflater)
        setContentView(binding.root)

        applySystemBarsPadding(binding.fragmentContainerView, true, true)

        when(intent.getIntExtra(EXTRA_FRAGMENT_TYPE,0)){
            TYPE_FRAGMENT_TRANSLATE_MODE->supportFragmentManager.beginTransaction().replace(binding.fragmentContainerView.id,
                TranslationMode()
            ).commit()
            TYPE_FRAGMENT_API_CONFIG->supportFragmentManager.beginTransaction().replace(binding.fragmentContainerView.id,
                APIConfig()
            ).commit()
            TYPE_FRAGMENT_PERSONALIZATION->supportFragmentManager.beginTransaction().replace(binding.fragmentContainerView.id,
                PersonalizationConfig()
            ).commit()
            TYPE_FRAGMENT_FAQ->supportFragmentManager.beginTransaction().replace(binding.fragmentContainerView.id,
                FAQPage()
            ).commit()
            TYPE_FRAGMENT_DEVELOPER->supportFragmentManager.beginTransaction().replace(binding.fragmentContainerView.id,
                Developer()
            ).commit()
            TYPE_FRAGMENT_MODEL_MANAGEMENT->supportFragmentManager.beginTransaction().replace(
                binding.fragmentContainerView.id,
                ModelManagementFragment.newInstance(
                    showSrTab = intent.getBooleanExtra(EXTRA_MODEL_SHOW_SR, false)
                )
            ).commit()
            else->Toast.makeText(applicationContext,"Unknown Error.", Toast.LENGTH_LONG).show()
        }
    }
}