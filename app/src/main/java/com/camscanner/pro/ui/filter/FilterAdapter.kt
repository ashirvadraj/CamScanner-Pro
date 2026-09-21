package com.camscanner.pro.ui.filter

import android.graphics.Bitmap
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.camscanner.pro.R
import com.camscanner.pro.core.cv.FilterType
import com.camscanner.pro.databinding.ItemFilterBinding

data class FilterItem(
    val type: FilterType,
    val thumbnail: Bitmap,
    var isSelected: Boolean = false
)

class FilterAdapter(
    private val items: List<FilterItem>,
    private val onFilterSelected: (FilterType) -> Unit
) : RecyclerView.Adapter<FilterAdapter.FilterViewHolder>() {

    private var selectedIndex = 1 // Default to Magic Color

    init {
        if (items.isNotEmpty()) {
            items[selectedIndex.coerceIn(0, items.size - 1)].isSelected = true
        }
    }

    inner class FilterViewHolder(private val binding: ItemFilterBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(item: FilterItem, position: Int) {
            binding.tvFilterName.text = item.type.displayName
            binding.ivFilterThumbnail.setImageBitmap(item.thumbnail)

            if (item.isSelected) {
                binding.frameThumbnail.setBackgroundResource(R.drawable.bg_filter_selected)
                binding.tvFilterName.setTextColor(
                    ContextCompat.getColor(binding.root.context, R.color.primary)
                )
            } else {
                binding.frameThumbnail.background = null
                binding.tvFilterName.setTextColor(
                    ContextCompat.getColor(binding.root.context, R.color.white)
                )
            }

            binding.root.setOnClickListener {
                if (selectedIndex != position) {
                    val prevIndex = selectedIndex
                    selectedIndex = position
                    items[prevIndex].isSelected = false
                    items[selectedIndex].isSelected = true
                    notifyItemChanged(prevIndex)
                    notifyItemChanged(selectedIndex)
                    onFilterSelected(item.type)
                }
            }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): FilterViewHolder {
        val binding = ItemFilterBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return FilterViewHolder(binding)
    }

    override fun onBindViewHolder(holder: FilterViewHolder, position: Int) {
        holder.bind(items[position], position)
    }

    override fun getItemCount(): Int = items.size
}
