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

package com.moe.starflow.me.about
import com.moe.starflow.translate.widget.*
import com.moe.starflow.translate.autotranslate.*
import com.moe.starflow.translate.screenshot.*

import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.fragment.app.Fragment
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import com.moe.starflow.R
import com.moe.starflow.databinding.FragmentDeveloperBinding
import com.moe.starflow.utils.CustomPreference
import com.moe.starflow.utils.LogCollector
import com.moe.starflow.utils.UiUtils
import com.moe.starflow.me.ManageActivity
import translationapi.llamacpp.LlamaCppNative
import nl.dionsegijn.konfetti.core.Party
import nl.dionsegijn.konfetti.core.Position
import nl.dionsegijn.konfetti.core.emitter.Emitter
import nl.dionsegijn.konfetti.core.models.Shape
import java.util.concurrent.TimeUnit


class Developer : Fragment() {
    private lateinit var binding: FragmentDeveloperBinding
    private lateinit var prefs: CustomPreference
    private val party = Party(
        angle = 300,
        spread = 60,
        speed = 60f,
        maxSpeed = 70f,
        damping = 0.9f,
        colors = listOf(0xfce18a, 0xff726d, 0xf4306d, 0xb48def),
        shapes = listOf(Shape.Square, Shape.Circle),
        timeToLive = 5000L,
        fadeOutEnabled = true,
        position = Position.Relative(0.0,0.6),
        emitter = Emitter(duration = 5000, TimeUnit.MILLISECONDS).max(600)
    )
    private val party2 = Party(
        angle = 240,
        spread = 60,
        speed = 60f,
        maxSpeed = 70f,
        damping = 0.9f,
        colors = listOf(0xfce18a, 0xff726d, 0xf4306d, 0xb48def),
        shapes = listOf(Shape.Square, Shape.Circle),
        timeToLive = 5000L,
        fadeOutEnabled = true,
        position = Position.Relative(1.0,0.6),
        emitter = Emitter(duration = 5000, TimeUnit.MILLISECONDS).max(600)
    )

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        binding = FragmentDeveloperBinding.inflate(inflater,container,false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        prefs = CustomPreference.getInstance(requireContext())

        val cele = binding.konfettiViewd
        cele.start(party)
        cele.start(party2)

        // 意见反馈 - 显示联系方式
        binding.ideas.setOnClickListener {
            AlertDialog.Builder(requireContext())
                .setTitle(R.string.feedback)
                .setMessage(getString(R.string.developer_contact))
                .setPositiveButton(R.string.user_known, null)
                .show()
        }

        binding.opensource.setOnClickListener {
            val intent = Intent(requireContext(), ManageActivity::class.java).apply {
                putExtra(ManageActivity.EXTRA_FRAGMENT_TYPE, ManageActivity.OPEN_SOURCE)
            }
            startActivity(intent)
        }

        binding.github.setOnClickListener {
            val url = "https://github.com/xujunjiex/StarFlow"
            val intent = Intent(Intent.ACTION_VIEW)
            intent.data = Uri.parse(url)
            startActivity(intent)
        }

        // RT-DETR-V2 调试开关
        binding.rtdetrDebugSwitch.isChecked = prefs.getBoolean("RTDetrV2_Debug_View", false)
        binding.rtdetrDebugSwitch.setOnCheckedChangeListener { _, isChecked ->
            prefs.setBoolean("RTDetrV2_Debug_View", isChecked)
            if (isChecked) {
                UiUtils.showToast(requireContext(), getString(R.string.dev_debug_manga_toast, "RT-DETR-V2"))
            }
        }

        // ML Kit 调试开关
        binding.mlkitDebugSwitch.isChecked = prefs.getBoolean("MLKit_Debug_View", false)
        binding.mlkitDebugSwitch.setOnCheckedChangeListener { _, isChecked ->
            prefs.setBoolean("MLKit_Debug_View", isChecked)
            if (isChecked) {
                UiUtils.showToast(requireContext(), getString(R.string.dev_debug_manga_toast, "ML Kit"))
            }
        }

        // PP-OCRv5 调试开关
        binding.ppocrv5DebugSwitch.isChecked = prefs.getBoolean("PPOcrV5_Debug_View", false)
        binding.ppocrv5DebugSwitch.setOnCheckedChangeListener { _, isChecked ->
            prefs.setBoolean("PPOcrV5_Debug_View", isChecked)
            if (isChecked) {
                UiUtils.showToast(requireContext(), getString(R.string.dev_debug_manga_toast, "PP-OCRv5"))
            }
        }

        // PP-OCRv6 调试开关
        binding.ppocrv6DebugSwitch.isChecked = prefs.getBoolean("PPOcrV6_Debug_View", false)
        binding.ppocrv6DebugSwitch.setOnCheckedChangeListener { _, isChecked ->
            prefs.setBoolean("PPOcrV6_Debug_View", isChecked)
            if (isChecked) {
                UiUtils.showToast(requireContext(), getString(R.string.dev_debug_manga_toast, "PP-OCRv6"))
            }
        }

        // 游戏翻译调试开关
        binding.gameTranslateDebugSwitch.isChecked = prefs.getBoolean("Game_Translate_Debug_View", false)
        binding.gameTranslateDebugSwitch.setOnCheckedChangeListener { _, isChecked ->
            prefs.setBoolean("Game_Translate_Debug_View", isChecked)
            if (isChecked) {
                UiUtils.showToast(requireContext(), getString(R.string.dev_debug_game_toast))
            }
        }

        // 缓存命中标记（⚡）开关：控制内存缓存命中的译文前是否显示 ⚡（默认关闭）
        binding.cacheMarkerSwitch.isChecked =
            prefs.getBoolean(com.moe.starflow.data.TranslationCacheManager.KEY_CACHE_MARKER, false)
        binding.cacheMarkerSwitch.setOnCheckedChangeListener { _, isChecked ->
            prefs.setBoolean(com.moe.starflow.data.TranslationCacheManager.KEY_CACHE_MARKER, isChecked)
        }
    }

}
