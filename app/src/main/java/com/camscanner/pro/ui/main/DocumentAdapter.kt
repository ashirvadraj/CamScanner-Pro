package com.camscanner.pro.ui.main

import android.graphics.BitmapFactory
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.camscanner.pro.R
import com.camscanner.pro.data.local.entity.DocumentEntity
import com.camscanner.pro.databinding.ItemDocumentBinding
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class DocumentAdapter(
    private val onItemClick: (DocumentEntity) -> Unit,
    private val onMoreClick: (View, DocumentEntity) -> Unit
) : ListAdapter<DocumentEntity, DocumentAdapter.DocViewHolder>(DiffCallback) {

    private val dateFormat = SimpleDateFormat("MMM dd, yyyy • hh:mm a", Locale.getDefault())

    inner class DocViewHolder(private val binding: ItemDocumentBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(doc: DocumentEntity) {
            binding.tvTitle.text = doc.title
            val formattedDate = dateFormat.format(Date(doc.updatedAt))
            val pageText = if (doc.pageCount == 1) "1 Page" else "${doc.pageCount} Pages"
            binding.tvDate.text = "$formattedDate • $pageText"

            if (!doc.ocrSnippet.isNullOrBlank()) {
                binding.tvSnippet.visibility = View.VISIBLE
                binding.tvSnippet.text = "“${doc.ocrSnippet}…”"
            } else {
                binding.tvSnippet.visibility = View.GONE
            }

            // Load thumbnail
            if (!doc.thumbnailPath.isNullOrBlank() && File(doc.thumbnailPath).exists()) {
                val opts = BitmapFactory.Options().apply { inSampleSize = 4 }
                val bm = BitmapFactory.decodeFile(doc.thumbnailPath, opts)
                binding.ivThumbnail.setImageBitmap(bm)
            } else {
                binding.ivThumbnail.setImageResource(R.drawable.ic_document_empty)
            }

            binding.root.setOnClickListener { onItemClick(doc) }
            binding.btnMore.setOnClickListener { view -> onMoreClick(view, doc) }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): DocViewHolder {
        val binding = ItemDocumentBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return DocViewHolder(binding)
    }

    override fun onBindViewHolder(holder: DocViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    object DiffCallback : DiffUtil.ItemCallback<DocumentEntity>() {
        override fun areItemsTheSame(oldItem: DocumentEntity, newItem: DocumentEntity): Boolean =
            oldItem.id == newItem.id

        override fun areContentsTheSame(oldItem: DocumentEntity, newItem: DocumentEntity): Boolean =
            oldItem == newItem
    }
}
