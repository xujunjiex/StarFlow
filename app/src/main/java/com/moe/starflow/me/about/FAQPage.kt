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

import android.os.Bundle
import androidx.fragment.app.Fragment
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.LinearLayoutManager
import com.moe.starflow.databinding.FragmentFaqPageBinding
import androidx.recyclerview.widget.RecyclerView
import com.moe.starflow.R

class FAQPage : Fragment() {

    private lateinit var binding: FragmentFaqPageBinding
    private lateinit var recyclerView: RecyclerView
    private lateinit var adapter: CardAdapter

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        binding = FragmentFaqPageBinding.inflate(inflater,container,false)
        recyclerView = binding.cardRecyclerview
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val faqs = listOf(
            CustomCard(getText(R.string.faq_q1), getText(R.string.faq_a1)),
            CustomCard(getText(R.string.faq_q2), getText(R.string.faq_a2)),
            CustomCard(getText(R.string.faq_q3), getText(R.string.faq_a3)),
            CustomCard(getText(R.string.faq_q4), getText(R.string.faq_a4)),
            CustomCard(getText(R.string.faq_q5), getText(R.string.faq_a5)),
            CustomCard(getText(R.string.faq_q6), getText(R.string.faq_a6)),
            CustomCard(getText(R.string.faq_q7), getText(R.string.faq_a7)),
            CustomCard(getText(R.string.faq_q8), getText(R.string.faq_a8)),
            CustomCard(getText(R.string.faq_q9), getText(R.string.faq_a9)),
            CustomCard(getText(R.string.faq_q10), getText(R.string.faq_a10)),
            CustomCard(getText(R.string.faq_q11), getText(R.string.faq_a11)),
            CustomCard(getText(R.string.faq_q12), getText(R.string.faq_a12)),
            CustomCard(getText(R.string.faq_q13), getText(R.string.faq_a13)),
            CustomCard(getText(R.string.faq_library_recovery_question), getText(R.string.faq_library_recovery_answer),
                id = com.moe.starflow.library.BookRecoveryDialog.FAQ_CARD)
        )

        val target = requireActivity().intent.getStringExtra("faq_card")
        val position = faqs.indexOfFirst { it.id == target }
        if (position >= 0) faqs[position].isExpanded = true
        adapter = CardAdapter(faqs)
        recyclerView.layoutManager = LinearLayoutManager(requireContext())
        recyclerView.adapter = adapter
        if (position >= 0) recyclerView.post { recyclerView.scrollToPosition(position) }
    }
}