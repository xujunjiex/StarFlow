package com.moe.starflow.mangaimport

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import com.moe.starflow.databinding.FragmentImportMangaBinding

/**
 * 导入翻译 tab：书架页。首版先展示占位文本，后续任务接网格 + 导入 + 显示选项。
 */
class ImportMangaFragment : Fragment() {

    private var _binding: FragmentImportMangaBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentImportMangaBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
