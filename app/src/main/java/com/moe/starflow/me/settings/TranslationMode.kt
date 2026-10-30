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

import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.AnimationUtils
import androidx.fragment.app.Fragment
import com.moe.starflow.R
import com.moe.starflow.databinding.FragmentTranslationModeBinding
import com.moe.starflow.manga.OcrLock
import com.moe.starflow.translate.ImageTranslatorFactory
import com.moe.starflow.translate.batch.TranslationJobRegistry
import com.moe.starflow.utils.CustomPreference
import com.moe.starflow.utils.TranslationBusyRegistry
import com.moe.starflow.utils.TranslationExecutionMode
import com.moe.starflow.utils.UiUtils

class TranslationMode : Fragment() {
    private lateinit var binding: FragmentTranslationModeBinding
    private lateinit var prefs: CustomPreference

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = CustomPreference.getInstance(requireContext())
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        binding = FragmentTranslationModeBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        applyExecutionMode(animate = false)

        binding.translateModeLayout.setOnClickListener {
            selectMode(TranslationExecutionMode.TRANSLATE)
        }
        binding.ocrOnlyModeLayout.setOnClickListener {
            selectMode(TranslationExecutionMode.OCR_ONLY)
        }
        binding.picModeLayout.setOnClickListener {
            selectMode(TranslationExecutionMode.IMAGE)
        }

        // 截图方式选择
        updateScreenshotSelection(animate = false)
        binding.mediaprojectionLayout.setOnClickListener {
            prefs.setString("Screenshot_Method", "0")
            updateScreenshotSelection(animate = true)
        }
        binding.accessibilityLayout.setOnClickListener {
            prefs.setString("Screenshot_Method", "1")
            updateScreenshotSelection(animate = true)
        }
    }

    private fun selectMode(mode: Int) {
        if (!canChangeExecutionMode()) return
        TranslationExecutionMode.set(requireContext(), mode)
        applyExecutionMode(animate = true)
        if (mode == TranslationExecutionMode.IMAGE &&
            !ImageTranslatorFactory.isConfigured(requireContext(), prefs)
        ) {
            showImageApiMissingDialog()
        }
    }

    private fun showImageApiMissingDialog() {
        val dialog = AlertDialog.Builder(requireContext())
            .setTitle(R.string.image_mode_not_configured_title)
            .setMessage(R.string.image_mode_not_configured_content)
            .setCancelable(true)
            .setPositiveButton(R.string.go_to_config) { _, _ ->
                startActivity(
                    Intent(requireContext(), SettingPageActivity::class.java).apply {
                        putExtra(
                            SettingPageActivity.EXTRA_FRAGMENT_TYPE,
                            SettingPageActivity.TYPE_FRAGMENT_API_CONFIG,
                        )
                    }
                )
            }
            .setNegativeButton(R.string.user_cancel, null)
            .create()
        dialog.show()
        dialog.window?.setBackgroundDrawableResource(R.drawable.dialog_background)
    }

    private fun canChangeExecutionMode(): Boolean {
        val busy = OcrLock.isRunning || TranslationJobRegistry.hasActiveJobs.value || TranslationBusyRegistry.isBusy
        if (busy) {
            UiUtils.showToast(requireContext(), getString(R.string.execution_mode_busy), isShort = false)
            return false
        }
        return true
    }

    private fun applyExecutionMode(animate: Boolean) {
        val mode = TranslationExecutionMode.get(requireContext())
        val cards = mapOf(
            TranslationExecutionMode.TRANSLATE to binding.translateModeLayout,
            TranslationExecutionMode.OCR_ONLY to binding.ocrOnlyModeLayout,
            TranslationExecutionMode.IMAGE to binding.picModeLayout,
        )
        val selected = cards[mode] ?: binding.translateModeLayout
        cards.values.forEach { card ->
            card.setBackgroundResource(
                if (card === selected) {
                    R.drawable.custom_radio_button_selected_background
                } else {
                    R.drawable.custom_radio_button_background
                }
            )
        }
        if (animate) {
            val bounceIn = AnimationUtils.loadAnimation(requireContext(), R.anim.card_select_bounce_in)
            val bounceOut = AnimationUtils.loadAnimation(requireContext(), R.anim.card_select_bounce_out)
            bounceIn.setAnimationListener(object : android.view.animation.Animation.AnimationListener {
                override fun onAnimationStart(animation: android.view.animation.Animation?) {}
                override fun onAnimationRepeat(animation: android.view.animation.Animation?) {}
                override fun onAnimationEnd(animation: android.view.animation.Animation?) {
                    selected.startAnimation(bounceOut)
                }
            })
            selected.startAnimation(bounceIn)
        }
    }

    private fun updateScreenshotSelection(animate: Boolean) {
        val method = prefs.getString("Screenshot_Method", "0").toIntOrNull() ?: 0

        val selectedView: View
        val unselectedView: View

        if (method == 0) {
            selectedView = binding.mediaprojectionLayout
            unselectedView = binding.accessibilityLayout
        } else {
            selectedView = binding.accessibilityLayout
            unselectedView = binding.mediaprojectionLayout
        }

        // 更新背景
        selectedView.setBackgroundResource(R.drawable.custom_radio_button_selected_background)
        unselectedView.setBackgroundResource(R.drawable.custom_radio_button_background)

        // 选中动画：轻微放大再回弹
        if (animate) {
            val bounceIn = AnimationUtils.loadAnimation(requireContext(), R.anim.card_select_bounce_in)
            val bounceOut = AnimationUtils.loadAnimation(requireContext(), R.anim.card_select_bounce_out)
            bounceIn.setAnimationListener(object : android.view.animation.Animation.AnimationListener {
                override fun onAnimationStart(animation: android.view.animation.Animation?) {}
                override fun onAnimationRepeat(animation: android.view.animation.Animation?) {}
                override fun onAnimationEnd(animation: android.view.animation.Animation?) {
                    selectedView.startAnimation(bounceOut)
                }
            })
            selectedView.startAnimation(bounceIn)
        }
    }
}
