package com.camscanner.pro.ui.detail

import android.graphics.BitmapFactory
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.camscanner.pro.data.local.entity.PageEntity
import com.camscanner.pro.databinding.ItemPageBinding
import java.io.File

class PageGridAdapter(
    private val onPageClick: (PageEntity) -> Unit,
    private val onOcrClick: (PageEntity) -> Unit,
    private val onDeleteClick: (PageEntity) -> Unit
) : ListAdapter<PageEntity, PageGridAdapter.PageViewHolder>(DiffCallback) {

    inner class PageViewHolder(private val binding: ItemPageBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(page: PageEntity, position: Int) {
            binding.tvPageNumber.text = (position + 1).toString()
            binding.tvFilterInfo.text = page.filterType.replace("_", " ").lowercase()
                .replaceFirstChar { it.uppercase() }

            if (File(page.imagePath).exists()) {
                val opts = BitmapFactory.Options().apply { inSampleSize = 4 }
                val bm = BitmapFactory.decodeFile(page.imagePath, opts)
                binding.ivPageImage.setImageBitmap(bm)
            }

            binding.root.setOnClickListener { onPageClick(page) }
            binding.btnPageOcr.setOnClickListener { onOcrClick(page) }
            binding.btnDeletePage.setOnClickListener { onDeleteClick(page) }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PageViewHolder {
        val binding = ItemPageBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return PageViewHolder(binding)
    }

    override fun onBindViewHolder(holder: PageViewHolder, position: Int) {
        holder.bind(getItem(position), position)
    }

    object DiffCallback : DiffUtil.ItemCallback<PageEntity>() {
        override fun areItemsTheSame(oldItem: PageEntity, newItem: PageEntity): Boolean =
            oldItem.id == newItem.id

        override fun areContentsTheSame(oldItem: PageEntity, newItem: PageEntity): Boolean =
            oldItem == newItem
    }
}
